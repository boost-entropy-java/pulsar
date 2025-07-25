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
package org.apache.pulsar.broker.service;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Cleanup;
import org.apache.bookkeeper.mledger.PositionFactory;
import org.apache.pulsar.broker.service.persistent.PersistentSubscription;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.common.policies.data.ManagedLedgerInternalStats.CursorStats;
import org.apache.pulsar.common.policies.data.PersistentTopicInternalStats;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.TopicPolicies;
import org.apache.pulsar.common.util.FutureUtil;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test(groups = "broker")
public class MessageTTLTest extends BrokerTestBase {

    private static final Logger log = LoggerFactory.getLogger(MessageTTLTest.class);

    @BeforeClass
    @Override
    protected void setup() throws Exception {
        this.conf.setTtlDurationDefaultInSeconds(1);
        this.conf.setBrokerDeleteInactiveTopicsEnabled(false);
        super.baseSetup();
    }

    @AfterClass(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testMessageExpiryAfterTopicUnload() throws Exception {
        int numMsgs = 50;
        final String topicName = "persistent://prop/ns-abc/testttl";
        final String subscriptionName = "ttl-sub-1";

        pulsarClient.newConsumer()
                .topic(topicName)
                .subscriptionName(subscriptionName)
                .subscribe()
                .close();

        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName)
                .enableBatching(false) // this makes the test easier and predictable
                .create();

        List<CompletableFuture<MessageId>> sendFutureList = new ArrayList<>();
        for (int i = 0; i < numMsgs; i++) {
            byte[] message = ("my-message-" + i).getBytes();
            sendFutureList.add(producer.sendAsync(message));
        }
        FutureUtil.waitForAll(sendFutureList).get();
        MessageIdImpl firstMessageId = (MessageIdImpl) sendFutureList.get(0).get();
        MessageIdImpl lastMessageId = (MessageIdImpl) sendFutureList.get(sendFutureList.size() - 1).get();
        producer.close();
        // unload a reload the topic
        // this action created a new ledger
        // having a managed ledger with more than one
        // ledger should not impact message expiration
        admin.topics().unload(topicName);
        admin.topics().getStats(topicName);

        PersistentTopicInternalStats internalStatsBeforeExpire = admin.topics().getInternalStats(topicName);
        CursorStats statsBeforeExpire = internalStatsBeforeExpire.cursors.get(subscriptionName);
        log.info("markDeletePosition before expire {}", statsBeforeExpire.markDeletePosition);
        assertEquals(statsBeforeExpire.markDeletePosition,
                PositionFactory.create(firstMessageId.getLedgerId(), -1).toString());

        Awaitility.await().timeout(30, TimeUnit.SECONDS)
                .pollDelay(3, TimeUnit.SECONDS).untilAsserted(() -> {
            this.runMessageExpiryCheck();
            log.info("***** run message expiry now");
            // verify that the markDeletePosition was moved forward, and exacly to the last message
            PersistentTopicInternalStats internalStatsAfterExpire = admin.topics().getInternalStats(topicName);
            CursorStats statsAfterExpire = internalStatsAfterExpire.cursors.get(subscriptionName);
            log.info("markDeletePosition after expire {}", statsAfterExpire.markDeletePosition);
            assertEquals(statsAfterExpire.markDeletePosition, PositionFactory.create(lastMessageId.getLedgerId(),
                    lastMessageId.getEntryId()).toString());
        });
    }

    @Test
    public void testTTLPoliciesUpdate() throws Exception {
        final String namespace = "prop/ns-abc";
        final String topicName = "persistent://" + namespace + "/testTTLPoliciesUpdate";

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName).create();
        PersistentTopic topicRef = (PersistentTopic) pulsar.getBrokerService().getTopicReference(topicName).get();
        assertNotNull(topicRef);

        PersistentTopic topicRefMock = spy(topicRef);

        // Namespace polices must be initiated from admin, which contains `replication_clusters`
        Policies policies = admin.namespaces().getPolicies(namespace);
        policies.message_ttl_in_seconds = 10;
        topicRefMock.onPoliciesUpdate(policies);
        verify(topicRefMock, times(1)).checkMessageExpiry();

