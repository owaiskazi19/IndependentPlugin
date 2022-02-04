import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioChannelOption;
import io.netty.util.AttributeKey;
import netty4.Netty4TcpChannel;
import netty4.Netty4TcpServerChannel;
import org.opensearch.Version;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.bytes.ReleasableBytesReference;
import org.opensearch.common.util.concurrent.ThreadContext;
import transport.TcpChannel;
import transport.OutboundHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.ExceptionsHelper;
import org.opensearch.action.ActionListener;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.Strings;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.component.AbstractLifecycleComponent;
import org.opensearch.common.network.NetworkAddress;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.BoundTransportAddress;
import org.opensearch.common.transport.PortsRange;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.unit.ByteSizeUnit;
import org.opensearch.common.unit.ByteSizeValue;
import org.opensearch.common.util.concurrent.ConcurrentCollections;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.core.internal.net.NetUtils;
import org.opensearch.env.Environment;
import org.opensearch.plugins.DiscoveryPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.*;
import io.netty.bootstrap.ServerBootstrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static org.opensearch.common.settings.Setting.byteSizeSetting;
import static org.opensearch.common.util.concurrent.ConcurrentCollections.newConcurrentMap;

public abstract class IndependentPlugin extends AbstractLifecycleComponent implements Transport {
    protected static Set<NewPlugin.ProfileSettings> profileSettings = getProfileSettings(Settings.builder().put("transport.profiles.test.port", "5555").put("transport.profiles.default.port", "3333").build());
    private static final Logger logger = LogManager.getLogger(IndependentPlugin.class);
    private static Environment env;
    private static final ReadWriteLock closeLock = new ReentrantReadWriteLock();
    private static final Map<String, List<TcpServerChannel>> serverChannels = newConcurrentMap();

    private static volatile BoundTransportAddress boundAddress;
    private static final ConcurrentMap<String, BoundTransportAddress> profileBoundAddresses = newConcurrentMap();
    private final Map<String, ServerBootstrap> serverBootstraps = newConcurrentMap();
    static final AttributeKey<Netty4TcpServerChannel> SERVER_CHANNEL_KEY = AttributeKey.newInstance("es-server-channel");
    private static SharedGroupFactory sharedGroupFactory;
    private static volatile SharedGroupFactory.SharedGroup sharedGroup;
    private final ByteSizeValue receivePredictorMin;
    private final ByteSizeValue receivePredictorMax;
    private final RecvByteBufAllocator recvByteBufAllocator;
    static final AttributeKey<Netty4TcpChannel> CHANNEL_KEY = AttributeKey.newInstance("es-channel");
    private final Set<TcpChannel> acceptedChannels = ConcurrentCollections.newConcurrentSet();
    protected final ThreadPool threadPool;
    private OutboundHandler handler;
    private TcpChannel channel;
    private DiscoveryNode node;
    private final TransportRequestOptions options = TransportRequestOptions.EMPTY;
    private final AtomicReference<Tuple<org.opensearch.transport.Header, BytesReference>> message = new AtomicReference<>();
    private static final Settings settings = Settings.builder()
            .put("node.name", "NettySizeHeaderFrameDecoderTests")
            .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
            .put(TransportSettings.PORT.getKey(), "0")
            .build();

    protected void serverAcceptedChannel(TcpChannel channel) {
        boolean addedOnThisCall = acceptedChannels.add(channel);
        assert addedOnThisCall : "Channel should only be added to accepted channel set once";
        // Mark the channel init time
        channel.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        channel.addCloseListener(ActionListener.wrap(() -> acceptedChannels.remove(channel)));
        logger.trace(() -> new ParameterizedMessage("Tcp transport channel accepted: {}", channel));
    }

    // Another class
    private void addClosedExceptionLogger(Channel channel) {
        channel.closeFuture().addListener(f -> {
            if (f.isSuccess() == false) {
                logger.debug(() -> new ParameterizedMessage("exception while closing channel: {}", channel), f.cause());
            }
        });
    }

    protected class ServerChannelInitializer extends ChannelInitializer<Channel> {

        protected final String name;
        private final NettyByteBufSizer sizer = new NettyByteBufSizer();

        protected ServerChannelInitializer(String name) {
            this.name = name;
        }

