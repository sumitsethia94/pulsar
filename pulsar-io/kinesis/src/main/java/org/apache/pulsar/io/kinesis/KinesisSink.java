/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.io.kinesis;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration.ThreadingModel;
import com.amazonaws.services.kinesis.producer.UserRecordResult;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.kinesis.KinesisSinkConfig.MessageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Kinesis sink which can be configured by {@link KinesisSinkConfig}.
 * <pre>
 * {@link KinesisSinkConfig} accepts
 * 1. <b>awsEndpoint:</b> kinesis end-point url can be found at : https://docs.aws.amazon.com/general/latest/gr/rande.html
 * 2. <b>awsRegion:</b> appropriate aws region eg: us-west-1, us-west-2
 * 3. <b>awsKinesisStreamName:</b> kinesis stream name
 * 4. <b>awsCredentialPluginName:</b> Fully-Qualified class name of implementation of {@link AwsCredentialProviderPlugin}.
 *    - It is a factory class which creates an {@link AWSCredentialsProvider} that will be used by {@link KinesisProducer}
 *    - If it is empty then {@link KinesisSink} creates default {@link AWSCredentialsProvider}
 *      which accepts json-map of credentials in awsCredentialPluginParam
 *      eg: awsCredentialPluginParam = {"accessKey":"my-access-key","secretKey":"my-secret-key"}
 * 5. <b>awsCredentialPluginParam:</b> json-parameters to initialize {@link AwsCredentialProviderPlugin}
 * 6. messageFormat: enum:["ONLY_RAW_PAYLOAD","FULL_MESSAGE_IN_JSON"]
 *   a. ONLY_RAW_PAYLOAD:     publishes raw payload to stream
 *   b. FULL_MESSAGE_IN_JSON: publish full message (encryptionCtx + properties + payload) in json format
 *   json-schema:
 *   {"type":"object","properties":{"encryptionCtx":{"type":"object","properties":{"metadata":{"type":"object","additionalProperties":{"type":"string"}},"uncompressedMessageSize":{"type":"integer"},"keysMetadataMap":{"type":"object","additionalProperties":{"type":"object","additionalProperties":{"type":"string"}}},"keysMapBase64":{"type":"object","additionalProperties":{"type":"string"}},"encParamBase64":{"type":"string"},"compressionType":{"type":"string","enum":["NONE","LZ4","ZLIB"]},"batchSize":{"type":"integer"},"algorithm":{"type":"string"}}},"payloadBase64":{"type":"string"},"properties":{"type":"object","additionalProperties":{"type":"string"}}}}
 *   Example:
 *   {"payloadBase64":"cGF5bG9hZA==","properties":{"prop1":"value"},"encryptionCtx":{"keysMapBase64":{"key1":"dGVzdDE=","key2":"dGVzdDI="},"keysMetadataMap":{"key1":{"ckms":"cmks-1","version":"v1"},"key2":{"ckms":"cmks-2","version":"v2"}},"metadata":{"ckms":"cmks-1","version":"v1"},"encParamBase64":"cGFyYW0=","algorithm":"algo","compressionType":"LZ4","uncompressedMessageSize":10,"batchSize":10}}
 * </pre>
 *
 *
 *
 */
public class KinesisSink implements Sink<byte[]> {

    private static final Logger LOG = LoggerFactory.getLogger(KinesisSink.class);

    private KinesisProducer kinesisProducer;
    private KinesisSinkConfig kinesisSinkConfig;
    private String streamName;
    private static final String defaultPartitionedKey = "default";
    private static final int maxPartitionedKeyLength = 256;
    private SinkContext sinkContext;
    // 
    private static final int FALSE = 0;
    private static final int TRUE = 1;
    private volatile int previousPublishFailed = FALSE;
    private static final AtomicIntegerFieldUpdater<KinesisSink> IS_PUBLISH_FAILED =
            AtomicIntegerFieldUpdater.newUpdater(KinesisSink.class, "previousPublishFailed");

    public static final String ACCESS_KEY_NAME = "accessKey";
    public static final String SECRET_KEY_NAME = "secretKey";

