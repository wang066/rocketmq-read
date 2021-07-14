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

import org.apache.rocketmq.client.consumer.PullCallback;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.hook.FilterMessageContext;
import org.apache.rocketmq.client.hook.FilterMessageHook;
import org.apache.rocketmq.client.impl.CommunicationMode;
import org.apache.rocketmq.client.impl.FindBrokerResult;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.common.MQVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.filter.ExpressionType;
import org.apache.rocketmq.common.message.*;
import org.apache.rocketmq.common.protocol.header.PullMessageRequestHeader;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.common.protocol.route.TopicRouteData;
import org.apache.rocketmq.common.sysflag.PullSysFlag;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 拉取消息的APIWrapper包装器
 * @author ;
 */
public class PullAPIWrapper {
    /**
     * log
     */
    private final InternalLogger log = ClientLogger.getLog();

    private final MQClientInstance mQClientFactory;
    /**
     * 消费组
     */
    private final String consumerGroup;
    private final boolean unitMode;
    /**
     * key:是消息队列，v是brokerId,是从master读还是从slave读
     */
    private ConcurrentMap<MessageQueue, AtomicLong> pullFromWhichNodeTable = new ConcurrentHashMap<MessageQueue, AtomicLong>(32);
    /**
     *
     */
    private volatile boolean connectBrokerByUser = false;
    /**
     * 默认的brokerId为masterId
     */
    private volatile long defaultBrokerId = MixAll.MASTER_ID;
    /**
     * 随机数
     */
    private Random random = new Random(System.currentTimeMillis());
    /**
     * FilterMessageHook
     */
    private ArrayList<FilterMessageHook> filterMessageHookList = new ArrayList<FilterMessageHook>();

    public PullAPIWrapper(MQClientInstance mQClientFactory, String consumerGroup, boolean unitMode) {
        this.mQClientFactory = mQClientFactory;
        this.consumerGroup = consumerGroup;
        this.unitMode = unitMode;
    }

    /**
     * 处理请求结果
     * @param mq mq
     * @param pullResult pullResult
     * @param subscriptionData subscription
     * @return ;
     */
    public PullResult processPullResult(final MessageQueue mq, final PullResult pullResult,
        final SubscriptionData subscriptionData) {

        PullResultExt pullResultExt = (PullResultExt) pullResult;

        this.updatePullFromWhichNode(mq, pullResultExt.getSuggestWhichBrokerId());

        if (PullStatus.FOUND == pullResult.getPullStatus()) {
            ByteBuffer byteBuffer = ByteBuffer.wrap(pullResultExt.getMessageBinary());
            List<MessageExt> msgList = MessageDecoder.decodes(byteBuffer);

            List<MessageExt> msgListFilterAgain = msgList;
            //执行一遍客户端过滤
            if (!subscriptionData.getTagsSet().isEmpty() && !subscriptionData.isClassFilterMode()) {
                msgListFilterAgain = new ArrayList<MessageExt>(msgList.size());
                for (MessageExt msg : msgList) {
                    if (msg.getTags() != null) {
                        if (subscriptionData.getTagsSet().contains(msg.getTags())) {
                            msgListFilterAgain.add(msg);
                        }
                    }
                }
            }


            //执行MessageFilterHook
            if (this.hasHook()) {
                FilterMessageContext filterMessageContext = new FilterMessageContext();
                filterMessageContext.setUnitMode(unitMode);
                filterMessageContext.setMsgList(msgListFilterAgain);
                this.executeHook(filterMessageContext);
            }

            for (MessageExt msg : msgListFilterAgain) {
                String traFlag = msg.getProperty(MessageConst.PROPERTY_TRANSACTION_PREPARED);
                if (traFlag != null && Boolean.parseBoolean(traFlag)) {
                    msg.setTransactionId(msg.getProperty(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX));
                }
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_MIN_OFFSET,
                    Long.toString(pullResult.getMinOffset()));
                MessageAccessor.putProperty(msg, MessageConst.PROPERTY_MAX_OFFSET,
                    Long.toString(pullResult.getMaxOffset()));
            }

            pullResultExt.setMsgFoundList(msgListFilterAgain);
        }

        pullResultExt.setMessageBinary(null);