        @Override
        protected void initChannel(Channel ch) throws Exception {
            addClosedExceptionLogger(ch);
            assert ch instanceof Netty4NioSocketChannel;
            NetUtils.tryEnsureReasonableKeepAliveConfig(((Netty4NioSocketChannel) ch).javaChannel());
            Netty4TcpChannel nettyTcpChannel = new Netty4TcpChannel(ch, true, name, ch.newSucceededFuture());
            ch.attr(CHANNEL_KEY).set(nettyTcpChannel);
            ch.pipeline().addLast("byte_buf_sizer", sizer);
            ch.pipeline().addLast("logging", new OpenSearchLoggingHandler());
            //ch.pipeline().addLast("dispatcher", new Netty4MessageChannelHandler(pageCacheRecycler, Netty4Transport.this));
            serverAcceptedChannel((TcpChannel) nettyTcpChannel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            ExceptionsHelper.maybeDieOnAnotherThread(cause);
            super.exceptionCaught(ctx, cause);
        }
    }


    //
    public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_SIZE = Setting.byteSizeSetting(
            "transport.netty.receive_predictor_size",
            new ByteSizeValue(64, ByteSizeUnit.KB),
            Setting.Property.NodeScope
    );

    public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_MIN = byteSizeSetting(
            "transport.netty.receive_predictor_min",
            NETTY_RECEIVE_PREDICTOR_SIZE,
            Setting.Property.NodeScope
    );

    public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_MAX = byteSizeSetting(
            "transport.netty.receive_predictor_max",
            NETTY_RECEIVE_PREDICTOR_SIZE,
            Setting.Property.NodeScope
    );

    public static final Setting<Integer> WORKER_COUNT = new Setting<>(
            "transport.netty.worker_count",
            (s) -> Integer.toString(OpenSearchExecutors.allocatedProcessors(s)),
            (s) -> Setting.parseInt(s, 1, "transport.netty.worker_count"),
            Setting.Property.NodeScope
    );

    protected IndependentPlugin(SharedGroupFactory sharedGroupFactory) {
        this.sharedGroupFactory = sharedGroupFactory;
        this.threadPool = new TestThreadPool("test");;
        this.receivePredictorMin = NETTY_RECEIVE_PREDICTOR_MIN.get(settings);
        this.receivePredictorMax = NETTY_RECEIVE_PREDICTOR_MAX.get(settings);
        if (receivePredictorMax.getBytes() == receivePredictorMin.getBytes()) {
            recvByteBufAllocator = new FixedRecvByteBufAllocator((int) receivePredictorMax.getBytes());
        } else {
            recvByteBufAllocator = new AdaptiveRecvByteBufAllocator(
                    (int) receivePredictorMin.getBytes(),
                    (int) receivePredictorMin.getBytes(),
                    (int) receivePredictorMax.getBytes()
            );
        }
    }


    // Another clas


    public static Set<NewPlugin.ProfileSettings> getProfileSettings(Settings settings) {
        HashSet<NewPlugin.ProfileSettings> profiles = new HashSet<>();
        boolean isDefaultSet = false;
        for (String profile : settings.getGroups("transport.profiles.", true).keySet()) {
            profiles.add(new NewPlugin.ProfileSettings(settings, profile));
            if (TransportSettings.DEFAULT_PROFILE.equals(profile)) {
                isDefaultSet = true;
            }
        }
        if (isDefaultSet == false) {
            profiles.add(new NewPlugin.ProfileSettings(settings, TransportSettings.DEFAULT_PROFILE));
        }
        return Collections.unmodifiableSet(profiles);
    }

    public static Settings settings() {
        return env.settings();
    }

    private static List<NetworkService.CustomNameResolver> getCustomNameResolvers(List<DiscoveryPlugin> discoveryPlugins) {
        List<NetworkService.CustomNameResolver> customNameResolvers = new ArrayList<>();
        for (DiscoveryPlugin discoveryPlugin : discoveryPlugins) {
            NetworkService.CustomNameResolver customNameResolver = discoveryPlugin.getCustomNameResolver(settings());
            if (customNameResolver != null) {
                customNameResolvers.add(customNameResolver);
            }
        }
        return customNameResolvers;
    }

    protected ChannelHandler getServerChannelInitializer(String name) {
        return new ServerChannelInitializer(name);
    }

    public static <T> List<T> filterPlugins(Class<T> type) {
        final List<Tuple<PluginInfo, Plugin>> plugins = new ArrayList<>();
        return plugins.stream().filter(x -> type.isAssignableFrom(x.v2().getClass())).map(p -> ((T) p.v2())).collect(Collectors.toList());
    }

    final static NetworkService networkService = new NetworkService(
            getCustomNameResolvers(filterPlugins(DiscoveryPlugin.class))
    );

    protected TcpServerChannel bind(String name, InetSocketAddress address) {
        Channel channel = serverBootstraps.get(name).bind(address).syncUninterruptibly().channel();
        Netty4TcpServerChannel esChannel = new Netty4TcpServerChannel(channel);
        channel.attr(SERVER_CHANNEL_KEY).set(esChannel);
        return esChannel;
    }