        TopicPolicies topicPolicies = new TopicPolicies();
        topicPolicies.setMessageTTLInSeconds(5);
        topicRefMock.onUpdate(topicPolicies);
        verify(topicRefMock, times(2)).checkMessageExpiry();
    }

    @Test
    public void testTtlFilteredByIgnoreSubscriptions() throws Exception {
        String topicName = "persistent://prop/ns-abc/testTTLFilteredByIgnoreSubscriptions";
        String subName = "__SUB_FILTER";
        cleanup();
        Set<String> ignoredSubscriptions = new HashSet<>();
        ignoredSubscriptions.add(subName);
        int defaultTtl = 5;
        conf.setAdditionalSystemCursorNames(ignoredSubscriptions);
        conf.setTtlDurationDefaultInSeconds(defaultTtl);
        super.baseSetup();

        pulsarClient.newConsumer(Schema.STRING).topic(topicName).subscriptionName(subName)
                .subscribe().close();

        @Cleanup
        org.apache.pulsar.client.api.Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .enableBatching(false).topic(topicName).create();

        final int messages = 10;

        for (int i = 0; i < messages; i++) {
            String message = "my-message-" + i;
            producer.send(message);
        }
        producer.close();

        Optional<Topic> topic = pulsar.getBrokerService().getTopicReference(topicName);
        assertTrue(topic.isPresent());
        PersistentSubscription subscription = (PersistentSubscription) topic.get().getSubscription(subName);

        Thread.sleep((defaultTtl - 1) * 1000);
        topic.get().checkMessageExpiry();
        // Wait the message expire task done and make sure the message does not expire early.
        Thread.sleep(1000);
        assertEquals(subscription.getNumberOfEntriesInBacklog(false), 10);
        Thread.sleep(2000);
        topic.get().checkMessageExpiry();
        // Wait the message expire task done.
        retryStrategically((test) -> subscription.getNumberOfEntriesInBacklog(false) == 0, 5, 200);
        // The message should not expire because the subscription is ignored.
        assertEquals(subscription.getNumberOfEntriesInBacklog(false), 10);

        conf.setAdditionalSystemCursorNames(new TreeSet<>());
    }

    @Test
    public void testTtlWithoutIgnoreSubscriptions() throws Exception {
        String topicName = "persistent://prop/ns-abc/testTTLWithoutIgnoreSubscriptions";
        String subName = "__SUB_FILTER";
        cleanup();
        int defaultTtl = 5;
        conf.setTtlDurationDefaultInSeconds(defaultTtl);
        conf.setBrokerDeleteInactiveTopicsEnabled(false);
        super.baseSetup();

        pulsarClient.newConsumer(Schema.STRING).topic(topicName).subscriptionName(subName)
                .subscribe().close();

        @Cleanup
        org.apache.pulsar.client.api.Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .enableBatching(false).topic(topicName).create();

        final int messages = 10;

        for (int i = 0; i < messages; i++) {
            String message = "my-message-" + i;
            producer.send(message);
        }
        producer.close();

        Optional<Topic> topic = pulsar.getBrokerService().getTopicReference(topicName);
        assertTrue(topic.isPresent());
        PersistentSubscription subscription = (PersistentSubscription) topic.get().getSubscription(subName);

        Thread.sleep((defaultTtl - 1) * 1000);
        topic.get().checkMessageExpiry();
        // Wait the message expire task done and make sure the message does not expire early.
        Thread.sleep(1000);
        assertEquals(subscription.getNumberOfEntriesInBacklog(false), 10);
        Thread.sleep(2000);
        topic.get().checkMessageExpiry();
        // Wait the message expire task done and make sure the message expired.
        retryStrategically((test) -> subscription.getNumberOfEntriesInBacklog(false) == 0, 5, 200);
        assertEquals(subscription.getNumberOfEntriesInBacklog(false), 0);
    }

}
