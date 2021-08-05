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
package org.apache.rocketmq.client.consumer.rebalance;

import org.apache.rocketmq.client.consumer.AllocateMessageQueueStrategy;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.logging.InternalLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认负载均衡
 * Average Hashing queue algorithm
 * 平均哈希队列的负载均衡算法。每个cid平均固定的队列
 *
 * consumer在运行时，通过两种机制来触发Rebalance：
 *
 * 监听broker 消费者数量变化通知，触发rebalance
 *
 * 周期性触发rebalance，避免Broker的Rebalance通知丢失。
 * @author ;
 */
public class AllocateMessageQueueAveragely implements AllocateMessageQueueStrategy {
    private final InternalLogger log = ClientLogger.getLog();

    @Override
    public List<MessageQueue> allocate(String consumerGroup, String currentCID, List<MessageQueue> mqAll,
        List<String> cidAll) {

        if (currentCID == null || currentCID.length() < 1) {
            throw new IllegalArgumentException("currentCID is empty");
        }
        if (mqAll == null || mqAll.isEmpty()) {
            throw new IllegalArgumentException("mqAll is null or mqAll empty");
        }
        if (cidAll == null || cidAll.isEmpty()) {
            throw new IllegalArgumentException("cidAll is null or cidAll empty");
        }

        List<MessageQueue> result = new ArrayList<MessageQueue>();
        if (!cidAll.contains(currentCID)) {
            log.info("[BUG] ConsumerGroup: {} The consumerId: {} not in cidAll: {}",
                consumerGroup,
                currentCID,
                cidAll);
            return result;
        }

        int index = cidAll.indexOf(currentCID);
        int mod = mqAll.size() % cidAll.size();
        //平均每个客户端的拉取数量
        // 1. mq数量 <= consumer数量，size = 1
        // 2. 否则，size = mq数量 / consumer数量，余数是几则前几个consumer的size+1,这样所有的queue都会有consumer消费
        int averageSize =
                mqAll.size() <= cidAll.size() ? 1 :

                        (mod > 0 && index < mod ? mqAll.size() / cidAll.size()
                + 1 : mqAll.size() / cidAll.size());
        //起始位置
        int startIndex = (mod > 0 && index < mod) ? index * averageSize : index * averageSize + mod;
        //averageSize和size-startIndex里最小的一个
        int range = Math.min(averageSize, mqAll.size() - startIndex);
        // 从第一个consumer开始分配，每个分avgSize个连续的Queue，
        for (int i = 0; i < range; i++) {
            result.add(mqAll.get((startIndex + i) % mqAll.size()));
        }
        return result;
    }

    /**
     * 平均法
     * @return ;
     */
    @Override
    public String getName() {
        return "AVG";
    }
}