    private InetSocketAddress bindToPort(final String name, final InetAddress hostAddress, String port) {
        PortsRange portsRange = new PortsRange(port);
        final AtomicReference<Exception> lastException = new AtomicReference<>();
        final AtomicReference<InetSocketAddress> boundSocket = new AtomicReference<>();
        closeLock.writeLock().lock();
        try {
            // No need for locking here since Lifecycle objects can't move from STARTED to INITIALIZED
            if (lifecycle.initialized() == false && lifecycle.started() == false) {
                throw new IllegalStateException("transport has been stopped");
            }
            boolean success = portsRange.iterate(portNumber -> {
                try {
                    TcpServerChannel channel = bind(name, new InetSocketAddress(hostAddress, portNumber));
                    serverChannels.computeIfAbsent(name, k -> new ArrayList<>()).add(channel);
                    boundSocket.set(channel.getLocalAddress());
                } catch (Exception e) {
                    lastException.set(e);
                    return false;
                }
                return true;
            });
            if (!success) {
                throw new BindTransportException(
                        "Failed to bind to " + NetworkAddress.format(hostAddress, portsRange),
                        lastException.get()
                );
            }
        } finally {
            closeLock.writeLock().unlock();
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Bound profile [{}] to address {{}}", name, NetworkAddress.format(boundSocket.get()));
        }

        return boundSocket.get();
    }

    public static int resolvePublishPort(NewPlugin.ProfileSettings profileSettings, List<InetSocketAddress> boundAddresses, InetAddress publishInetAddress) {
        int publishPort = profileSettings.publishPort;

        // if port not explicitly provided, search for port of address in boundAddresses that matches publishInetAddress
        if (publishPort < 0) {
            for (InetSocketAddress boundAddress : boundAddresses) {
                InetAddress boundInetAddress = boundAddress.getAddress();
                if (boundInetAddress.isAnyLocalAddress() || boundInetAddress.equals(publishInetAddress)) {
                    publishPort = boundAddress.getPort();
                    break;
                }
            }
        }

        // if no matching boundAddress found, check if there is a unique port for all bound addresses
        if (publishPort < 0) {
            final IntSet ports = new IntHashSet();
            for (InetSocketAddress boundAddress : boundAddresses) {
                ports.add(boundAddress.getPort());
            }
            if (ports.size() == 1) {
                publishPort = ports.iterator().next().value;
            }
        }

        if (publishPort < 0) {
            String profileExplanation = profileSettings.isDefaultProfile ? "" : " for profile " + profileSettings.profileName;
            throw new BindTransportException(
                    "Failed to auto-resolve publish port"
                            + profileExplanation
                            + ", multiple bound addresses "
                            + boundAddresses
                            + " with distinct ports and none of them matched the publish address ("
                            + publishInetAddress
                            + "). "
                            + "Please specify a unique port by setting "
                            + TransportSettings.PORT.getKey()
                            + " or "
                            + TransportSettings.PUBLISH_PORT.getKey()
            );
        }
        return publishPort;
    }


    private static BoundTransportAddress createBoundTransportAddress(NewPlugin.ProfileSettings profileSettings, List<InetSocketAddress> boundAddresses) {
        String[] boundAddressesHostStrings = new String[boundAddresses.size()];
        TransportAddress[] transportBoundAddresses = new TransportAddress[boundAddresses.size()];
        for (int i = 0; i < boundAddresses.size(); i++) {
            InetSocketAddress boundAddress = boundAddresses.get(i);
            boundAddressesHostStrings[i] = boundAddress.getHostString();
            transportBoundAddresses[i] = new TransportAddress(boundAddress);
        }

        List<String> publishHosts = profileSettings.publishHosts;
        if (profileSettings.isDefaultProfile == false && publishHosts.isEmpty()) {
            publishHosts = Arrays.asList(boundAddressesHostStrings);
        }
        if (publishHosts.isEmpty()) {
            publishHosts = NetworkService.GLOBAL_NETWORK_PUBLISH_HOST_SETTING.get(settings);
        }

        final InetAddress publishInetAddress;
        try {
            publishInetAddress = networkService.resolvePublishHostAddresses(publishHosts.toArray(Strings.EMPTY_ARRAY));
        } catch (Exception e) {
            throw new BindTransportException("Failed to resolve publish address", e);
        }

        final int publishPort = resolvePublishPort(profileSettings, boundAddresses, publishInetAddress);
        final TransportAddress publishAddress = new TransportAddress(new InetSocketAddress(publishInetAddress, publishPort));
        return new BoundTransportAddress(transportBoundAddresses, publishAddress);
    }


