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
package org.apache.rocketmq.client.producer.selector;

import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SelectMessageQueueByHashTest {

    private String topic = "FooBar";

    @Test
    public void testSelect() throws Exception {
        SelectMessageQueueByHash selector = new SelectMessageQueueByHash();

        Message message = new Message(topic, new byte[] {});

        List<MessageQueue> messageQueues = new ArrayList<MessageQueue>();
        for (int i = 0; i < 10; i++) {
            MessageQueue messageQueue = new MessageQueue(topic, "DefaultBroker", i);
            messageQueues.add(messageQueue);
        }

        String orderId = "123";
        String anotherOrderId = "234";
        MessageQueue selected = selector.select(messageQueues, message, orderId);
        MessageQueue selected234 = selector.select(messageQueues, message, anotherOrderId);
        assertThat(selected234).isNotEqualTo(selected);
    }

}