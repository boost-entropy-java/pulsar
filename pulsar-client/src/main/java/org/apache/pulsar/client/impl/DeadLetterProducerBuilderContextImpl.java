/*
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
package org.apache.pulsar.client.impl;

import lombok.AllArgsConstructor;
import org.apache.pulsar.client.api.DeadLetterProducerBuilderContext;

@AllArgsConstructor
class DeadLetterProducerBuilderContextImpl implements DeadLetterProducerBuilderContext {
    private final String defaultTopicName;
    private final String inputTopicName;
    private final String inputTopicSubscriptionName;
    private final String inputTopicConsumerName;

    @Override
    public String getDefaultTopicName() {
        return defaultTopicName;
    }

    @Override
    public String getInputTopicName() {
        return inputTopicName;
    }

    @Override
    public String getInputTopicSubscriptionName() {
        return inputTopicSubscriptionName;
    }

    @Override
    public String getInputTopicConsumerName() {
        return inputTopicConsumerName;
    }
}