    protected final void bindServer(NewPlugin.ProfileSettings profileSettings) {
        // Bind and start to accept incoming connections.
        System.out.println("PROFILE");
        //logger.info("PROFILE", profileSettings);
        InetAddress[] hostAddresses;
        List<String> profileBindHosts = profileSettings.bindHosts;
        try {
            hostAddresses = networkService.resolveBindHostAddresses(profileBindHosts.toArray(Strings.EMPTY_ARRAY));
        } catch (IOException e) {
            throw new BindTransportException("Failed to resolve host " + profileBindHosts, e);
        }
        if (logger.isDebugEnabled()) {
            String[] addresses = new String[hostAddresses.length];
            for (int i = 0; i < hostAddresses.length; i++) {
                addresses[i] = NetworkAddress.format(hostAddresses[i]);
            }
            logger.debug("binding server bootstrap to: {}", (Object) addresses);
        }

        assert hostAddresses.length > 0;

        List<InetSocketAddress> boundAddresses = new ArrayList<>();
        for (InetAddress hostAddress : hostAddresses) {
            boundAddresses.add(bindToPort(profileSettings.profileName, hostAddress, profileSettings.portOrRange));
        }

        final BoundTransportAddress boundTransportAddress = createBoundTransportAddress(profileSettings, boundAddresses);

        if (profileSettings.isDefaultProfile) {
            boundAddress = boundTransportAddress;
        } else {
            profileBoundAddresses.put(profileSettings.profileName, boundTransportAddress);
        }
    }

    private void createServerBootstrap(NewPlugin.ProfileSettings profileSettings, SharedGroupFactory.SharedGroup sharedGroup) {
        String name = profileSettings.profileName;
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "using profile[{}], worker_count[{}], port[{}], bind_host[{}], publish_host[{}], receive_predictor[{}->{}]",
                    name,
                    sharedGroupFactory.getTransportWorkerCount(),
                    profileSettings.portOrRange,
                    profileSettings.bindHosts,
                    profileSettings.publishHosts,
                    receivePredictorMin,
                    receivePredictorMax
            );
        }

        final ServerBootstrap serverBootstrap = new ServerBootstrap();

        serverBootstrap.group(sharedGroup.getLowLevelGroup());

        // NettyAllocator will return the channel type designed to work with the configuredAllocator
        serverBootstrap.channel(NettyAllocator.getServerChannelType());

        // Set the allocators for both the server channel and the child channels created
        serverBootstrap.option(ChannelOption.ALLOCATOR, NettyAllocator.getAllocator());
        serverBootstrap.childOption(ChannelOption.ALLOCATOR, NettyAllocator.getAllocator());

        serverBootstrap.childHandler(getServerChannelInitializer(name));
       // serverBootstrap.handler(new ServerChannelExceptionHandler());