    public static final String METRICS_TOTAL_INCOMING = "_kinesis_total_incoming_";
    public static final String METRICS_TOTAL_INCOMING_BYTES = "_kinesis_total_incoming_bytes_";
    public static final String METRICS_TOTAL_SUCCESS = "_kinesis_total_success_";
    public static final String METRICS_TOTAL_FAILURE = "_kinesis_total_failure_";
         
    
    @Override
    public void write(Record<byte[]> record) throws Exception {
        // kpl-thread captures publish-failure. fail the publish on main pulsar-io-thread to maintain the ordering 
        if (kinesisSinkConfig.isRetainOrdering() && previousPublishFailed == TRUE) {
            LOG.warn("Skip acking message to retain ordering with previous failed message {}-{}", this.streamName,
                    record.getRecordSequence());
            throw new IllegalStateException("kinesis queue has publish failure");
        }
        String partitionedKey = record.getKey().orElse(record.getTopicName().orElse(defaultPartitionedKey));
        partitionedKey = partitionedKey.length() > maxPartitionedKeyLength
                ? partitionedKey.substring(0, maxPartitionedKeyLength - 1)
                : partitionedKey; // partitionedKey Length must be at least one, and at most 256
        ByteBuffer data = createKinesisMessage(kinesisSinkConfig.getMessageFormat(), record);
        ListenableFuture<UserRecordResult> addRecordResult = kinesisProducer.addUserRecord(this.streamName,
                partitionedKey, data);
        addCallback(addRecordResult,
                ProducerSendCallback.create(this, record, System.nanoTime()), directExecutor());
        if (sinkContext != null) {
            sinkContext.recordMetric(METRICS_TOTAL_INCOMING, 1);
            sinkContext.recordMetric(METRICS_TOTAL_INCOMING_BYTES, data.array().length);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Published message to kinesis stream {} with size {}", streamName, record.getValue().length);
        }
    }