        return pullResult;
    }

    /**
     * 更新下一次从哪个broker读取
     * @param mq mq
     * @param brokerId brokerId
     */
    public void updatePullFromWhichNode(final MessageQueue mq, final long brokerId) {
        AtomicLong suggest = this.pullFromWhichNodeTable.get(mq);
        if (null == suggest) {
            this.pullFromWhichNodeTable.put(mq, new AtomicLong(brokerId));
        } else {
            suggest.set(brokerId);
        }
    }

    /**
     * 是否hasMessageFilterHook
     * @return ;
     */
    public boolean hasHook() {
        return !this.filterMessageHookList.isEmpty();
    }

    public void executeHook(final FilterMessageContext context) {
        if (!this.filterMessageHookList.isEmpty()) {
            for (FilterMessageHook hook : this.filterMessageHookList) {
                try {
                    hook.filterMessage(context);
                } catch (Throwable e) {
                    log.error("execute hook error. hookName={}", hook.hookName());
                }
            }
        }
    }

    /**
     * 拉取消息
     *
     * @param mq ;
     * @param subExpression ;
     * @param expressionType ;
     * @param subVersion ;
     * @param offset 偏移量
     * @param maxNums ;
     * @param sysFlag ;
     * @param commitOffset ;
     * @param brokerSuspendMaxTimeMillis ;
     * @param timeoutMillis ;
     * @param communicationMode ;
     * @param pullCallback ;
     * @return ;
     * @throws MQClientException ;
     * @throws RemotingException ;
     * @throws MQBrokerException ;
     * @throws InterruptedException ;
     */
    public PullResult pullKernelImpl(
        final MessageQueue mq,
        final String subExpression,
        final String expressionType,
        final long subVersion,
        final long offset,
        final int maxNums,
        final int sysFlag,
        final long commitOffset,
        final long brokerSuspendMaxTimeMillis,
        final long timeoutMillis,
        final CommunicationMode communicationMode,
        final PullCallback pullCallback
    ) throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        // 1、获取Broker的ID。以入参MessageQueue对象为参数调用PullAPIWrapper.recalculatePullFromWhichNode(MessageQueue mq)方法，在该方法中，先判断PullAPIWrapper.connectBrokerByUser变量是否为true（在FiltersrvController中启动时会设置为true，默认为false），若是则直接返回0（主用Broker的brokerId）；否则以MessageQueue对象为参数从PullAPIWrapper.pullFromWhichNodeTable:ConcurrentHashMap<MessageQueue, AtomicLong获取brokerId，若该值不为null则返回该值，否则返回0（主用Broker的brokerId）；

        // 2调用MQClientInstance.findBrokerAddressInSubscribe(String brokerName ,long brokerId,boolean onlyThisBroker) 方法查找Broker地址，其中onlyThisBroker=false，表示若指定的brokerId未获取到地址则获取其他BrokerId的地址也行。在该方法中根据brokerName和brokerId参数从MQClientInstance.brokerAddrTable: ConcurrentHashMap<, HashMap变量中获取对应的Broker地址，若获取不到则从brokerName下面的Map列表中找其他地址返回即可；
        FindBrokerResult findBrokerResult =
            this.mQClientFactory.findBrokerAddressInSubscribe(mq.getBrokerName(),
                this.recalculatePullFromWhichNode(mq), false);

        if (null == findBrokerResult) {
            //   3、若在上一步未获取到Broker地址，则以topic参数调用MQClientInstance.updateTopicRouteInfoFromNameServer(String topic)方法，然后在执行第2步的操作，直到获取到Broker地址为止；
            this.mQClientFactory.updateTopicRouteInfoFromNameServer(mq.getTopic());
            findBrokerResult =
                this.mQClientFactory.findBrokerAddressInSubscribe(mq.getBrokerName(),
                    this.recalculatePullFromWhichNode(mq), false);
        }

        if (findBrokerResult != null) {
            {
                // check version
                if (!ExpressionType.isTagType(expressionType)
                    && findBrokerResult.getBrokerVersion() < MQVersion.Version.V4_1_0_SNAPSHOT.ordinal()) {
                    throw new MQClientException("The broker[" + mq.getBrokerName() + ", "
                        + findBrokerResult.getBrokerVersion() + "] does not upgrade to support for filter message by " + expressionType, null);
                }
            }
            int sysFlagInner = sysFlag;
            // 4、若获取的Broker地址是备用Broker，则将标记位sysFlag的第1个字节置为0，即在消费完之后不提交消费进度；
            if (findBrokerResult.isSlave()) {
                sysFlagInner = PullSysFlag.clearCommitOffsetFlag(sysFlagInner);
            }

            PullMessageRequestHeader requestHeader = new PullMessageRequestHeader();
            requestHeader.setConsumerGroup(this.consumerGroup);
            requestHeader.setTopic(mq.getTopic());
            requestHeader.setQueueId(mq.getQueueId());
            requestHeader.setQueueOffset(offset);
            requestHeader.setMaxMsgNums(maxNums);
            requestHeader.setSysFlag(sysFlagInner);
            requestHeader.setCommitOffset(commitOffset);
            requestHeader.setSuspendTimeoutMillis(brokerSuspendMaxTimeMillis);
            requestHeader.setSubscription(subExpression);
            requestHeader.setSubVersion(subVersion);
            requestHeader.setExpressionType(expressionType);

            String brokerAddr = findBrokerResult.getBrokerAddr();
            // 5、检查标记位sysFlag的第4个字节（即SubscriptionData. classFilterMode）是否为1；若等于1，则调用PullAPIWrapper.computPullFromWhichFilterServer(String topic, String brokerAddr)方法获取Filter服务器地址。大致逻辑如下：
            // 5.1)根据topic参数值从MQClientInstance.topicRouteTable: ConcurrentHashMapTopicRouteData>变量中获取TopicRouteData对象，
            // 5.2)以Broker地址为参数从该TopicRouteData对象的filterServerTable:HashMap变量中获取该Broker下面的所有Filter服务器地址列表；
            // 5.3)若该地址列表不为空，则随机选择一个Filter服务器地址返回；否则向调用层抛出异常，该pullKernelImpl方法结束；
            if (PullSysFlag.hasClassFilterFlag(sysFlagInner)) {
                brokerAddr = computPullFromWhichFilterServer(mq.getTopic(), brokerAddr);
            }

            /*
             * 拉取消息
             * 6、构建PullMessageRequestHeader对象，其中queueOffset变量值等于入参offset；
             */
            PullResult pullResult = this.mQClientFactory.getMQClientAPIImpl().pullMessage(
                brokerAddr,
                requestHeader,
                timeoutMillis,
                communicationMode,
                pullCallback);

            return pullResult;
        }

        throw new MQClientException("The broker[" + mq.getBrokerName() + "] not exist", null);
    }

    /**
     * 拉取消息
     * @param mq mq
     * @param subExpression tag
     * @param subVersion version
     * @param offset 偏移量
     * @param maxNums 最大量
     * @param sysFlag 系统flag
     * @param commitOffset commitoffset
     * @param brokerSuspendMaxTimeMillis broker挂起的最大时间
     * @param timeoutMillis 超时时间
     * @param communicationMode 模式
     * @param pullCallback 异步callback
     * @return ;
     * @throws MQClientException ;
     * @throws RemotingException ;
     * @throws MQBrokerException ;
     * @throws InterruptedException ;
     */
    public PullResult pullKernelImpl(
        final MessageQueue mq,
        final String subExpression,
        final long subVersion,
        final long offset,
        final int maxNums,
        final int sysFlag,
        final long commitOffset,
        final long brokerSuspendMaxTimeMillis,
        final long timeoutMillis,
        final CommunicationMode communicationMode,
        final PullCallback pullCallback
    ) throws MQClientException, RemotingException, MQBrokerException, InterruptedException {
        return pullKernelImpl(
            mq,
            subExpression,
            ExpressionType.TAG,
            subVersion,
                offset,
            maxNums,
            sysFlag,
            commitOffset,
            brokerSuspendMaxTimeMillis,
            timeoutMillis,
            communicationMode,
            pullCallback
        );
    }

    /**
     * 计算从哪个节点拉取数据
     * @param mq mq
     * @return ;
     */
    public long recalculatePullFromWhichNode(final MessageQueue mq) {
        if (this.isConnectBrokerByUser()) {
            return this.defaultBrokerId;
        }

        AtomicLong suggest = this.pullFromWhichNodeTable.get(mq);
        if (suggest != null) {
            return suggest.get();
        }

        return MixAll.MASTER_ID;
    }

    /**
     * BrokerAddr变成从哪个FilterServer拉取
     * @param topic topic
     * @param brokerAddr brokerAddr
     * @return ;
     * @throws MQClientException ;
     */
    private String computPullFromWhichFilterServer(final String topic, final String brokerAddr)
        throws MQClientException {

        ConcurrentMap<String, TopicRouteData> topicRouteTable = this.mQClientFactory.getTopicRouteTable();
        if (topicRouteTable != null) {
            TopicRouteData topicRouteData = topicRouteTable.get(topic);
            List<String> list = topicRouteData.getFilterServerTable().get(brokerAddr);

            /*
             * 从FilterServer中随机查找一个List
             */
            if (list != null && !list.isEmpty()) {
                return list.get(randomNum() % list.size());
            }
        }

        throw new MQClientException("Find Filter Server Failed, Broker Addr: " + brokerAddr + " topic: " + topic, null);
    }

    public boolean isConnectBrokerByUser() {
        return connectBrokerByUser;
    }

    public void setConnectBrokerByUser(boolean connectBrokerByUser) {
        this.connectBrokerByUser = connectBrokerByUser;

    }

    /**
     * 随机数
     * @return ;
     */
    public int randomNum() {
        int value = random.nextInt();
        if (value < 0) {
            value = Math.abs(value);
            if (value < 0) {
                value = 0;
            }
        }
        return value;
    }

    public void registerFilterMessageHook(ArrayList<FilterMessageHook> filterMessageHookList) {
        this.filterMessageHookList = filterMessageHookList;
    }

    public long getDefaultBrokerId() {
        return defaultBrokerId;
    }

    public void setDefaultBrokerId(long defaultBrokerId) {
        this.defaultBrokerId = defaultBrokerId;
    }
}
