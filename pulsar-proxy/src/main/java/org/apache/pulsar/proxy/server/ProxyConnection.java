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

import static com.google.common.base.Preconditions.checkArgument;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.ssl.SslHandler;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.naming.AuthenticationException;
import javax.net.ssl.SSLSession;
import lombok.Getter;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.authentication.AuthenticationProvider;
import org.apache.pulsar.broker.authentication.AuthenticationState;
import org.apache.pulsar.broker.limiter.ConnectionController;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.ClientCnx;
import org.apache.pulsar.client.impl.ConnectionPool;
import org.apache.pulsar.client.impl.PulsarChannelInitializer;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.client.impl.conf.ConfigurationDataUtils;
import org.apache.pulsar.client.impl.metrics.InstrumentProvider;
import org.apache.pulsar.client.internal.PropertiesUtils;
import org.apache.pulsar.common.api.AuthData;
import org.apache.pulsar.common.api.proto.CommandAuthResponse;
import org.apache.pulsar.common.api.proto.CommandConnect;
import org.apache.pulsar.common.api.proto.CommandConnected;
import org.apache.pulsar.common.api.proto.CommandGetSchema;
import org.apache.pulsar.common.api.proto.CommandGetTopicsOfNamespace;
import org.apache.pulsar.common.api.proto.CommandLookupTopic;
import org.apache.pulsar.common.api.proto.CommandPartitionedTopicMetadata;
import org.apache.pulsar.common.api.proto.FeatureFlags;
import org.apache.pulsar.common.api.proto.ProtocolVersion;
import org.apache.pulsar.common.api.proto.ServerError;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.protocol.PulsarHandler;
import org.apache.pulsar.common.util.Runnables;
import org.apache.pulsar.common.util.netty.NettyChannelUtil;
import org.apache.pulsar.policies.data.loadbalancer.ServiceLookupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles incoming discovery request from client and sends appropriate response back to client.
 * <p>
 * Please see {@link org.apache.pulsar.common.protocol.PulsarDecoder} javadoc for important details about handle* method
 * parameter instance lifecycle.
 */