    @Override
    public void close() throws IOException {
        if (kinesisProducer != null) {
            kinesisProducer.flush();
            kinesisProducer.destroy();
        }
        LOG.info("Kinesis sink stopped.");
    }

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) throws Exception {
        kinesisSinkConfig = KinesisSinkConfig.load(config);
        this.sinkContext = sinkContext;

        checkArgument(isNotBlank(kinesisSinkConfig.getAwsKinesisStreamName()), "empty kinesis-stream name");
        checkArgument(isNotBlank(kinesisSinkConfig.getAwsEndpoint()), "empty aws-end-point");
        checkArgument(isNotBlank(kinesisSinkConfig.getAwsRegion()), "empty aws region name");
        checkArgument(isNotBlank(kinesisSinkConfig.getAwsKinesisStreamName()), "empty kinesis stream name");
        checkArgument(isNotBlank(kinesisSinkConfig.getAwsCredentialPluginParam()), "empty aws-credential param");

        KinesisProducerConfiguration kinesisConfig = new KinesisProducerConfiguration();
        kinesisConfig.setKinesisEndpoint(kinesisSinkConfig.getAwsEndpoint());
        kinesisConfig.setRegion(kinesisSinkConfig.getAwsRegion());
        kinesisConfig.setThreadingModel(ThreadingModel.POOLED);
        kinesisConfig.setThreadPoolSize(4);
        kinesisConfig.setCollectionMaxCount(1);
        AWSCredentialsProvider credentialsProvider = createCredentialProvider(
                kinesisSinkConfig.getAwsCredentialPluginName(), kinesisSinkConfig.getAwsCredentialPluginParam());
        kinesisConfig.setCredentialsProvider(credentialsProvider);

        this.streamName = kinesisSinkConfig.getAwsKinesisStreamName();
        this.kinesisProducer = new KinesisProducer(kinesisConfig);
        IS_PUBLISH_FAILED.set(this, FALSE);

        LOG.info("Kinesis sink started. {}", (ReflectionToStringBuilder.toString(kinesisConfig, ToStringStyle.SHORT_PREFIX_STYLE)));
    }

    protected AWSCredentialsProvider createCredentialProvider(String awsCredentialPluginName,
            String awsCredentialPluginParam) {
        if (isNotBlank(awsCredentialPluginName)) {
            return createCredentialProviderWithPlugin(awsCredentialPluginName, awsCredentialPluginParam);
        } else {
            return defaultCredentialProvider(awsCredentialPluginParam);
        }
    }

    private static final class ProducerSendCallback implements FutureCallback<UserRecordResult> {

        private Record<byte[]> resultContext;
        private long startTime = 0;
        private final Handle<ProducerSendCallback> recyclerHandle;
        private KinesisSink kinesisSink;

        private ProducerSendCallback(Handle<ProducerSendCallback> recyclerHandle) {
            this.recyclerHandle = recyclerHandle;
        }

        static ProducerSendCallback create(KinesisSink kinesisSink, Record<byte[]> resultContext, long startTime) {
            ProducerSendCallback sendCallback = RECYCLER.get();
            sendCallback.resultContext = resultContext;
            sendCallback.kinesisSink = kinesisSink;
            sendCallback.startTime = startTime;
            return sendCallback;
        }

        private void recycle() {
            resultContext = null;
            kinesisSink = null;
            startTime = 0;
            recyclerHandle.recycle(this);
        }

        private static final Recycler<ProducerSendCallback> RECYCLER = new Recycler<ProducerSendCallback>() {
            @Override
            protected ProducerSendCallback newObject(Handle<ProducerSendCallback> handle) {
                return new ProducerSendCallback(handle);
            }
        };

        @Override
        public void onSuccess(UserRecordResult result) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Successfully published message for {}-{} with latency {}", kinesisSink.streamName, result.getShardId(),
                        TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startTime)));
            }
            if (kinesisSink.sinkContext != null) {
                kinesisSink.sinkContext.recordMetric(METRICS_TOTAL_SUCCESS, 1);
            }
            if (kinesisSink.kinesisSinkConfig.isRetainOrdering() && kinesisSink.previousPublishFailed == TRUE) {
                LOG.warn("Skip acking message to retain ordering with previous failed message {}-{} on shard {}",
                        kinesisSink.streamName, resultContext.getRecordSequence(), result.getShardId());
            } else {
                this.resultContext.ack();
            }
            recycle();
        }

        @Override
        public void onFailure(Throwable exception) {
            LOG.error("[{}] Failed to published message for replicator of {}-{}, {} ", kinesisSink.streamName,
                    resultContext.getPartitionId(), resultContext.getRecordSequence(), exception.getMessage());
            kinesisSink.previousPublishFailed = TRUE;
            if (kinesisSink.sinkContext != null) {
                kinesisSink.sinkContext.recordMetric(METRICS_TOTAL_FAILURE, 1);
            }
            recycle();
        }
    }

    /**
     * Creates a instance of credential provider which can return {@link AWSCredentials} or {@link BasicAWSCredentials}
     * based on IAM user/roles.
     *
     * @param pluginFQClassName
     * @param param
     * @return
     * @throws IllegalArgumentException
     */
    public static AWSCredentialsProvider createCredentialProviderWithPlugin(String pluginFQClassName, String param)
            throws IllegalArgumentException {
        try {
            Class<?> clazz = Class.forName(pluginFQClassName);
            Constructor<?> ctor = clazz.getConstructor();
            final AwsCredentialProviderPlugin plugin = (AwsCredentialProviderPlugin) ctor.newInstance(new Object[] {});
            plugin.init(param);
            return plugin.getCredentialProvider();
        } catch (Exception e) {
            LOG.error("Failed to initialize AwsCredentialProviderPlugin {}", pluginFQClassName, e);
            throw new IllegalArgumentException(
                    String.format("invalid authplugin name %s , failed to init %s", pluginFQClassName, e.getMessage()));
        }
    }

    /**
     * It creates a default credential provider which takes accessKey and secretKey form configuration and creates
     * {@link AWSCredentials}
     *
     * @param awsCredentialPluginParam
     * @return
     */
    protected AWSCredentialsProvider defaultCredentialProvider(String awsCredentialPluginParam) {
        Map<String, String> credentialMap = new Gson().fromJson(awsCredentialPluginParam,
                new TypeToken<Map<String, String>>() {
                }.getType());
        String accessKey = credentialMap.get(ACCESS_KEY_NAME);
        String secretKey = credentialMap.get(SECRET_KEY_NAME);
        checkArgument(isNotBlank(accessKey) && isNotBlank(secretKey),
                String.format(
                        "Default %s and %s must be present into json-map if AwsCredentialProviderPlugin not provided",
                        ACCESS_KEY_NAME, SECRET_KEY_NAME));
        return defaultCredentialProvider(accessKey, secretKey);
    }

    private AWSCredentialsProvider defaultCredentialProvider(String accessKey, String secretKey) {
        return new AWSCredentialsProvider() {
            @Override
            public AWSCredentials getCredentials() {
                return new AWSCredentials() {
                    @Override
                    public String getAWSAccessKeyId() {
                        return accessKey;
                    }

                    @Override
                    public String getAWSSecretKey() {
                        return secretKey;
                    }
                };
            }
            @Override
            public void refresh() {
                // no-op
            }
        };
    }

    public static ByteBuffer createKinesisMessage(MessageFormat msgFormat, Record<byte[]> record) {
        if (MessageFormat.FULL_MESSAGE_IN_JSON.equals(msgFormat)) {
            return ByteBuffer.wrap(Utils.serializeRecordToJson(record).getBytes());
        } else if (MessageFormat.FULL_MESSAGE_IN_FB.equals(msgFormat)) {
            return Utils.serializeRecordToFlatBuffer(record);
        } else {
            // send raw-message
            return ByteBuffer.wrap(record.getValue());
        }
    }

}