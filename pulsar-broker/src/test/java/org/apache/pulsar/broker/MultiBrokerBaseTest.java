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
package org.apache.pulsar.broker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.broker.testcontext.PulsarTestContext;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.common.util.PortManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

@Slf4j
public abstract class MultiBrokerBaseTest extends MockedPulsarServiceBaseTest {
    protected List<PulsarTestContext> additionalPulsarTestContexts;
    protected List<PulsarService> additionalBrokers;
    protected List<PulsarAdmin> additionalBrokerAdmins;
    protected List<PulsarClient> additionalBrokerClients;

    protected int numberOfAdditionalBrokers() {
        return 2;
    }

    @BeforeClass(alwaysRun = true)
    @Override
    public final void setup() throws Exception {
        super.internalSetup();
        additionalBrokersSetup();
        pulsarResourcesSetup();
        additionalSetup();
    }

    protected void additionalSetup() throws Exception {
        // override this method to add any additional setup logic
    }

    protected void pulsarResourcesSetup() throws PulsarAdminException {
        admin.tenants().createTenant("public", createDefaultTenantInfo());
        admin.namespaces()
                .createNamespace("public/default", getPulsar().getConfiguration().getDefaultNumberOfNamespaceBundles());
    }

    protected void additionalBrokersSetup() throws Exception {
        int numberOfAdditionalBrokers = numberOfAdditionalBrokers();
        additionalBrokers = new ArrayList<>(numberOfAdditionalBrokers);
        additionalBrokerAdmins = new ArrayList<>(numberOfAdditionalBrokers);
        additionalBrokerClients = new ArrayList<>(numberOfAdditionalBrokers);
        additionalPulsarTestContexts = new ArrayList<>(numberOfAdditionalBrokers);
        for (int i = 0; i < numberOfAdditionalBrokers; i++) {
            PulsarTestContext pulsarTestContext = createAdditionalBroker(i);
            additionalPulsarTestContexts.add(i, pulsarTestContext);
            PulsarService pulsarService = pulsarTestContext.getPulsarService();
            additionalBrokers.add(i, pulsarService);
            PulsarAdminBuilder pulsarAdminBuilder =
                    PulsarAdmin.builder().serviceHttpUrl(pulsarService.getWebServiceAddress() != null
                            ? pulsarService.getWebServiceAddress()
                            : pulsarService.getWebServiceAddressTls());
            customizeNewPulsarAdminBuilder(pulsarAdminBuilder);
            additionalBrokerAdmins.add(i, pulsarAdminBuilder.build());
            additionalBrokerClients.add(i, newPulsarClient(pulsarService.getBrokerServiceUrl(), 0));
        }
    }

    protected ServiceConfiguration createConfForAdditionalBroker(int additionalBrokerIndex) {
        return getDefaultConf();
    }

    protected PulsarTestContext createAdditionalBroker(int additionalBrokerIndex) throws Exception {
        return createAdditionalPulsarTestContext(createConfForAdditionalBroker(additionalBrokerIndex));
    }

    @AfterClass(alwaysRun = true)
    @Override
    public final void cleanup() throws Exception {
        additionalBrokersCleanup();
        try {
            additionalCleanup();
        } catch (Exception e) {
            log.warn("Exception during additional cleanup", e);
        }
        super.internalCleanup();
    }

    protected void additionalCleanup() throws Exception {
        // override this method to add any additional cleanup logic
    }

    protected void additionalBrokersCleanup() {
        if (additionalBrokerAdmins != null) {
            for (PulsarAdmin additionalBrokerAdmin : additionalBrokerAdmins) {
                additionalBrokerAdmin.close();
            }
            additionalBrokerAdmins = null;
        }
        if (additionalBrokerClients != null) {
            for (PulsarClient additionalBrokerClient : additionalBrokerClients) {
                try {
                    additionalBrokerClient.shutdown();
                } catch (PulsarClientException e) {
                    // ignore
                }
            }
            additionalBrokerClients = null;
        }
        if (additionalPulsarTestContexts != null) {
            for (PulsarTestContext pulsarTestContext : additionalPulsarTestContexts) {
                PulsarService pulsarService = pulsarTestContext.getPulsarService();
                try {
                    pulsarService.getConfiguration().setBrokerShutdownTimeoutMs(0L);
                    pulsarTestContext.close();
                    pulsarService.getConfiguration().getBrokerServicePort().ifPresent(PortManager::releaseLockedPort);
                    pulsarService.getConfiguration().getWebServicePort().ifPresent(PortManager::releaseLockedPort);
                    pulsarService.getConfiguration().getWebServicePortTls().ifPresent(PortManager::releaseLockedPort);
                } catch (Exception e) {
                    log.warn("Failed to stop additional broker", e);
                }
            }
            additionalBrokers = null;
            additionalPulsarTestContexts = null;
        }
    }

    public final List<PulsarService> getAllBrokers() {
        List<PulsarService> brokers = new ArrayList<>(numberOfAdditionalBrokers() + 1);
        brokers.add(getPulsar());
        brokers.addAll(additionalBrokers);
        return Collections.unmodifiableList(brokers);
    }

    public final List<PulsarAdmin> getAllAdmins() {
        List<PulsarAdmin> admins = new ArrayList<>(numberOfAdditionalBrokers() + 1);
        admins.add(admin);
        admins.addAll(additionalBrokerAdmins);
        return Collections.unmodifiableList(admins);
    }

    public final List<PulsarClient> getAllClients() {
        List<PulsarClient> clients = new ArrayList<>(numberOfAdditionalBrokers() + 1);
        clients.add(pulsarClient);
        clients.addAll(additionalBrokerClients);
        return Collections.unmodifiableList(clients);
    }
}