public class ProxyConnection extends PulsarHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyConnection.class);
    // ConnectionPool is used by the proxy to issue lookup requests. It is null when doing direct broker proxying.
    private ConnectionPool connectionPool;
    private final AtomicLong requestIdGenerator =
            new AtomicLong(ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE / 2));
    private final ProxyService service;
    private final DnsAddressResolverGroup dnsAddressResolverGroup;
    private State state;

    private LookupProxyHandler lookupProxyHandler = null;
    @Getter
    private DirectProxyHandler directProxyHandler = null;
    private ScheduledFuture<?> authRefreshTask;
    // When authChallengeSentTime is not Long.MAX_VALUE, it means the proxy is waiting for the client to respond
    // to an auth challenge. When authChallengeSentTime is Long.MAX_VALUE, there are no pending auth challenges.
    private long authChallengeSentTime = Long.MAX_VALUE;
    private FeatureFlags features;
    private Set<CompletableFuture<AuthData>> pendingBrokerAuthChallenges = null;
    private final BrokerProxyValidator brokerProxyValidator;
    private final ConnectionController connectionController;
    String clientAuthRole;
    volatile AuthData clientAuthData;
    String clientAuthMethod;
    String clientVersion;

    private String authMethod = "none";
    AuthenticationProvider authenticationProvider;
    AuthenticationState authState;
    private ClientConfigurationData clientConf;
    private boolean hasProxyToBrokerUrl;
    private int protocolVersionToAdvertise;
    private String proxyToBrokerUrl;
    private HAProxyMessage haProxyMessage;

    protected static final Integer SPLICE_BYTES = 1024 * 1024 * 1024;
    private static final byte[] EMPTY_CREDENTIALS = new byte[0];

    boolean isTlsInboundChannel = false;

    enum State {
        Init,

        // Connecting between user client and proxy server.
        // Mutual authn needs verify between client and proxy server several times.
        Connecting,

        // Proxy the lookup requests to a random broker
        // Follow redirects
        ProxyLookupRequests,

        // Connecting to the broker
        ProxyConnectingToBroker,

        // If we are proxying a connection to a specific broker, we
        // are just forwarding data between the 2 connections, without
        // looking into it
        ProxyConnectionToBroker,

        Closing,

        Closed,
    }

    ConnectionPool getConnectionPool() {
        return connectionPool;
    }

    public ProxyConnection(ProxyService proxyService, DnsAddressResolverGroup dnsAddressResolverGroup) {
        super(proxyService.getConfiguration().getKeepAliveIntervalSeconds(), TimeUnit.SECONDS);
        this.service = proxyService;
        this.dnsAddressResolverGroup = dnsAddressResolverGroup;
        this.state = State.Init;
        this.brokerProxyValidator = service.getBrokerProxyValidator();
        this.connectionController = proxyService.getConnectionController();
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        ProxyService.ACTIVE_CONNECTIONS.inc();
        SocketAddress rmAddress = ctx.channel().remoteAddress();
        ConnectionController.State state = connectionController.increaseConnection(rmAddress);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Active connection count={} for cnx {} with state {}", ProxyService.ACTIVE_CONNECTIONS.get(),
                    rmAddress, state);
        }
        if (!state.equals(ConnectionController.State.OK)) {
            ctx.writeAndFlush(Commands.newError(-1, ServerError.NotAllowedError,
                    state.equals(ConnectionController.State.REACH_MAX_CONNECTION)
                            ? "Reached the maximum number of connections"
                            : "Reached the maximum number of connections on address" + rmAddress))
                            .addListener(result -> ctx.close());
            ProxyService.REJECTED_CONNECTIONS.inc();
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        connectionController.decreaseConnection(ctx.channel().remoteAddress());
        ProxyService.ACTIVE_CONNECTIONS.dec();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Decreasing active connection count={} ", ProxyService.ACTIVE_CONNECTIONS.get());
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        ProxyService.NEW_CONNECTIONS.inc();
        service.getClientCnxs().add(this);
        isTlsInboundChannel = ProxyConnection.isTlsChannel(ctx.channel());
        LOG.info("[{}] New connection opened", remoteAddress);
    }

    @Override
    public synchronized void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);

        if (directProxyHandler != null) {
            directProxyHandler.close();
            directProxyHandler = null;
        }

        if (authRefreshTask != null) {
            authRefreshTask.cancel(false);
        }

        if (pendingBrokerAuthChallenges != null) {
            pendingBrokerAuthChallenges.forEach(future -> future.cancel(true));
            pendingBrokerAuthChallenges = null;
        }

        service.getClientCnxs().remove(this);
        LOG.info("[{}] Connection closed", remoteAddress);

        if (connectionPool != null) {
            try {
                connectionPool.close();
                connectionPool = null;
            } catch (Exception e) {
                LOG.error("Failed to close connection pool {}", e.getMessage(), e);
            }
        }

        state = State.Closed;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOG.warn("[{}] Got exception {} : Message: {} State: {}", remoteAddress, cause.getClass().getSimpleName(),
                cause.getMessage(), state,
                ClientCnx.isKnownException(cause) ? null : cause);
        if (state != State.Closed) {
            state = State.Closing;
        }
        if (ctx.channel().isOpen()) {
            ctx.close();
        } else {
            // close connection to broker if that is present
            if (directProxyHandler != null) {
                directProxyHandler.close();
                directProxyHandler = null;
            }
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (directProxyHandler != null && directProxyHandler.outboundChannel != null) {
            // handle backpressure
            // stop/resume reading input from connection between the proxy and the broker
            // when the writability of the connection between the client and the proxy changes
            directProxyHandler.outboundChannel.config().setAutoRead(ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HAProxyMessage) {
            haProxyMessage = (HAProxyMessage) msg;
            return;
        }
        switch (state) {
        case Init:
        case Connecting:
        case ProxyLookupRequests:
            // Do the regular decoding for the Connected message
            super.channelRead(ctx, msg);
            break;

        case ProxyConnectionToBroker:
            if (directProxyHandler != null) {
                ProxyService.OPS_COUNTER.inc();
                if (msg instanceof ByteBuf) {
                    int bytes = ((ByteBuf) msg).readableBytes();
                    directProxyHandler.getInboundChannelRequestsRate().recordEvent(bytes);
                    ProxyService.BYTES_COUNTER.inc(bytes);
                }
                directProxyHandler.outboundChannel
                        .writeAndFlush(msg, directProxyHandler.outboundChannel.voidPromise());

                if (service.proxyZeroCopyModeEnabled && service.proxyLogLevel == 0) {
                    if (!directProxyHandler.isTlsOutboundChannel && !isTlsInboundChannel) {
                        if (ctx.pipeline().get("readTimeoutHandler") != null) {
                            ctx.pipeline().remove("readTimeoutHandler");
                        }
                        spliceNIC2NIC((EpollSocketChannel) ctx.channel(),
                                (EpollSocketChannel) directProxyHandler.outboundChannel, SPLICE_BYTES)
                                .addListener(future -> {
                                    ProxyService.OPS_COUNTER.inc();
                                    ProxyService.BYTES_COUNTER.inc(SPLICE_BYTES);
                                    directProxyHandler.getInboundChannelRequestsRate().recordEvent(SPLICE_BYTES);
                                });
                    }
                }
            } else {
                LOG.warn("Received message of type {} while connection to broker is missing in state {}. "
                                + "Dropping the input message (readable bytes={}).", msg.getClass(), state,
                        msg instanceof ByteBuf ? ((ByteBuf) msg).readableBytes() : -1);
            }
            break;
        case ProxyConnectingToBroker:
            LOG.warn("Received message of type {} while connecting to broker. "
                            + "Dropping the input message (readable bytes={}).", msg.getClass(),
                    msg instanceof ByteBuf ? ((ByteBuf) msg).readableBytes() : -1);
            break;
        default:
            break;
        }
    }

    /**
     * Use splice to zero-copy of NIC to NIC.
     * @param inboundChannel input channel
     * @param outboundChannel output channel
     */
    protected static ChannelPromise spliceNIC2NIC(EpollSocketChannel inboundChannel,
                                                  EpollSocketChannel outboundChannel, int spliceLength) {
        ChannelPromise promise = inboundChannel.newPromise();
        inboundChannel.spliceTo(outboundChannel, spliceLength, promise);
        promise.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess() && !(future.cause() instanceof ClosedChannelException)) {
                future.channel().pipeline().fireExceptionCaught(future.cause());
            }
        });
        return promise;
    }

    protected static boolean isTlsChannel(Channel channel) {
        return channel.pipeline().get(ServiceChannelInitializer.TLS_HANDLER) != null;
    }

    private synchronized void completeConnect() throws PulsarClientException {
        checkArgument(state == State.Connecting);
        LOG.info("[{}] complete connection, init proxy handler. authenticated with {} role {}, hasProxyToBrokerUrl: {}",
                remoteAddress, authMethod, clientAuthRole, hasProxyToBrokerUrl);
        if (hasProxyToBrokerUrl) {
            // Optimize proxy connection to fail-fast if the target broker isn't active
            // Pulsar client will retry connecting after a back off timeout
            if (service.getConfiguration().isCheckActiveBrokers()
                    && !isBrokerActive(proxyToBrokerUrl)) {
                state = State.Closing;
                LOG.warn("[{}] Target broker '{}' isn't available. authenticated with {} role {}.",
                        remoteAddress, proxyToBrokerUrl, authMethod, clientAuthRole);
                final ByteBuf msg = Commands.newError(-1,
                        ServerError.ServiceNotReady, "Target broker isn't available.");
                writeAndFlushAndClose(msg);
                return;
            }

            state = State.ProxyConnectingToBroker;
            brokerProxyValidator.resolveAndCheckTargetAddress(proxyToBrokerUrl)
                    .thenAcceptAsync(this::connectToBroker, ctx.executor())
                    .exceptionally(throwable -> {
                        if (throwable instanceof TargetAddressDeniedException
                                || throwable.getCause() instanceof TargetAddressDeniedException) {
                            TargetAddressDeniedException targetAddressDeniedException =
                                    (TargetAddressDeniedException) (throwable instanceof TargetAddressDeniedException
                                            ? throwable : throwable.getCause());

                            LOG.warn("[{}] Target broker '{}' cannot be validated. {}. authenticated with {} role {}.",
                                    remoteAddress, proxyToBrokerUrl, targetAddressDeniedException.getMessage(),
                                    authMethod, clientAuthRole);
                        } else {
                            LOG.error("[{}] Error validating target broker '{}'. authenticated with {} role {}.",
                                    remoteAddress, proxyToBrokerUrl, authMethod, clientAuthRole, throwable);
                        }
                        final ByteBuf msg = Commands.newError(-1, ServerError.ServiceNotReady,
                                "Target broker cannot be validated.");
                        writeAndFlushAndClose(msg);
                        return null;
                    });
        } else {
            // Client is doing a lookup, we can consider the handshake complete
            // and we'll take care of just topics and partitions metadata lookups
            Supplier<ClientCnx> clientCnxSupplier;
            if (service.getConfiguration().isAuthenticationEnabled()) {
                clientCnxSupplier = () -> new ProxyClientCnx(clientConf, service.getWorkerGroup(), clientAuthRole,
                        clientAuthMethod, protocolVersionToAdvertise,
                        service.getConfiguration().isForwardAuthorizationCredentials(), this);
            } else {
                clientCnxSupplier =
                        () -> new ClientCnx(InstrumentProvider.NOOP, clientConf, service.getWorkerGroup(),
                                protocolVersionToAdvertise);
            }

            if (this.connectionPool == null) {
                this.connectionPool = new ConnectionPool(InstrumentProvider.NOOP, clientConf, service.getWorkerGroup(),
                        clientCnxSupplier,
                        Optional.of(dnsAddressResolverGroup.getResolver(service.getWorkerGroup().next())), null);
            } else {
                LOG.error("BUG! Connection Pool has already been created for proxy connection to {} state {} role {}",
                        remoteAddress, state, clientAuthRole);
            }

            state = State.ProxyLookupRequests;
            lookupProxyHandler = service.newLookupProxyHandler(this);
            if (service.getConfiguration().isAuthenticationEnabled()
                    && service.getConfiguration().getAuthenticationRefreshCheckSeconds() > 0) {
                authRefreshTask = ctx.executor().scheduleAtFixedRate(
                        Runnables.catchingAndLoggingThrowables(
                                this::refreshAuthenticationCredentialsAndCloseIfTooExpired),
                        service.getConfiguration().getAuthenticationRefreshCheckSeconds(),
                        service.getConfiguration().getAuthenticationRefreshCheckSeconds(),
                        TimeUnit.SECONDS);
            }
            final ByteBuf msg = Commands.newConnected(protocolVersionToAdvertise, false);
            writeAndFlush(msg);
        }
    }

    private void handleBrokerConnected(DirectProxyHandler directProxyHandler, CommandConnected connected) {
        assert ctx.executor().inEventLoop();
        if (state == State.ProxyConnectingToBroker && ctx.channel().isOpen() && this.directProxyHandler == null) {
            this.directProxyHandler = directProxyHandler;
            state = State.ProxyConnectionToBroker;
            int maxMessageSize =
                    connected.hasMaxMessageSize() ? connected.getMaxMessageSize() : Commands.INVALID_MAX_MESSAGE_SIZE;
            final ByteBuf msg = Commands.newConnected(connected.getProtocolVersion(), maxMessageSize,
                    connected.hasFeatureFlags() && connected.getFeatureFlags().isSupportsTopicWatchers());
            writeAndFlush(msg);
        } else {
            LOG.warn("[{}] Channel is {}. ProxyConnection is in {}. "
                            + "Closing connection to broker '{}'.",
                    remoteAddress, ctx.channel().isOpen() ? "open" : "already closed",
                    state != State.ProxyConnectingToBroker ? "invalid state " + state : "state " + state,
                    proxyToBrokerUrl);
            directProxyHandler.close();
            ctx.close();
        }
    }

    private void connectToBroker(InetSocketAddress brokerAddress) {
        assert ctx.executor().inEventLoop();
        DirectProxyHandler directProxyHandler = new DirectProxyHandler(service, this);
        directProxyHandler.connect(proxyToBrokerUrl, brokerAddress, protocolVersionToAdvertise, features);
    }

    public void brokerConnected(DirectProxyHandler directProxyHandler, CommandConnected connected) {
        try {
            final CommandConnected finalConnected = new CommandConnected().copyFrom(connected);
            handleBrokerConnected(directProxyHandler, finalConnected);
        } catch (RejectedExecutionException e) {
            LOG.error("Event loop was already closed. Closing broker connection.", e);
            directProxyHandler.close();
        } catch (AssertionError e) {
            LOG.error("Failed assertion, closing direct proxy handler.", e);
            directProxyHandler.close();
        }
    }

    // According to auth result, send newConnected or newAuthChallenge command.
    private void doAuthentication(AuthData clientData)
            throws Exception {
        authState
                .authenticateAsync(clientData)
                .whenCompleteAsync((authChallenge, throwable) -> {
                    if (throwable == null) {
                        authChallengeSuccessCallback(authChallenge);
                    } else {
                        authenticationFailedCallback(throwable);
                    }
                    }, ctx.executor());
    }

    protected void authenticationFailedCallback(Throwable t) {
        LOG.warn("[{}] Unable to authenticate: ", remoteAddress, t);
        final ByteBuf msg = Commands.newError(-1, ServerError.AuthenticationError, "Failed to authenticate");
        writeAndFlushAndClose(msg);
    }

    // Always run in this class's event loop.
    protected void authChallengeSuccessCallback(AuthData authChallenge) {
        try {
            // authentication has completed, will send newConnected command.
            if (authChallenge == null) {
                clientAuthRole = authState.getAuthRole();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[{}] Client successfully authenticated with {} role {}",
                            remoteAddress, authMethod, clientAuthRole);
                }

                // First connection
                if (state == State.Connecting) {
                    // authentication has completed, will send newConnected command.
                    completeConnect();
                }
                return;
            }

            // auth not complete, continue auth with client side.
            final ByteBuf msg = Commands.newAuthChallenge(authMethod, authChallenge, protocolVersionToAdvertise);
            writeAndFlush(msg);
            if (LOG.isDebugEnabled()) {
                LOG.debug("[{}] Authentication in progress client by method {}.",
                        remoteAddress, authMethod);
            }
        } catch (Exception e) {
            authenticationFailedCallback(e);
        }
    }

    private void refreshAuthenticationCredentialsAndCloseIfTooExpired() {
        assert ctx.executor().inEventLoop();
        if (state != State.ProxyLookupRequests) {
            // Happens when an exception is thrown that causes this connection to close.
            return;
        } else if (!authState.isExpired()) {
            // Credentials are still valid. Nothing to do at this point
            return;
        }

        if (System.nanoTime() - authChallengeSentTime
                > TimeUnit.SECONDS.toNanos(service.getConfiguration().getAuthenticationRefreshCheckSeconds())) {
            LOG.warn("[{}] Closing connection after timeout on refreshing auth credentials", remoteAddress);
            ctx.close();
        }

        maybeSendAuthChallenge();
    }

    private void maybeSendAuthChallenge() {
        assert ctx.executor().inEventLoop();

        if (!supportsAuthenticationRefresh()) {
            LOG.warn("[{}] Closing connection because client doesn't support auth credentials refresh", remoteAddress);
            ctx.close();
            return;
        } else if (authChallengeSentTime != Long.MAX_VALUE) {
            // If the proxy sent a refresh but hasn't yet heard back, do not send another challenge.
            return;
        } else if (service.getConfiguration().getAuthenticationRefreshCheckSeconds() < 1) {
            // Without the refresh check enabled, there is no way to guarantee the ProxyConnection will close
            // this connection if the client fails to respond to the auth challenge with valid auth data.
            // The cost is minimal since the client can recreate the connection. This logic prevents a leak.
            LOG.warn("[{}] Closing connection because auth credentials refresh is disabled", remoteAddress);
            ctx.close();
            return;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("[{}] Refreshing authentication credentials", remoteAddress);
        }
        try {
            AuthData challenge = authState.refreshAuthentication();
            writeAndFlush(Commands.newAuthChallenge(authMethod, challenge, protocolVersionToAdvertise));
            if (LOG.isDebugEnabled()) {
                LOG.debug("[{}] Sent auth challenge to client to refresh credentials with method: {}.",
                        remoteAddress, authMethod);
            }
            authChallengeSentTime = System.nanoTime();
        } catch (AuthenticationException e) {
            LOG.warn("[{}] Failed to refresh authentication: {}", remoteAddress, e);
            ctx.close();
        }
    }

    @Override
    protected void handleConnect(CommandConnect connect) {
        checkArgument(state == State.Init);
        state = State.Connecting;
        this.setRemoteEndpointProtocolVersion(connect.getProtocolVersion());
        this.hasProxyToBrokerUrl = connect.hasProxyToBrokerUrl();
        this.protocolVersionToAdvertise = getProtocolVersionToAdvertise(connect);
        this.proxyToBrokerUrl = connect.hasProxyToBrokerUrl() ? connect.getProxyToBrokerUrl() : "null";
        this.clientVersion = connect.getClientVersion();
        features = new FeatureFlags();
        if (connect.hasFeatureFlags()) {
            features.copyFrom(connect.getFeatureFlags());
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Received CONNECT from {} proxyToBroker={}", remoteAddress, proxyToBrokerUrl);
            LOG.debug(
                "[{}] Protocol version to advertise to broker is {}, clientProtocolVersion={}, proxyProtocolVersion={}",
                remoteAddress, protocolVersionToAdvertise, getRemoteEndpointProtocolVersion(),
                Commands.getCurrentProtocolVersion());
        }

        if (getRemoteEndpointProtocolVersion() < ProtocolVersion.v10.getValue()) {
            LOG.warn("[{}] Client doesn't support connecting through proxy", remoteAddress);
            state = State.Closing;
            ctx.close();
            return;
        }

        if (connect.hasProxyVersion()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[{}] Client illegally provided proxyVersion.", remoteAddress);
            }
            state = State.Closing;
            writeAndFlushAndClose(Commands.newError(-1, ServerError.NotAllowedError, "Must not provide proxyVersion"));
            return;
        }

        try {
            // init authn
            this.clientConf = createClientConfiguration();

            // authn not enabled, complete
            if (!service.getConfiguration().isAuthenticationEnabled()) {
                completeConnect();
                return;
            }

            AuthData clientData = AuthData.of(connect.hasAuthData() ? connect.getAuthData() : EMPTY_CREDENTIALS);
            if (connect.hasAuthMethodName()) {
                authMethod = connect.getAuthMethodName();
            } else if (connect.hasAuthMethod()) {
                // Legacy client is passing enum
                authMethod = connect.getAuthMethod().name().substring(10).toLowerCase();
            } else {
                authMethod = "none";
            }

            if (service.getConfiguration().isForwardAuthorizationCredentials()) {
                // We store the first clientData here. Before this commit, we stored the last clientData.
                // Since this only works when forwarding single staged authentication, first == last is true.
                // Here is an issue to fix the protocol: https://github.com/apache/pulsar/issues/19291.
                this.clientAuthData = clientData;
                this.clientAuthMethod = authMethod;
            }

            authenticationProvider = service
                .getAuthenticationService()
                .getAuthenticationProvider(authMethod);

            // Not find provider named authMethod. Most used for tests.
            // In AuthenticationDisabled, it will set authMethod "none".
            if (authenticationProvider == null) {
                clientAuthRole = service.getAuthenticationService().getAnonymousUserRole()
                    .orElseThrow(() ->
                        new AuthenticationException("No anonymous role, and no authentication provider configured"));

                completeConnect();
                return;
            }

            // init authState and other var
            ChannelHandler sslHandler = ctx.channel().pipeline().get(PulsarChannelInitializer.TLS_HANDLER);
            SSLSession sslSession = null;
            if (sslHandler != null) {
                sslSession = ((SslHandler) sslHandler).engine().getSession();
            }

            authState = authenticationProvider.newAuthState(clientData, remoteAddress, sslSession);
            doAuthentication(clientData);
        } catch (Exception e) {
            authenticationFailedCallback(e);
        }
    }

    @Override
    protected void handleAuthResponse(CommandAuthResponse authResponse) {
        checkArgument(authResponse.hasResponse());
        checkArgument(authResponse.getResponse().hasAuthData() && authResponse.getResponse().hasAuthMethodName());

        if (LOG.isDebugEnabled()) {
            LOG.debug("Received AuthResponse from {}, auth method: {}",
                    remoteAddress, authResponse.getResponse().getAuthMethodName());
        }

        try {
            // Reset the auth challenge sent time to indicate we are not waiting on a client response.
            authChallengeSentTime = Long.MAX_VALUE;
            AuthData clientData = AuthData.of(authResponse.getResponse().getAuthData());
            // Authenticate the client's auth data and send to the broker concurrently
            // Note: this implementation relies on the current weakness that prevents multi-stage authentication
            // from working when forwardAuthorizationCredentials is enabled. Here is an issue to fix the protocol:
            // https://github.com/apache/pulsar/issues/19291.
            doAuthentication(clientData);
            if (service.getConfiguration().isForwardAuthorizationCredentials()) {
                // Update the clientAuthData to be able to initialize future ProxyClientCnx.
                this.clientAuthData = clientData;
                // We only have pendingBrokerAuthChallenges when forwardAuthorizationCredentials is enabled.
                if (pendingBrokerAuthChallenges != null && !pendingBrokerAuthChallenges.isEmpty()) {
                    // Send auth data to pending challenges from the broker
                    for (CompletableFuture<AuthData> challenge : pendingBrokerAuthChallenges) {
                        challenge.complete(clientData);
                    }
                    pendingBrokerAuthChallenges.clear();
                }
            }
        } catch (Exception e) {
            String errorMsg = "Unable to handleAuthResponse";
            LOG.warn("[{}] {} ", remoteAddress, errorMsg, e);
            final ByteBuf msg = Commands.newError(-1, ServerError.AuthenticationError, errorMsg);
            writeAndFlushAndClose(msg);
        }
    }

    @Override
    protected void handlePartitionMetadataRequest(CommandPartitionedTopicMetadata partitionMetadata) {
        checkArgument(state == State.ProxyLookupRequests);

        lookupProxyHandler.handlePartitionMetadataResponse(partitionMetadata);
    }

    @Override
    protected void handleGetTopicsOfNamespace(CommandGetTopicsOfNamespace commandGetTopicsOfNamespace) {
        checkArgument(state == State.ProxyLookupRequests);

        lookupProxyHandler.handleGetTopicsOfNamespace(commandGetTopicsOfNamespace);
    }
    @Override
    protected void handleGetSchema(CommandGetSchema commandGetSchema) {
        checkArgument(state == State.ProxyLookupRequests);

        lookupProxyHandler.handleGetSchema(commandGetSchema);
    }

    /**
     * handles discovery request from client ands sends next active broker address.
     */
    @Override
    protected void handleLookup(CommandLookupTopic lookup) {
        checkArgument(state == State.ProxyLookupRequests);
        lookupProxyHandler.handleLookup(lookup);
    }

    ClientConfigurationData createClientConfiguration() {
        ClientConfigurationData initialConf = new ClientConfigurationData();
        ProxyConfiguration proxyConfig = service.getConfiguration();
        initialConf.setServiceUrl(
                proxyConfig.isTlsEnabledWithBroker() ? service.getServiceUrlTls() : service.getServiceUrl());
        /** The proxy service does not need to automatically clean up idling connections, so set to false. **/
        initialConf.setConnectionMaxIdleSeconds(-1);

        // Apply all arbitrary configuration. This must be called before setting any fields annotated as
        // @Secret on the ClientConfigurationData object because of the way they are serialized.
        // See https://github.com/apache/pulsar/issues/8509 for more information.
        Map<String, Object> overrides = PropertiesUtils
                .filterAndMapProperties(proxyConfig.getProperties(), "brokerClient_");
        ClientConfigurationData clientConf = ConfigurationDataUtils
                .loadData(overrides, initialConf, ClientConfigurationData.class);
        clientConf.setAuthentication(this.getClientAuthentication());
        if (proxyConfig.isTlsEnabledWithBroker()) {
            clientConf.setUseTls(true);
            clientConf.setTlsHostnameVerificationEnable(proxyConfig.isTlsHostnameVerificationEnabled());
            if (proxyConfig.isBrokerClientTlsEnabledWithKeyStore()) {
                clientConf.setUseKeyStoreTls(true);
                clientConf.setTlsTrustStoreType(proxyConfig.getBrokerClientTlsTrustStoreType());
                clientConf.setTlsTrustStorePath(proxyConfig.getBrokerClientTlsTrustStore());
                clientConf.setTlsTrustStorePassword(proxyConfig.getBrokerClientTlsTrustStorePassword());
                clientConf.setTlsKeyStoreType(proxyConfig.getBrokerClientTlsKeyStoreType());
                clientConf.setTlsKeyStorePath(proxyConfig.getBrokerClientTlsKeyStore());
                clientConf.setTlsKeyStorePassword(proxyConfig.getBrokerClientTlsKeyStorePassword());
            } else {
                clientConf.setTlsTrustCertsFilePath(proxyConfig.getBrokerClientTrustCertsFilePath());
                clientConf.setTlsKeyFilePath(proxyConfig.getBrokerClientKeyFilePath());
                clientConf.setTlsCertificateFilePath(proxyConfig.getBrokerClientCertificateFilePath());
            }
            clientConf.setTlsAllowInsecureConnection(proxyConfig.isTlsAllowInsecureConnection());
        }
        return clientConf;
    }

    private static int getProtocolVersionToAdvertise(CommandConnect connect) {
        return Math.min(connect.getProtocolVersion(), Commands.getCurrentProtocolVersion());
    }

    long newRequestId() {
        return requestIdGenerator.getAndIncrement();
    }

    public Authentication getClientAuthentication() {
        return service.getProxyClientAuthenticationPlugin();
    }

    @Override
    protected boolean isHandshakeCompleted() {
        return state != State.Init;
    }

    SocketAddress clientAddress() {
        return remoteAddress;
    }

    ChannelHandlerContext ctx() {
        return ctx;
    }

    public boolean hasHAProxyMessage() {
        return haProxyMessage != null;
    }

    public HAProxyMessage getHAProxyMessage() {
        return haProxyMessage;
    }

    private boolean isBrokerActive(String targetBrokerHostPort) {
        for (ServiceLookupData serviceLookupData : getAvailableBrokers()) {
            if (matchesHostAndPort("pulsar://", serviceLookupData.getPulsarServiceUrl(), targetBrokerHostPort)
                    || matchesHostAndPort("pulsar+ssl://", serviceLookupData.getPulsarServiceUrlTls(),
                    targetBrokerHostPort)) {
                return true;
            }
        }
        return false;
    }

    private List<? extends ServiceLookupData> getAvailableBrokers() {
        if (service.getDiscoveryProvider() == null) {
            LOG.warn("Unable to retrieve active brokers. service.getDiscoveryProvider() is null."
                    + "zookeeperServers and configurationStoreServers must be configured in proxy configuration "
                    + "when checkActiveBrokers is enabled.");
            return Collections.emptyList();
        }
        try {
            return service.getDiscoveryProvider().getAvailableBrokers();
        } catch (PulsarServerException e) {
            LOG.error("Unable to get available brokers", e);
            return Collections.emptyList();
        }
    }

    static boolean matchesHostAndPort(String expectedPrefix, String pulsarServiceUrl, String brokerHostPort) {
        return pulsarServiceUrl != null
                && pulsarServiceUrl.length() == expectedPrefix.length() + brokerHostPort.length()
                && pulsarServiceUrl.startsWith(expectedPrefix)
                && pulsarServiceUrl.startsWith(brokerHostPort, expectedPrefix.length());
    }

    private void writeAndFlush(ByteBuf cmd) {
        NettyChannelUtil.writeAndFlushWithVoidPromise(ctx, cmd);
    }

    private void writeAndFlushAndClose(ByteBuf cmd) {
        NettyChannelUtil.writeAndFlushWithClosePromise(ctx, cmd);
    }

    boolean supportsAuthenticationRefresh() {
        return features != null && features.isSupportsAuthRefresh();
    }

    AuthData getClientAuthData() {
        return clientAuthData;
    }

    /**
     * Thread-safe method to retrieve unexpired client auth data. Due to inherent race conditions,
     * the auth data may expire before it is used.
     */
    CompletableFuture<AuthData> getValidClientAuthData() {
        final CompletableFuture<AuthData> clientAuthDataFuture = new CompletableFuture<>();
        ctx().executor().execute(Runnables.catchingAndLoggingThrowables(() -> {
            // authState is not thread safe, so this must run on the ProxyConnection's event loop.
            if (!authState.isExpired()) {
                clientAuthDataFuture.complete(clientAuthData);
            } else if (state == State.ProxyLookupRequests) {
                maybeSendAuthChallenge();
                if (pendingBrokerAuthChallenges == null) {
                    pendingBrokerAuthChallenges = new HashSet<>();
                }
                pendingBrokerAuthChallenges.add(clientAuthDataFuture);
            } else {
                clientAuthDataFuture.completeExceptionally(new PulsarClientException.AlreadyClosedException(
                        "ProxyConnection is not in a valid state to get client auth data for " + remoteAddress));
            }
        }));
        return clientAuthDataFuture;
    }
}
