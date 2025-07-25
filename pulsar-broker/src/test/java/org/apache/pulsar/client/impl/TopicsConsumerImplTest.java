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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.netty.util.Timeout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Cleanup;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.ManagedCursorImpl;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerImpl;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.persistent.PersistentSubscription;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerEventListener;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.MessageIdAdv;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.MessageRouter;
import org.apache.pulsar.client.api.MessageRoutingMode;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerConsumerBase;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.api.TopicMessageId;
import org.apache.pulsar.client.api.TopicMetadata;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.PartitionedTopicStats;
import org.apache.pulsar.common.policies.data.SubscriptionStats;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@SuppressWarnings({ "unchecked", "rawtypes" })
@Test(groups = "broker-impl")
public class TopicsConsumerImplTest extends ProducerConsumerBase {
    private static final long testTimeout = 90000; // 1.5 min
    private static final Logger log = LoggerFactory.getLogger(TopicsConsumerImplTest.class);
    private final long ackTimeOutMillis = TimeUnit.SECONDS.toMillis(2);

    @Override
    @BeforeMethod
    public void setup() throws Exception {
        super.internalSetup();
        super.producerBaseSetup();
    }

    @Override
    @AfterMethod(alwaysRun = true)
    public void cleanup() throws Exception {
        super.internalCleanup();
    }

    // Verify subscribe topics from different namespace should return error.
    @Test(timeOut = testTimeout)
    public void testDifferentTopicsNameSubscribe() throws Exception {
        String key = "TopicsFromDifferentNamespace";
        final String subscriptionName = "my-ex-subscription-" + key;

        final String topicName1 = "persistent://prop/use/ns-abc1/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc2/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc3/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName2, 2);
        admin.topics().createPartitionedTopic(topicName3, 3);

        // 2. Create consumer
        try {
            Consumer consumer = pulsarClient.newConsumer()
                .topics(topicNames)
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            assertTrue(consumer instanceof MultiTopicsConsumerImpl);
        } catch (IllegalArgumentException e) {
            // expected for have different namespace
        }
    }

    @Test(timeOut = testTimeout)
    public void testRetryClusterTopic() throws Exception {
        String key = "testRetryClusterTopic";
        final String topicName = "persistent://prop/use/ns-abc1/topic-1-" + key;
        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        final String namespace = "prop/ns-abc1";
        admin.tenants().createTenant("prop", tenantInfo);
        admin.namespaces().createNamespace(namespace, Set.of("test"));
        Consumer consumer = pulsarClient.newConsumer()
                .topic(topicName)
                .subscriptionName("my-sub")
                .subscriptionType(SubscriptionType.Shared)
                .enableRetry(true)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
        assertTrue(consumer instanceof MultiTopicsConsumerImpl);
    }

