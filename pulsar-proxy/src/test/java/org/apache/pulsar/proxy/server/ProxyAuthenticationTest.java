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
package org.apache.pulsar.proxy.server;

import static org.mockito.Mockito.spy;
import com.google.common.collect.Sets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.naming.AuthenticationException;
import lombok.Cleanup;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.authentication.AuthenticationProvider;
import org.apache.pulsar.broker.authentication.AuthenticationService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationDataProvider;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.api.ProducerConsumerBase;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.common.configuration.PulsarConfigurationLoader;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ProxyAuthenticationTest extends ProducerConsumerBase {
    private static final Logger log = LoggerFactory.getLogger(ProxyAuthenticationTest.class);
    private static final String CLUSTER_NAME = "test";

    public static class BasicAuthenticationData implements AuthenticationDataProvider {
        private final String authParam;

        public BasicAuthenticationData(String authParam) {
            this.authParam = authParam;
        }

        public boolean hasDataFromCommand() {
            return true;
        }

        public String getCommandData() {
            return authParam;
        }

        public boolean hasDataForHttp() {
            return true;
        }

        @Override
        public Set<Entry<String, String>> getHttpHeaders() {
            Map<String, String> headers = new HashMap<>();
            headers.put("BasicAuthentication", authParam);
            return headers.entrySet();
        }
    }

    public static class BasicAuthentication implements Authentication {

        private String authParam;

        @Override
        public void close() throws IOException {
            // noop
        }

        @Override
        public String getAuthMethodName() {
            return "BasicAuthentication";
        }

        @Override
        public AuthenticationDataProvider getAuthData() throws PulsarClientException {
            try {
                return new BasicAuthenticationData(authParam);
            } catch (Exception e) {
                throw new PulsarClientException(e);
            }
        }

        @Override
        public void configure(Map<String, String> authParams) {
            this.authParam = String.format("{\"entityType\": \"%s\", \"expiryTime\": \"%s\"}",
                    authParams.get("entityType"), authParams.get("expiryTime"));
        }

        @Override
        public void start() throws PulsarClientException {
            // noop
        }
    }

    public static class BasicAuthenticationProvider implements AuthenticationProvider {

        @Override
        public void close() throws IOException {
        }

        @Override
        public void initialize(ServiceConfiguration config) throws IOException {
        }

        @Override
        public String getAuthMethodName() {
            return "BasicAuthentication";
        }

        @Override
        public CompletableFuture<String> authenticateAsync(AuthenticationDataSource authData) {
            String commandData = null;
            if (authData.hasDataFromCommand()) {
                commandData = authData.getCommandData();
            } else if (authData.hasDataFromHttp()) {
                commandData = authData.getHttpHeader("BasicAuthentication");
            }

            JsonObject element = JsonParser.parseString(commandData).getAsJsonObject();
            log.info("Have log of {}", element);
            long expiryTimeInMillis = Long.parseLong(element.get("expiryTime").getAsString());
            long currentTimeInMillis = System.currentTimeMillis();
            if (expiryTimeInMillis < currentTimeInMillis) {
                log.warn("Auth failed due to timeout");
                return CompletableFuture
                        .failedFuture(new AuthenticationException("Authentication data has been expired"));
            }
            final String result = element.get("entityType").getAsString();
            // Run in another thread to attempt to test the async logic
            return CompletableFuture.supplyAsync(() -> result);
        }
    }

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        conf.setAuthenticationEnabled(true);
        conf.setAuthorizationEnabled(true);
        conf.setBrokerClientAuthenticationPlugin(BasicAuthentication.class.getName());
        // Expires after an hour
        conf.setBrokerClientAuthenticationParameters(
                "entityType:admin,expiryTime:" + (System.currentTimeMillis() + 3600 * 1000));

        Set<String> superUserRoles = new HashSet<>();
        superUserRoles.add("admin");
        conf.setSuperUserRoles(superUserRoles);

        Set<String> providers = new HashSet<>();
        providers.add(BasicAuthenticationProvider.class.getName());
        conf.setAuthenticationProviders(providers);

        conf.setClusterName(CLUSTER_NAME);
        Set<String> proxyRoles = new HashSet<>();
        proxyRoles.add("proxy");
        conf.setProxyRoles(proxyRoles);
        conf.setAuthenticateOriginalAuthData(true);
        super.init();

        updateAdminClient();
        producerBaseSetup();
    }

    @Override
    @AfterMethod(alwaysRun = true)
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    void testAuthentication() throws Exception {
        log.info("-- Starting {} test --", methodName);

        // Step 1: Create Admin Client
        updateAdminClient();
        // create a client which connects to proxy and pass authData
        String namespaceName = "my-property/my-ns";
        String topicName = "persistent://my-property/my-ns/my-topic1";
        String subscriptionName = "my-subscriber-name";
        // expires after 60 seconds
        String clientAuthParams = "entityType:client,expiryTime:" + (System.currentTimeMillis() + 60 * 1000);
        // expires after 60 seconds
        String proxyAuthParams = "entityType:proxy,expiryTime:" + (System.currentTimeMillis() + 60 * 1000);

        admin.namespaces().grantPermissionOnNamespace(namespaceName, "proxy",
                Sets.newHashSet(AuthAction.consume, AuthAction.produce));
        admin.namespaces().grantPermissionOnNamespace(namespaceName, "client",
                Sets.newHashSet(AuthAction.consume, AuthAction.produce));

        // Step 2: Try to use proxy Client as a normal Client - expect exception
        ProxyConfiguration proxyConfig = new ProxyConfiguration();
        proxyConfig.setAuthenticationEnabled(true);
        proxyConfig.setServicePort(Optional.of(0));
        proxyConfig.setBrokerProxyAllowedTargetPorts("*");
        proxyConfig.setWebServicePort(Optional.of(0));
        proxyConfig.setBrokerServiceURL(pulsar.getBrokerServiceUrl());
        proxyConfig.setClusterName(CLUSTER_NAME);

        proxyConfig.setBrokerClientAuthenticationPlugin(BasicAuthentication.class.getName());
        proxyConfig.setBrokerClientAuthenticationParameters(proxyAuthParams);

        Set<String> providers = new HashSet<>();
        providers.add(BasicAuthenticationProvider.class.getName());
        proxyConfig.setAuthenticationProviders(providers);
        proxyConfig.setForwardAuthorizationCredentials(true);
                AuthenticationService authenticationService = new AuthenticationService(
                        PulsarConfigurationLoader.convertFrom(proxyConfig));
        @Cleanup
        final Authentication proxyClientAuthentication =
                AuthenticationFactory.create(proxyConfig.getBrokerClientAuthenticationPlugin(),
                proxyConfig.getBrokerClientAuthenticationParameters());
        proxyClientAuthentication.start();
        @Cleanup
        ProxyService proxyService = new ProxyService(proxyConfig, authenticationService, proxyClientAuthentication);

        proxyService.start();
        final String proxyServiceUrl = proxyService.getServiceUrl();

        // Step 3: Pass correct client params and use multiple connections
        @Cleanup
        PulsarClient proxyClient = createPulsarClient(proxyServiceUrl, clientAuthParams, 3);
        proxyClient.newProducer(Schema.BYTES).topic(topicName).create();
        proxyClient.newProducer(Schema.BYTES).topic(topicName).create();
        proxyClient.newProducer(Schema.BYTES).topic(topicName).create();

        // Step 4: Ensure that all client contexts share the same auth provider
        Assert.assertTrue(proxyService.getClientCnxs().size() >= 3, "expect at least 3 clients");
        proxyService.getClientCnxs().stream().forEach((cnx) -> {
            Assert.assertSame(cnx.authenticationProvider,
                    proxyService.getAuthenticationService().getAuthenticationProvider("BasicAuthentication"));
        });
    }

    private void updateAdminClient() throws PulsarClientException {
        // Expires after an hour
        String adminAuthParams = "entityType:admin,expiryTime:" + (System.currentTimeMillis() + 3600 * 1000);
        closeAdmin();
        admin = spy(PulsarAdmin.builder().serviceHttpUrl(brokerUrl.toString())
                .authentication(BasicAuthentication.class.getName(), adminAuthParams).build());
    }

    private PulsarClient createPulsarClient(String proxyServiceUrl, String authParams, int numberOfConnections)
            throws PulsarClientException {
        return PulsarClient.builder().serviceUrl(proxyServiceUrl)
                .authentication(BasicAuthentication.class.getName(), authParams)
                .connectionsPerBroker(numberOfConnections).build();
    }
}
