/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.client.impl.consumer;

import java.util.List;
import java.util.Set;
import org.apache.rocketmq.client.consumer.AllocateMessageQueueStrategy;
import org.apache.rocketmq.client.consumer.MessageQueueListener;
import org.apache.rocketmq.client.consumer.store.ReadOffsetType;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.UtilAll;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.heartbeat.ConsumeType;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;

public class RebalanceLitePullImpl extends RebalanceImpl {

    private final DefaultLitePullConsumerImpl litePullConsumerImpl;

    public RebalanceLitePullImpl(DefaultLitePullConsumerImpl litePullConsumerImpl) {
        this(null, null, null, null, litePullConsumerImpl);
    }

    public RebalanceLitePullImpl(String consumerGroup, MessageModel messageModel,
        AllocateMessageQueueStrategy allocateMessageQueueStrategy,
        MQClientInstance mQClientFactory, DefaultLitePullConsumerImpl litePullConsumerImpl) {
        super(consumerGroup, messageModel, allocateMessageQueueStrategy, mQClientFactory);
        this.litePullConsumerImpl = litePullConsumerImpl;
    }

    @Override
    public void messageQueueChanged(String topic, Set<MessageQueue> mqAll, Set<MessageQueue> mqDivided) {
        MessageQueueListener messageQueueListener = this.litePullConsumerImpl.getDefaultLitePullConsumer().getMessageQueueListener();
        if (messageQueueListener != null) {
            try {
                messageQueueListener.messageQueueChanged(topic, mqAll, mqDivided);
            } catch (Throwable e) {
                log.error("messageQueueChanged exception", e);
            }
        }
    }

    @Override
    public boolean removeUnnecessaryMessageQueue(MessageQueue mq, ProcessQueue pq) {
        this.litePullConsumerImpl.getOffsetStore().persist(mq);
        this.litePullConsumerImpl.getOffsetStore().removeOffset(mq);
        return true;
    }

    @Override
    public ConsumeType consumeType() {
        return ConsumeType.CONSUME_ACTIVELY;
    }

    @Override
    public void removeDirtyOffset(final MessageQueue mq) {
        this.litePullConsumerImpl.getOffsetStore().removeOffset(mq);
    }

    @Deprecated
    @Override
    public long computePullFromWhere(MessageQueue mq) {
        long result = -1L;
        try {
            result = computePullFromWhereWithException(mq);
        } catch (MQClientException e) {
            log.warn("Compute consume offset exception, mq={}", mq);
        }
        return result;
    }

    @Override
    public long computePullFromWhereWithException(MessageQueue mq) throws MQClientException {
        long lastOffset = litePullConsumerImpl.getOffsetStore().readOffset(mq, ReadOffsetType.MEMORY_FIRST_THEN_STORE);
        if (lastOffset >= 0) {
            return lastOffset;
        } else if (-1 == lastOffset) {
            // First start, no offset
            return findConsumeFromWhere(mq);
        } else {
            return -1;
        }
    }

    private long findConsumeFromWhere(MessageQueue mq) throws MQClientException {
        ConsumeFromWhere consumeFromWhere = litePullConsumerImpl.getDefaultLitePullConsumer().getConsumeFromWhere();
        switch (consumeFromWhere) {
            case CONSUME_FROM_LAST_OFFSET: {
                if (mq.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                    return 0L;
                } else {
                    try {
                        return this.mQClientFactory.getMQAdminImpl().maxOffset(mq);
                    } catch (MQClientException e) {
                        log.warn("Compute consume offset from last offset exception, mq={}, exception={}", mq, e);
                        throw e;
                    }
                }
            }
            case CONSUME_FROM_FIRST_OFFSET: {
                return 0L;
            }
            case CONSUME_FROM_TIMESTAMP: {
                if (mq.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                    try {
                        return this.mQClientFactory.getMQAdminImpl().maxOffset(mq);
                    } catch (MQClientException e) {
                        log.warn("Compute consume offset from last offset exception, mq={}, exception={}", mq, e);
                        throw e;
                    }
                } else {
                    try {
                        long timestamp = UtilAll.parseDate(this.litePullConsumerImpl.getDefaultLitePullConsumer().getConsumeTimestamp(),
                                UtilAll.YYYYMMDDHHMMSS).getTime();
                        return this.mQClientFactory.getMQAdminImpl().searchOffset(mq, timestamp);
                    } catch (MQClientException e) {
                        log.warn("Compute consume offset from last offset exception, mq={}, exception={}", mq, e);
                        throw e;
                    }
                }
            }
        }
        return -1;
    }

    @Override
    public void dispatchPullRequest(List<PullRequest> pullRequestList) {
    }

}