        serverBootstrap.childOption(ChannelOption.TCP_NODELAY, profileSettings.tcpNoDelay);
        serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, profileSettings.tcpKeepAlive);
        if (profileSettings.tcpKeepAlive) {
            // Note that Netty logs a warning if it can't set the option
            if (profileSettings.tcpKeepIdle >= 0) {
                final SocketOption<Integer> keepIdleOption = NetUtils.getTcpKeepIdleSocketOptionOrNull();
                if (keepIdleOption != null) {
                    serverBootstrap.childOption(NioChannelOption.of(keepIdleOption), profileSettings.tcpKeepIdle);
                }
            }
            if (profileSettings.tcpKeepInterval >= 0) {
                final SocketOption<Integer> keepIntervalOption = NetUtils.getTcpKeepIntervalSocketOptionOrNull();
                if (keepIntervalOption != null) {
                    serverBootstrap.childOption(NioChannelOption.of(keepIntervalOption), profileSettings.tcpKeepInterval);
                }

            }
            if (profileSettings.tcpKeepCount >= 0) {
                final SocketOption<Integer> keepCountOption = NetUtils.getTcpKeepCountSocketOptionOrNull();
                if (keepCountOption != null) {
                    serverBootstrap.childOption(NioChannelOption.of(keepCountOption), profileSettings.tcpKeepCount);
                }
            }
        }

        if (profileSettings.sendBufferSize.getBytes() != -1) {
            serverBootstrap.childOption(ChannelOption.SO_SNDBUF, Math.toIntExact(profileSettings.sendBufferSize.getBytes()));
        }

        if (profileSettings.receiveBufferSize.getBytes() != -1) {
            serverBootstrap.childOption(ChannelOption.SO_RCVBUF, Math.toIntExact(profileSettings.receiveBufferSize.bytesAsInt()));
        }

        serverBootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator);
        serverBootstrap.childOption(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator);

        serverBootstrap.option(ChannelOption.SO_REUSEADDR, profileSettings.reuseAddress);
        serverBootstrap.childOption(ChannelOption.SO_REUSEADDR, profileSettings.reuseAddress);
        serverBootstrap.validate();

        serverBootstraps.put(name, serverBootstrap);
    }

    // Test Send Request



    public AtomicReference<ActionListener<Void>> getListenerCaptor() {
        return new AtomicReference();
    }


    public void testSendRequest() throws IOException {
        ThreadContext threadContext = threadPool.getThreadContext();
        Version version = Version.CURRENT;
        String action = "handshake";
        long requestId = 200;
        boolean isHandshake = true;
        boolean compress = true;
        String value = "message";
        threadContext.putHeader("header", "header_value");
        TestRequest request = new TestRequest(value);

        AtomicReference<DiscoveryNode> nodeRef = new AtomicReference<>();
        AtomicLong requestIdRef = new AtomicLong();
        AtomicReference<String> actionRef = new AtomicReference<>();
        AtomicReference<TransportRequest> requestRef = new AtomicReference<>();
        handler.setMessageListener(new TransportMessageListener() {
            @Override
            public void onRequestSent(
                    DiscoveryNode node,
                    long requestId,
                    String action,
                    TransportRequest request,
                    TransportRequestOptions options
            ) {
                nodeRef.set(node);
                requestIdRef.set(requestId);
                actionRef.set(action);
                requestRef.set(request);
            }
        });
        handler.sendRequest(node, channel, requestId, action, request, options, version, compress, isHandshake);

        //BytesReference reference = channel.getMessageCaptor().get();
        ActionListener<Void> sendListener = getListenerCaptor().get();
        boolean flag = true;
        if (flag) {
            sendListener.onResponse(null);
        } else {
            sendListener.onFailure(new IOException("failed"));
        }
//        assertEquals(node, nodeRef.get());
//        assertEquals(requestId, requestIdRef.get());
//        assertEquals(action, actionRef.get());
//        assertEquals(request, requestRef.get());

        //pipeline.handleBytes(channel, new ReleasableBytesReference(reference, () -> {}));
        final Tuple<Header, BytesReference> tuple = message.get();
        final Header header = tuple.v1();
        final TestRequest message = new TestRequest(tuple.v2().streamInput());
        logger.debug("VERSION", version);
        logger.debug("HEADER", header);
        logger.debug("MESSAGE", message);
       // assertEquals(version, header);
//        assertEquals(requestId, header.getRequestId());
//        assertTrue(header.isRequest());
//        assertFalse(header.isResponse());
//        if (isHandshake) {
//            assertTrue(header.isHandshake());
//        } else {
//            assertFalse(header.isHandshake());
//        }
//        if (compress) {
//            assertTrue(header.isCompressed());
//        } else {
//            assertFalse(header.isCompressed());
//        }

//        assertEquals(value, message.value);
//        assertEquals("header_value", header.getHeaders().v1().get("header"));
    }



    public static void main(String[] args) throws IOException {
        IndependentPlugin newPlugin = new IndependentPlugin(new SharedGroupFactory(settings)) {
            @Override
            protected void doStart() {

            }

            @Override
            protected void doStop() {

            }

            @Override
            protected void doClose() throws IOException {

            }

            @Override
            public void setMessageListener(TransportMessageListener transportMessageListener) {

            }

            @Override
            public BoundTransportAddress boundAddress() {
                return null;
            }

            @Override
            public Map<String, BoundTransportAddress> profileBoundAddresses() {
                return null;
            }

            @Override
            public TransportAddress[] addressesFromString(String s) throws UnknownHostException {
                return new TransportAddress[0];
            }

            @Override
            public List<String> getDefaultSeedAddresses() {
                return null;
            }

            @Override
            public void openConnection(DiscoveryNode discoveryNode, ConnectionProfile connectionProfile, ActionListener<Connection> actionListener) {

            }

            @Override
            public TransportStats getStats() {
                return null;
            }

            @Override
            public ResponseHandlers getResponseHandlers() {
                return null;
            }

            @Override
            public RequestHandlers getRequestHandlers() {
                return null;
            }
        };
        sharedGroup = sharedGroupFactory.getTransportGroup();

        for (NewPlugin.ProfileSettings profileSettings : profileSettings) {
            newPlugin.createServerBootstrap(profileSettings, sharedGroup);
            newPlugin.bindServer(profileSettings);
        }
        newPlugin.testSendRequest();
    }
}