    @Test(timeOut = testTimeout)
    public void testGetConsumersAndGetTopics() throws Exception {
        String key = "TopicsConsumerGet";
        final String subscriptionName = "my-ex-subscription-" + key;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2);

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName2, 2);
        admin.topics().createPartitionedTopic(topicName3, 3);

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .topic(topicName3)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof MultiTopicsConsumerImpl);
        assertTrue(consumer.getTopic().startsWith(MultiTopicsConsumerImpl.DUMMY_TOPIC_NAME_PREFIX));

        List<String> topics = ((MultiTopicsConsumerImpl<byte[]>) consumer).getPartitions();
        List<ConsumerImpl<byte[]>> consumers = ((MultiTopicsConsumerImpl) consumer).getConsumers();

        topics.forEach(topic -> log.info("topic: {}", topic));
        consumers.forEach(c -> log.info("consumer: {}", c.getTopic()));

        IntStream.range(0, 6).forEach(index ->
            assertEquals(consumers.get(index).getTopic(), topics.get(index)));

        assertEquals(((MultiTopicsConsumerImpl<byte[]>) consumer).getPartitionedTopics().size(), 2);

        consumer.unsubscribe();
        consumer.close();
    }

    @Test
    public void testMaxAcknowledgmentGroupSize() throws Exception {
        final String namespace = "use/ns-abc";
        final String topicName = "persistent://" + namespace + "/topic1";
        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("use", tenantInfo);
        admin.namespaces().createNamespace(namespace, Set.of("test"));
        int acknowledgmentGroupSize = 6;

        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topicName)
                .enableBatching(false)
                .messageRoutingMode(MessageRoutingMode.SinglePartition)
                .create();
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName)
                .subscriptionName("my-sub")
                .acknowledgmentGroupTime(10000, TimeUnit.SECONDS)
                .maxAcknowledgmentGroupSize(acknowledgmentGroupSize)
                .subscribe();

        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getOrCreateTopic(topicName).get();
        ManagedLedgerImpl managedLedger = (ManagedLedgerImpl) topic.getManagedLedger();
        ManagedCursorImpl cursor = (ManagedCursorImpl) managedLedger.getCursors().iterator().next();

        for (int i = 0; i < 10; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        MessageIdImpl ackMessageId = new MessageIdImpl(-1, -1, -1);
        for (int i = 0; i < 10; i++) {
            Message<byte[]> msg = consumer.receive(5, TimeUnit.SECONDS);
            if (msg != null) {
                MessageId messageId = msg.getMessageId();
                consumer.acknowledge(msg);
                // When the acknowledgmentGroupSize message is confirmed, send ack will be triggered
                if (i == (acknowledgmentGroupSize - 1)) {
                    ackMessageId = (MessageIdImpl) messageId;
                }
            }
        }

        Awaitility.await().until(() -> cursor.getMarkDeletedPosition().getLedgerId() != -1);
        Position markDeletedPosition = cursor.getMarkDeletedPosition();
        long ledgerId = markDeletedPosition.getLedgerId();
        long entryId = markDeletedPosition.getEntryId();

        assertEquals(ledgerId, ackMessageId.getLedgerId());
        assertEquals(entryId, ackMessageId.getEntryId());

        producer.close();
        consumer.close();
    }

    @Test(timeOut = testTimeout)
    public void testSyncProducerAndConsumer() throws Exception {
        String key = "TopicsConsumerSyncTest";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 30;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName2, 2);
        admin.topics().createPartitionedTopic(topicName3, 3);

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.SinglePartition)
            .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();
        Producer<byte[]> producer3 = pulsarClient.newProducer().topic(topicName3)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof MultiTopicsConsumerImpl);

        // 3. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-" + i).getBytes());
            producer2.send((messagePredicate + "producer2-" + i).getBytes());
            producer3.send((messagePredicate + "producer3-" + i).getBytes());
        }

        int messageSet = 0;
        Message<byte[]> message = consumer.receive();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            messageSet++;
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, totalMessages);

        consumer.unsubscribe();
        consumer.close();
        producer1.close();
        producer2.close();
        producer3.close();
    }

    @Test(timeOut = testTimeout)
    public void testAsyncConsumer() throws Exception {
        String key = "TopicsConsumerAsyncTest";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 30;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName2, 2);
        admin.topics().createPartitionedTopic(topicName3, 3);

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.SinglePartition)
            .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();
        Producer<byte[]> producer3 = pulsarClient.newProducer().topic(topicName3)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof MultiTopicsConsumerImpl);

        // Asynchronously produce messages
        List<Future<MessageId>> futures = new ArrayList<>();
        for (int i = 0; i < totalMessages / 3; i++) {
            futures.add(producer1.sendAsync((messagePredicate + "producer1-" + i).getBytes()));
            futures.add(producer2.sendAsync((messagePredicate + "producer2-" + i).getBytes()));
            futures.add(producer3.sendAsync((messagePredicate + "producer3-" + i).getBytes()));
        }
        log.info("Waiting for async publish to complete : {}", futures.size());
        for (Future<MessageId> future : futures) {
            future.get();
        }

        log.info("start async consume");
        CountDownLatch latch = new CountDownLatch(totalMessages);
        @Cleanup("shutdownNow")
        ExecutorService executor = Executors.newFixedThreadPool(1);
        executor.execute(() -> IntStream.range(0, totalMessages).forEach(index ->
            consumer.receiveAsync()
                .thenAccept(msg -> {
                    assertTrue(msg instanceof TopicMessageImpl);
                    try {
                        consumer.acknowledge(msg);
                    } catch (PulsarClientException e1) {
                        fail("message acknowledge failed", e1);
                    }
                    latch.countDown();
                    log.info("receive index: {}, latch countDown: {}", index, latch.getCount());
                })
                .exceptionally(ex -> {
                    log.warn("receive index: {}, failed receive message {}", index, ex.getMessage());
                    ex.printStackTrace();
                    return null;
                })));

        latch.await();
        log.info("success latch wait");

        consumer.unsubscribe();
        consumer.close();
        producer1.close();
        producer2.close();
        producer3.close();
    }

    @Test(timeOut = testTimeout)
    public void testConsumerUnackedRedelivery() throws Exception {
        String key = "TopicsConsumerRedeliveryTest";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 30;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName2, 2);
        admin.topics().createPartitionedTopic(topicName3, 3);

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.SinglePartition)
            .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();
        Producer<byte[]> producer3 = pulsarClient.newProducer().topic(topicName3)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof MultiTopicsConsumerImpl);

        // 3. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-" + i).getBytes());
            producer2.send((messagePredicate + "producer2-" + i).getBytes());
            producer3.send((messagePredicate + "producer3-" + i).getBytes());
        }

        // 4. Receiver receives the message, not ack, Unacked Message Tracker size should be totalMessages.
        Message<byte[]> message = consumer.receive();
        while (message != null) {
            assertTrue(message instanceof TopicMessageImpl);
            log.debug("Consumer received : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        }
        long size = ((MultiTopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.debug(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, totalMessages);

        // 5. Blocking call, redeliver should kick in, after receive and ack, Unacked Message Tracker size should be 0.
        message = consumer.receive();
        HashSet<String> hSet = new HashSet<>();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            hSet.add(new String(message.getData()));
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);

        size = ((MultiTopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.debug(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, 0);
        assertEquals(hSet.size(), totalMessages);

        // 6. producer publish more messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-round2" + i).getBytes());
            producer2.send((messagePredicate + "producer2-round2" + i).getBytes());
            producer3.send((messagePredicate + "producer3-round2" + i).getBytes());
        }

        // 7. Receiver receives the message, ack them
        message = consumer.receive();
        int received = 0;
        while (message != null) {
            assertTrue(message instanceof TopicMessageImpl);
            received++;
            String data = new String(message.getData());
            log.debug("Consumer received : " + data);
            consumer.acknowledge(message);
            message = consumer.receive(100, TimeUnit.MILLISECONDS);
        }
        size = ((MultiTopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.debug(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, 0);
        assertEquals(received, totalMessages);

        // 8. Simulate ackTimeout
        Thread.sleep(ackTimeOutMillis);

        // 9. producer publish more messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-round3" + i).getBytes());
            producer2.send((messagePredicate + "producer2-round3" + i).getBytes());
            producer3.send((messagePredicate + "producer3-round3" + i).getBytes());
        }

        // 10. Receiver receives the message, doesn't ack
        message = consumer.receive();
        while (message != null) {
            String data = new String(message.getData());
            log.debug("Consumer received : " + data);
            message = consumer.receive(100, TimeUnit.MILLISECONDS);
        }
        size = ((MultiTopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.debug(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, 30);

        Thread.sleep(ackTimeOutMillis);

        // 11. Receiver receives redelivered messages
        message = consumer.receive();
        int redelivered = 0;
        while (message != null) {
            assertTrue(message instanceof TopicMessageImpl);
            redelivered++;
            String data = new String(message.getData());
            log.debug("Consumer received : " + data);
            consumer.acknowledge(message);
            message = consumer.receive(2000, TimeUnit.MILLISECONDS);
        }
        assertEquals(redelivered, 30);
        size =  ((MultiTopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.info(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, 0);

        consumer.unsubscribe();
        consumer.close();
        producer1.close();
        producer2.close();
        producer3.close();
    }

    @Test
    public void testTopicNameValid() throws Exception{
        final String topicName = "persistent://prop/use/ns-abc/testTopicNameValid";
        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName, 3);
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(topicName)
                .subscriptionName("subscriptionName")
                .subscribe();
        ((MultiTopicsConsumerImpl) consumer).subscribeAsync("ns-abc/testTopicNameValid", 5).handle((res, exception) -> {
            assertTrue(exception instanceof PulsarClientException.AlreadyClosedException);
            assertEquals(((PulsarClientException.AlreadyClosedException) exception).getMessage(),
                    "Topic name not valid");
            return null;
        }).get();
        ((MultiTopicsConsumerImpl) consumer).subscribeAsync(topicName, 3).handle((res, exception) -> {
            assertTrue(exception instanceof PulsarClientException.AlreadyClosedException);
            assertEquals(((PulsarClientException.AlreadyClosedException) exception).getMessage(),
                    "Already subscribed to " + topicName);
            return null;
        }).get();
    }

    @Test(timeOut = 30000)
    public void testExclusiveSubscribe() throws Exception {
        final String topicName = "persistent://tenant1/namespace1/testTopicNameValid";
        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("tenant1", tenantInfo);
        admin.namespaces().createNamespace("tenant1/namespace1");
        admin.topics().createPartitionedTopic(topicName, 3);

        Consumer<byte[]> consumer1 = pulsarClient.newConsumer()
                .topic(topicName)
                .subscriptionName("subscriptionName")
                .subscriptionType(SubscriptionType.Exclusive)
                .subscribe();

        try {
            pulsarClient.newConsumer()
                    .topics(IntStream.range(0, 3).mapToObj(i -> topicName + "-partition-" + i)
                    .collect(Collectors.toList()))
                    .subscriptionName("subscriptionName")
                    .subscriptionType(SubscriptionType.Exclusive)
                    .subscribe();
            fail("should fail");
        } catch (PulsarClientException e) {
            String errorLog = e.getMessage();
            assertTrue(errorLog.contains("Exclusive consumer is already connected"));
        }
        consumer1.close();
    }

    @Test
    public void testSubscribeUnsubscribeSingleTopic() throws Exception {
        String key = "TopicsConsumerSubscribeUnsubscribeSingleTopicTest";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 30;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName2, 2);
        admin.topics().createPartitionedTopic(topicName3, 3);

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.SinglePartition)
            .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();
        Producer<byte[]> producer3 = pulsarClient.newProducer().topic(topicName3)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof MultiTopicsConsumerImpl);

        // 3. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-" + i).getBytes());
            producer2.send((messagePredicate + "producer2-" + i).getBytes());
            producer3.send((messagePredicate + "producer3-" + i).getBytes());
        }

        int messageSet = 0;
        Message<byte[]> message = consumer.receive();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            messageSet++;
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, totalMessages);

        // 4, unsubscribe topic3
        CompletableFuture<Void> unsubFuture = ((MultiTopicsConsumerImpl<byte[]>) consumer).unsubscribeAsync(topicName3);
        unsubFuture.get();

        // 5. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-round2" + i).getBytes());
            producer2.send((messagePredicate + "producer2-round2" + i).getBytes());
            producer3.send((messagePredicate + "producer3-round2" + i).getBytes());
        }

        // 6. should not receive messages from topic3, verify get 2/3 of all messages
        messageSet = 0;
        message = consumer.receive();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            messageSet++;
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, totalMessages * 2 / 3);

        // 7. use getter to verify internal topics number after un-subscribe topic3
        List<String> topics = ((MultiTopicsConsumerImpl<byte[]>) consumer).getPartitions();
        List<ConsumerImpl<byte[]>> consumers = ((MultiTopicsConsumerImpl) consumer).getConsumers();

        assertEquals(topics.size(), 3);
        assertEquals(consumers.size(), 3);
        assertEquals(((MultiTopicsConsumerImpl<byte[]>) consumer).getPartitionedTopics().size(), 1);

        // 8. re-subscribe topic3
        CompletableFuture<Void> subFuture =
                ((MultiTopicsConsumerImpl<byte[]>) consumer).subscribeAsync(topicName3, true);
        subFuture.get();

        // 9. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-round3" + i).getBytes());
            producer2.send((messagePredicate + "producer2-round3" + i).getBytes());
            producer3.send((messagePredicate + "producer3-round3" + i).getBytes());
        }

        // 10. should receive messages from all 3 topics
        messageSet = 0;
        message = consumer.receive();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            messageSet++;
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, totalMessages);

        // 11. use getter to verify internal topics number after subscribe topic3
        topics = ((MultiTopicsConsumerImpl<byte[]>) consumer).getPartitions();
        consumers = ((MultiTopicsConsumerImpl) consumer).getConsumers();

        assertEquals(topics.size(), 6);
        assertEquals(consumers.size(), 6);
        assertEquals(((MultiTopicsConsumerImpl<byte[]>) consumer).getPartitionedTopics().size(), 2);

        consumer.unsubscribe();
        consumer.close();
        producer1.close();
        producer2.close();
        producer3.close();
    }

    @Test
    public void testResubscribeSameTopic() throws Exception {
        final String localTopicName = "TopicsConsumerResubscribeSameTopicTest";
        final String localPartitionName = localTopicName + "-partition-0";
        final String topicNameWithNamespace = "public/default/" + localTopicName;
        final String topicNameWithDomain = "persistent://" + topicNameWithNamespace;

        admin.topics().createPartitionedTopic(localTopicName, 2);

        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(localTopicName)
                .subscriptionName("SubscriptionName")
                .subscribe();

        assertTrue(consumer instanceof MultiTopicsConsumerImpl);
        MultiTopicsConsumerImpl<byte[]> multiTopicsConsumer = (MultiTopicsConsumerImpl<byte[]>) consumer;

        multiTopicsConsumer.subscribeAsync(topicNameWithNamespace, false).handle((res, exception) -> {
            assertTrue(exception instanceof PulsarClientException.AlreadyClosedException);
            assertEquals(exception.getMessage(), "Already subscribed to " + topicNameWithNamespace);
            return null;
        }).get();
        multiTopicsConsumer.subscribeAsync(topicNameWithDomain, false).handle((res, exception) -> {
            assertTrue(exception instanceof PulsarClientException.AlreadyClosedException);
            assertEquals(exception.getMessage(), "Already subscribed to " + topicNameWithDomain);
            return null;
        }).get();
        multiTopicsConsumer.subscribeAsync(localPartitionName, false).handle((res, exception) -> {
            assertTrue(exception instanceof PulsarClientException.AlreadyClosedException);
            assertEquals(exception.getMessage(), "Already subscribed to " + localPartitionName);
            return null;
        }).get();

        consumer.unsubscribe();
        consumer.close();
    }


    @Test(timeOut = testTimeout)
    public void testTopicsNameSubscribeWithBuilderFail() throws Exception {
        String key = "TopicsNameSubscribeWithBuilder";
        final String subscriptionName = "my-ex-subscription-" + key;

        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName2, 2);
        admin.topics().createPartitionedTopic(topicName3, 3);

        // test failing builder with empty topics
        try {
            pulsarClient.newConsumer()
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            fail("subscribe1 with no topicName should fail.");
        } catch (PulsarClientException e) {
            // expected
        }

        try {
            pulsarClient.newConsumer()
                .topic()
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            fail("subscribe2 with no topicName should fail.");
        } catch (IllegalArgumentException e) {
            // expected
        }

        try {
            pulsarClient.newConsumer()
                .topics(null)
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            fail("subscribe3 with no topicName should fail.");
        } catch (IllegalArgumentException e) {
            // expected
        }

        try {
            pulsarClient.newConsumer()
                .topics(new ArrayList<>())
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            fail("subscribe4 with no topicName should fail.");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    /**
     * Test Listener for github issue #2547.
     */
    @Test(timeOut = 30000)
    public void testMultiTopicsMessageListener() throws Exception {
        String key = "MultiTopicsMessageListenerTest";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 6;

        // set latch larger than totalMessages, so timeout message get resend
        CountDownLatch latch = new CountDownLatch(totalMessages * 3);

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1);

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName1, 2);

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.SinglePartition)
            .create();

        // 2. Create consumer, set not ack in message listener, so time-out message will resend
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(1000, TimeUnit.MILLISECONDS)
            .receiverQueueSize(100)
            .messageListener((c1, msg) -> {
                assertNotNull(msg, "Message cannot be null");
                String receivedMessage = new String(msg.getData());
                latch.countDown();

                log.info("Received message [{}] in the listener, latch: {}",
                    receivedMessage, latch.getCount());
                // since not acked, it should retry another time
                //c1.acknowledgeAsync(msg);
            })
            .subscribe();
        assertTrue(consumer instanceof MultiTopicsConsumerImpl);

        // 3. producer publish messages
        for (int i = 0; i < totalMessages; i++) {
            producer1.send((messagePredicate + "producer1-" + i).getBytes());
        }

        // verify should not time out, because of message redelivered several times.
        latch.await();

        consumer.close();
    }

    /**
     * Test topic partitions auto subscribed.
     *
     * Steps:
     * 1. Create a consumer with 2 topics, and each topic has 2 partitions: xx-partition-0, xx-partition-1.
     * 2. update topics to have 3 partitions.
     * 3. trigger partitionsAutoUpdate. this should be done automatically, this is to save time to manually trigger.
     * 4. produce message to xx-partition-2 again,  and verify consumer could receive message.
     *
     */
    @Test(timeOut = 30000)
    public void testTopicAutoUpdatePartitions() throws Exception {
        String key = "TestTopicAutoUpdatePartitions";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 6;

        final String topicName1 = "persistent://my-property/my-ns/topic-1-" + key;
        final String topicName2 = "persistent://my-property/my-ns/topic-2-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2);

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName1, 2);
        admin.topics().createPartitionedTopic(topicName2, 2);

        // 1. Create a  consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topics(topicNames)
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .receiverQueueSize(4)
                .autoUpdatePartitions(true)
                .subscribe();
        assertTrue(consumer instanceof MultiTopicsConsumerImpl);

        MultiTopicsConsumerImpl topicsConsumer = (MultiTopicsConsumerImpl) consumer;

        // 2. update to 3 partitions
        admin.topics().updatePartitionedTopic(topicName1, 3);
        admin.topics().updatePartitionedTopic(topicName2, 3);

        // 3. trigger partitionsAutoUpdate. this should be done automatically in 1 minutes,
        // this is to save time to manually trigger.
        log.info("trigger partitionsAutoUpdateTimerTask");
        Timeout timeout = topicsConsumer.getPartitionsAutoUpdateTimeout();
        timeout.task().run(timeout);
        Thread.sleep(200);

        // 4. produce message to xx-partition-2,  and verify consumer could receive message.
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1 + "-partition-2")
                .enableBatching(false)
                .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2 + "-partition-2")
                .enableBatching(false)
                .create();
        for (int i = 0; i < totalMessages; i++) {
            producer1.send((messagePredicate + "topic1-partition-2 index:" + i).getBytes());
            producer2.send((messagePredicate + "topic2-partition-2 index:" + i).getBytes());
            log.info("produce message to partition-2 again. messageindex: {}", i);
        }
        int messageSet = 0;
        Message<byte[]> message = consumer.receive();
        do {
            messageSet++;
            consumer.acknowledge(message);
            log.info("4 Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(200, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, 2 * totalMessages);

        consumer.close();
    }

    @Test(timeOut = testTimeout)
    public void testConsumerDistributionInFailoverSubscriptionWhenUpdatePartitions() throws Exception {
        final String topicName =
                "persistent://my-property/my-ns/testConsumerDistributionInFailoverSubscriptionWhenUpdatePartitions";
        final String subName = "failover-test";
        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName, 2);
        assertEquals(admin.topics().getPartitionedTopicMetadata(topicName).partitions, 2);
        Consumer<String> consumer1 = pulsarClient.newConsumer(Schema.STRING)
                .topic(topicName)
                .subscriptionType(SubscriptionType.Failover)
                .subscriptionName(subName)
                .subscribe();
        assertTrue(consumer1 instanceof MultiTopicsConsumerImpl);

        assertEquals(((MultiTopicsConsumerImpl) consumer1).allTopicPartitionsNumber.get(), 2);

        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topicName)
                .messageRouter(new MessageRouter() {
                    @Override
                    public int choosePartition(Message<?> msg, TopicMetadata metadata) {
                        return Integer.parseInt(msg.getKey()) % metadata.numPartitions();
                    }
                })
                .create();

        final int messages = 20;
        for (int i = 0; i < messages; i++) {
            producer.newMessage().key(String.valueOf(i)).value("message - " + i).send();
        }

        int received = 0;
        Message lastMessage = null;
        for (int i = 0; i < messages; i++) {
            lastMessage = consumer1.receive();
            received++;
        }
        assertEquals(received, messages);
        consumer1.acknowledgeCumulative(lastMessage);

        // 1.Update partition and check message consumption
        admin.topics().updatePartitionedTopic(topicName, 4);
        log.info("trigger partitionsAutoUpdateTimerTask");
        Timeout timeout = ((MultiTopicsConsumerImpl) consumer1).getPartitionsAutoUpdateTimeout();
        timeout.task().run(timeout);
        Thread.sleep(200);

        assertEquals(((MultiTopicsConsumerImpl) consumer1).allTopicPartitionsNumber.get(), 4);
        for (int i = 0; i < messages; i++) {
            producer.newMessage().key(String.valueOf(i)).value("message - " + i).send();
        }

        received = 0;
        lastMessage = null;
        for (int i = 0; i < messages; i++) {
            lastMessage = consumer1.receive();
            received++;
        }
        assertEquals(received, messages);
        consumer1.acknowledgeCumulative(lastMessage);

        // 2.Create a new consumer and check active consumer changed
        Consumer<String> consumer2 = pulsarClient.newConsumer(Schema.STRING)
                .topic(topicName)
                .subscriptionType(SubscriptionType.Failover)
                .subscriptionName(subName)
                .subscribe();
        assertTrue(consumer2 instanceof MultiTopicsConsumerImpl);
        assertEquals(((MultiTopicsConsumerImpl) consumer1).allTopicPartitionsNumber.get(), 4);

        for (int i = 0; i < messages; i++) {
            producer.newMessage().key(String.valueOf(i)).value("message - " + i).send();
        }

        Map<String, AtomicInteger> activeConsumers = new HashMap<>();
        PartitionedTopicStats stats = admin.topics().getPartitionedStats(topicName, true);
        for (TopicStats value : stats.getPartitions().values()) {
            for (SubscriptionStats subscriptionStats : value.getSubscriptions().values()) {
                assertTrue(subscriptionStats.getActiveConsumerName().equals(consumer1.getConsumerName())
                        || subscriptionStats.getActiveConsumerName().equals(consumer2.getConsumerName()));
                activeConsumers.putIfAbsent(subscriptionStats.getActiveConsumerName(), new AtomicInteger(0));
                activeConsumers.get(subscriptionStats.getActiveConsumerName()).incrementAndGet();
            }
        }
        assertEquals(activeConsumers.get(consumer1.getConsumerName()).get(), 2);
        assertEquals(activeConsumers.get(consumer2.getConsumerName()).get(), 2);

        // 4.Check new consumer can receive half of total messages
        received = 0;
        lastMessage = null;
        for (int i = 0; i < messages / 2; i++) {
            lastMessage = consumer1.receive();
            received++;
        }
        assertEquals(received, messages / 2);
        consumer1.acknowledgeCumulative(lastMessage);

        received = 0;
        lastMessage = null;
        for (int i = 0; i < messages / 2; i++) {
            lastMessage = consumer2.receive();
            received++;
        }
        assertEquals(received, messages / 2);
        consumer2.acknowledgeCumulative(lastMessage);
    }

    @Test(timeOut = testTimeout)
    public void testDefaultBacklogTTL() throws Exception {

        int defaultTTLSec = 5;
        int totalMessages = 10;
        this.conf.setTtlDurationDefaultInSeconds(defaultTTLSec);

        final String namespace = "prop/use/expiry";
        final String topicName = "persistent://" + namespace + "/expiry";
        final String subName = "expiredSub";

        admin.clusters().createCluster("use", ClusterData.builder().serviceUrl(brokerUrl.toString()).build());

        admin.tenants().createTenant("prop", new TenantInfoImpl(null, Sets.newHashSet("use")));
        admin.namespaces().createNamespace(namespace);

        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName).subscriptionName(subName)
                .subscriptionType(SubscriptionType.Shared).ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
        consumer.close();

        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName).enableBatching(false).create();
        for (int i = 0; i < totalMessages; i++) {
            producer.send(("" + i).getBytes());
        }

        Optional<Topic> topic = pulsar.getBrokerService().getTopic(topicName, false).get();
        assertTrue(topic.isPresent());
        PersistentSubscription subscription = (PersistentSubscription) topic.get().getSubscription(subName);

        Thread.sleep((defaultTTLSec - 1) * 1000);
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

    @Test(timeOut = testTimeout)
    public void testGetLastMessageId() throws Exception {
        String key = "TopicGetLastMessageId";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 30;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        TenantInfoImpl tenantInfo = createDefaultTenantInfo();
        admin.tenants().createTenant("prop", tenantInfo);
        admin.topics().createPartitionedTopic(topicName2, 2);
        admin.topics().createPartitionedTopic(topicName3, 3);

        final Set<String> topics = new HashSet<>();
        topics.add(topicName1);
        IntStream.range(0, 2).forEach(i -> topics.add(topicName2 + TopicName.PARTITIONED_TOPIC_SUFFIX + i));
        IntStream.range(0, 3).forEach(i -> topics.add(topicName3 + TopicName.PARTITIONED_TOPIC_SUFFIX + i));

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .enableBatching(false)
            .messageRoutingMode(MessageRoutingMode.SinglePartition)
            .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();
        Producer<byte[]> producer3 = pulsarClient.newProducer().topic(topicName3)
            .enableBatching(false)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof MultiTopicsConsumerImpl);

        // 3. producer publish messages
        for (int i = 0; i < totalMessages; i++) {
            producer1.send((messagePredicate + "producer1-" + i).getBytes());
            producer2.send((messagePredicate + "producer2-" + i).getBytes());
            producer3.send((messagePredicate + "producer3-" + i).getBytes());
        }

        MessageId messageId = consumer.getLastMessageId();
        assertTrue(messageId instanceof MultiMessageIdImpl);
        MultiMessageIdImpl multiMessageId = (MultiMessageIdImpl) messageId;
        Map<String, MessageId> map = multiMessageId.getMap();
        assertEquals(map.size(), 6);
        map.forEach((k, v) -> {
            log.info("topic: {}, messageId:{} ", k, v.toString());
            assertTrue(v instanceof MessageIdImpl);
            MessageIdImpl messageId1 = (MessageIdImpl) v;
            if (k.contains(topicName1)) {
                assertEquals(messageId1.entryId,  totalMessages  - 1);
            } else if (k.contains(topicName2)) {
                assertEquals(messageId1.entryId,  totalMessages / 2  - 1);
            } else {
                assertEquals(messageId1.entryId,  totalMessages / 3  - 1);
            }
        });

        List<TopicMessageId> msgIds = consumer.getLastMessageIds();
        assertEquals(msgIds.size(), 6);
        assertEquals(msgIds.stream().map(TopicMessageId::getOwnerTopic).collect(Collectors.toSet()), topics);
        for (TopicMessageId msgId : msgIds) {
            int numMessages = (int) ((MessageIdAdv) msgId).getEntryId() + 1;
            if (msgId.getOwnerTopic().equals(topicName1)) {
                assertEquals(numMessages, totalMessages);
            } else if (msgId.getOwnerTopic().startsWith(topicName2)) {
                assertEquals(numMessages, totalMessages / 2);
            } else {
                assertEquals(numMessages, totalMessages / 3);
            }
        }

        for (int i = 0; i < totalMessages; i++) {
            producer1.send((messagePredicate + "producer1-" + i).getBytes());
            producer2.send((messagePredicate + "producer2-" + i).getBytes());
            producer3.send((messagePredicate + "producer3-" + i).getBytes());
        }


        messageId = consumer.getLastMessageId();
        assertTrue(messageId instanceof MultiMessageIdImpl);
        MultiMessageIdImpl multiMessageId2 = (MultiMessageIdImpl) messageId;
        Map<String, MessageId> map2 = multiMessageId2.getMap();
        assertEquals(map2.size(), 6);
        map2.forEach((k, v) -> {
            log.info("topic: {}, messageId:{} ", k, v.toString());
            assertTrue(v instanceof MessageIdImpl);
            MessageIdImpl messageId1 = (MessageIdImpl) v;
            if (k.contains(topicName1)) {
                assertEquals(messageId1.entryId,  totalMessages * 2  - 1);
            } else if (k.contains(topicName2)) {
                assertEquals(messageId1.entryId,  totalMessages - 1);
            } else {
                assertEquals(messageId1.entryId,  totalMessages * 2 / 3  - 1);
            }
        });

        msgIds = consumer.getLastMessageIds();
        assertEquals(msgIds.size(), 6);
        assertEquals(msgIds.stream().map(TopicMessageId::getOwnerTopic).collect(Collectors.toSet()), topics);
        for (TopicMessageId msgId : msgIds) {
            int numMessages = (int) ((MessageIdAdv) msgId).getEntryId() + 1;
            if (msgId.getOwnerTopic().equals(topicName1)) {
                assertEquals(numMessages, totalMessages * 2);
            } else if (msgId.getOwnerTopic().startsWith(topicName2)) {
                assertEquals(numMessages, totalMessages);
            } else {
                assertEquals(numMessages, totalMessages / 3 * 2);
            }
        }

        consumer.unsubscribe();
        consumer.close();
        producer1.close();
        producer2.close();
        producer3.close();
    }

    @Test(timeOut = testTimeout)
    public void multiTopicsInDifferentNameSpace() throws PulsarAdminException, PulsarClientException {
        List<String> topics = new ArrayList<>();
        topics.add("persistent://prop/use/ns-abc/topic-1");
        topics.add("persistent://prop/use/ns-abc/topic-2");
        topics.add("persistent://prop/use/ns-abc1/topic-3");
        admin.clusters().createCluster("use", ClusterData.builder().serviceUrl(brokerUrl.toString()).build());
        admin.tenants().createTenant("prop", new TenantInfoImpl(null, Sets.newHashSet("use")));
        admin.namespaces().createNamespace("prop/use/ns-abc");
        admin.namespaces().createNamespace("prop/use/ns-abc1");
        Consumer consumer = pulsarClient.newConsumer()
                .topics(topics)
                .subscriptionName("multiTopicSubscription")
                .subscriptionType(SubscriptionType.Exclusive)
                .subscribe();
        // create Producer
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic("persistent://prop/use/ns-abc/topic-1")
                .producerName("producer")
                .create();
        Producer<String> producer1 = pulsarClient.newProducer(Schema.STRING)
                .topic("persistent://prop/use/ns-abc/topic-2")
                .producerName("producer1")
                .create();
        Producer<String> producer2 = pulsarClient.newProducer(Schema.STRING)
                .topic("persistent://prop/use/ns-abc1/topic-3")
                .producerName("producer2")
                .create();
        //send message
        producer.send("ns-abc/topic-1-Message1");

        producer1.send("ns-abc/topic-2-Message1");

        producer2.send("ns-abc1/topic-3-Message1");

        int messageSet = 0;
        Message<byte[]> message = consumer.receive();
        do {
            messageSet++;
            consumer.acknowledge(message);
            log.info("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(200, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, 3);

        consumer.unsubscribe();
        consumer.close();
        producer.close();
        producer1.close();
        producer2.close();
    }

    @Test(timeOut = testTimeout)
    public void testSubscriptionMustCompleteWhenOperationTimeoutOnMultipleTopics() throws PulsarClientException {
        @Cleanup
        PulsarClient client = PulsarClient.builder()
                .serviceUrl(lookupUrl.toString())
                .ioThreads(2)
                .listenerThreads(3)
                // Below line: Set this very small so the operation timeout can be triggered
                .operationTimeout(2, TimeUnit.MILLISECONDS)
                .build();

        String topic0 = "public/default/topic0";
        String topic1 = "public/default/topic1";

        for (int i = 0; i < 10; i++) {
            try {
                client.newConsumer(Schema.STRING)
                        .subscriptionName("subName")
                        .topics(Lists.newArrayList(topic0, topic1))
                        .receiverQueueSize(2)
                        .subscriptionType(SubscriptionType.Shared)
                        .ackTimeout(365, TimeUnit.DAYS)
                        .ackTimeoutTickTime(36, TimeUnit.DAYS)
                        .acknowledgmentGroupTime(0, TimeUnit.MILLISECONDS)
                        .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                        .subscribe();
                Thread.sleep(3000);
            } catch (Exception ex) {
                Assert.assertTrue(ex instanceof PulsarClientException.TimeoutException);
            }
        }
    }

    @Test(timeOut = testTimeout)
    public void testPartitionsUpdatesForMultipleTopics() throws Exception {
        final String topicName0 = "persistent://public/default/testPartitionsUpdatesForMultipleTopics-0";
        final String subName = "my-sub";
        admin.topics().createPartitionedTopic(topicName0, 2);
        assertEquals(admin.topics().getPartitionedTopicMetadata(topicName0).partitions, 2);

        PatternMultiTopicsConsumerImpl<String> consumer =
                (PatternMultiTopicsConsumerImpl<String>) pulsarClient.newConsumer(Schema.STRING)
                .topicsPattern("persistent://public/default/test.*")
                .subscriptionType(SubscriptionType.Failover)
                .subscriptionName(subName)
                .subscribe();

        Assert.assertEquals(consumer.getPartitionsOfTheTopicMap(), 2);
        Assert.assertEquals(consumer.allTopicPartitionsNumber.intValue(), 2);

        admin.topics().updatePartitionedTopic(topicName0, 5);

        Awaitility.await().untilAsserted(() -> {
            Assert.assertEquals(consumer.getPartitionsOfTheTopicMap(), 5);
            Assert.assertEquals(consumer.allTopicPartitionsNumber.intValue(), 5);
        });

        final String topicName1 = "persistent://public/default/testPartitionsUpdatesForMultipleTopics-1";
        admin.topics().createPartitionedTopic(topicName1, 3);
        assertEquals(admin.topics().getPartitionedTopicMetadata(topicName1).partitions, 3);

        Awaitility.await().untilAsserted(() -> {
            Assert.assertEquals(consumer.getPartitionsOfTheTopicMap(), 8);
            Assert.assertEquals(consumer.allTopicPartitionsNumber.intValue(), 8);
        });

        admin.topics().updatePartitionedTopic(topicName1, 5);

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Assert.assertEquals(consumer.getPartitionsOfTheTopicMap(), 10);
            Assert.assertEquals(consumer.allTopicPartitionsNumber.intValue(), 10);
        });
    }

    @Test
    public void testTopicsDistribution() throws Exception {
        final String topic = "topics-distribution";
        final int topicCount = 100;
        final int consumers = 10;

        for (int i = 0; i < topicCount; i++) {
            admin.topics().createNonPartitionedTopic(topic + "-" + i);
        }

        CustomizedConsumerEventListener eventListener = new CustomizedConsumerEventListener();

        List<Consumer<?>> consumerList = new ArrayList<>(consumers);
        for (int i = 0; i < consumers; i++) {
            consumerList.add(pulsarClient.newConsumer()
                    .topics(IntStream.range(0, topicCount).mapToObj(j -> topic + "-" + j).toList())
                    .subscriptionType(SubscriptionType.Failover)
                    .subscriptionName("my-sub")
                    .consumerName("consumer-" + i)
                    .consumerEventListener(eventListener)
                    .subscribe());
        }

        log.info("Topics are distributed to consumers as {}", eventListener.getActiveConsumers());
        Map<String, Integer> assigned = new HashMap<>();
        eventListener.getActiveConsumers().forEach((k, v) -> assigned.compute(v, (t, c) -> c == null ? 1 : ++c));
        assertEquals(assigned.size(), consumers);
        for (Consumer<?> consumer : consumerList) {
            consumer.close();
        }
    }

    @Test
    public void testPartitionedTopicDistribution() throws Exception {
        this.conf.setActiveConsumerFailoverConsistentHashing(true);
        final String topic = "partitioned-topics-distribution";
        final int topicCount = 100;
        final int consumers = 10;

        for (int i = 0; i < topicCount; i++) {
            admin.topics().createPartitionedTopic(topic + "-" + i, 1);
        }

        CustomizedConsumerEventListener eventListener = new CustomizedConsumerEventListener();

        List<Consumer<?>> consumerList = new ArrayList<>(consumers);
        for (int i = 0; i < consumers; i++) {
            consumerList.add(pulsarClient.newConsumer()
                    .topics(IntStream.range(0, topicCount).mapToObj(j -> topic + "-" + j).toList())
                    .subscriptionType(SubscriptionType.Failover)
                    .subscriptionName("my-sub")
                    .consumerName("consumer-" + i)
                    .consumerEventListener(eventListener)
                    .subscribe());
        }

        log.info("Topics are distributed to consumers as {}", eventListener.getActiveConsumers());
        Map<String, Integer> assigned = new HashMap<>();
        eventListener.getActiveConsumers().forEach((k, v) -> assigned.compute(v, (t, c) -> c == null ? 1 : ++c));
        assertEquals(assigned.size(), consumers);
        for (Consumer<?> consumer : consumerList) {
            consumer.close();
        }
    }

    private static class CustomizedConsumerEventListener implements ConsumerEventListener {

        private final Map<String, String> activeConsumers = new HashMap<>();

        @Override
        public void becameActive(Consumer<?> consumer, int partitionId) {
            activeConsumers.put(consumer.getTopic(), consumer.getConsumerName());
        }

        @Override
        public void becameInactive(Consumer<?> consumer, int partitionId) {
            //no-op
        }

        public Map<String, String> getActiveConsumers() {
            return activeConsumers;
        }
    }

    @DataProvider
    public static Object[][] seekByFunction() {
        return new Object[][] {
                { true }, { false }
        };
    }

    @Test(timeOut = 30000, dataProvider = "seekByFunction")
    public void testSeekToNewerPosition(boolean seekByFunction) throws Exception {
        final var topic1 = TopicName.get(newTopicName()).toString()
                .replace("my-property", "public").replace("my-ns", "default");
        final var topic2 = TopicName.get(newTopicName()).toString()
                .replace("my-property", "public").replace("my-ns", "default");
        @Cleanup final var producer1 = pulsarClient.newProducer(Schema.STRING).topic(topic1).create();
        @Cleanup final var producer2 = pulsarClient.newProducer(Schema.STRING).topic(topic2).create();
        producer1.send("1-0");
        producer2.send("2-0");
        producer1.send("1-1");
        producer2.send("2-1");
        final var consumer1 = pulsarClient.newConsumer(Schema.STRING)
                .topics(Arrays.asList(topic1, topic2)).subscriptionName("sub")
                .ackTimeout(1, TimeUnit.SECONDS)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest).subscribe();
        final var timestamps = new ArrayList<Long>();
        for (int i = 0; i < 4; i++) {
            timestamps.add(consumer1.receive().getPublishTime());
        }
        timestamps.sort(Comparator.naturalOrder());
        final var timestamp = timestamps.get(2);
        consumer1.close();

        final Function<Consumer<String>, CompletableFuture<Void>> seekAsync = consumer -> {
            final var future = seekByFunction ? consumer.seekAsync(__ -> timestamp) : consumer.seekAsync(timestamp);
            assertEquals(((ConsumerBase<String>) consumer).getIncomingMessageSize(), 0L);
            assertEquals(((ConsumerBase<String>) consumer).getTotalIncomingMessages(), 0);
            assertTrue(((ConsumerBase<String>) consumer).getUnAckedMessageTracker().isEmpty());
            return future;
        };

        @Cleanup final var consumer2 = pulsarClient.newConsumer(Schema.STRING)
                .topics(Arrays.asList(topic1, topic2)).subscriptionName("sub-2")
                .ackTimeout(1, TimeUnit.SECONDS)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest).subscribe();
        seekAsync.apply(consumer2).get();
        final var values = new TreeSet<String>();
        for (int i = 0; i < 2; i++) {
            values.add(consumer2.receive().getValue());
        }
        assertEquals(values, new TreeSet<>(Arrays.asList("1-1", "2-1")));

        final var valuesInListener = new CopyOnWriteArrayList<String>();
        @Cleanup final var consumer3 = pulsarClient.newConsumer(Schema.STRING)
                .topics(Arrays.asList(topic1, topic2)).subscriptionName("sub-3")
                .messageListener((MessageListener<String>) (__, msg) -> valuesInListener.add(msg.getValue()))
                .ackTimeout(1, TimeUnit.SECONDS)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest).subscribe();
        seekAsync.apply(consumer3).get();
        if (valuesInListener.isEmpty()) {
            Awaitility.await().untilAsserted(() -> assertEquals(valuesInListener.size(), 2));
            assertEquals(valuesInListener.stream().sorted().toList(), Arrays.asList("1-1", "2-1"));
        } // else: consumer3 has passed messages to the listener before seek, in this case we cannot assume anything

        @Cleanup final var consumer4 = pulsarClient.newConsumer(Schema.STRING)
                .topics(Arrays.asList(topic1, topic2)).subscriptionName("sub-4")
                .ackTimeout(1, TimeUnit.SECONDS)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest).subscribe();
        seekAsync.apply(consumer4).get();
        final var valuesInReceiveAsync = new ArrayList<String>();
        valuesInReceiveAsync.add(consumer4.receiveAsync().get().getValue());
        valuesInReceiveAsync.add(consumer4.receiveAsync().get().getValue());
        assertEquals(valuesInReceiveAsync.stream().sorted().toList(), Arrays.asList("1-1", "2-1"));
    }
}
