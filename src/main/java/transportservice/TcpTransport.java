package transportservice;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.OpenSearchException;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.Booleans;
import org.opensearch.common.Strings;
import org.opensearch.common.breaker.CircuitBreaker;
import org.opensearch.common.bytes.BytesArray;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.component.Lifecycle;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.metrics.MeanMetric;
import org.opensearch.common.network.CloseableChannel;
import org.opensearch.common.network.NetworkAddress;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.BoundTransportAddress;
import org.opensearch.common.transport.PortsRange;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.unit.ByteSizeValue;
import org.opensearch.common.util.BigArrays;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.common.util.concurrent.ConcurrentCollections;
import org.opensearch.indices.breaker.CircuitBreakerService;
import org.opensearch.monitor.jvm.JvmInfo;
import org.opensearch.node.Node;
import org.opensearch.rest.RestStatus;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.*;
import transportservice.component.AbstractLifecycleComponent;
import transportservice.transport.ConnectionProfile;
import transportservice.transport.InboundHandler;
import transportservice.transport.OutboundHandler;
import transportservice.transport.Transport;
import transportservice.transport.TransportHandshaker;
import transportservice.transport.TransportKeepAlive;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.CancelledKeyException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import transportservice.transport.TcpChannel;
import transportservice.transport.InboundMessage;
import transportservice.transport.Transport.ResponseHandlers;


import static java.util.Collections.unmodifiableMap;
import static org.opensearch.common.transport.NetworkExceptionHelper.isCloseConnectionException;
import static org.opensearch.common.transport.NetworkExceptionHelper.isConnectException;
import static org.opensearch.common.util.concurrent.ConcurrentCollections.newConcurrentMap;

public abstract class TcpTransport extends AbstractLifecycleComponent implements Transport {

    private static final Logger logger = LogManager.getLogger(TcpTransport.class);
    // This is the number of bytes necessary to read the message size
    private static final int BYTES_NEEDED_FOR_MESSAGE_SIZE = TcpHeader.MARKER_BYTES_SIZE + TcpHeader.MESSAGE_LENGTH_SIZE;
    private static final long THIRTY_PER_HEAP_SIZE = (long) (JvmInfo.jvmInfo().getMem().getHeapMax().getBytes() * 0.3);
    protected final Settings settings;
    private final Version version;
    protected final ThreadPool threadPool;
    protected final PageCacheRecycler pageCacheRecycler;
    protected final NetworkService networkService;
    protected final Set<ProfileSettings> profileSettings;
    private final CircuitBreakerService circuitBreakerService;
    private volatile BoundTransportAddress boundAddress;

    private final ConcurrentMap<String, BoundTransportAddress> profileBoundAddresses = newConcurrentMap();
    private final Map<String, List<TcpServerChannel>> serverChannels = newConcurrentMap();
    private final Set<TcpChannel> acceptedChannels = ConcurrentCollections.newConcurrentSet();
    private final OutboundHandler outboundHandler;
    private final InboundHandler inboundHandler;
    private final TransportKeepAlive keepAlive;
    private final TransportHandshaker handshaker;
    private final transportservice.transport.Transport.ResponseHandlers responseHandlers = new transportservice.transport.Transport.ResponseHandlers();
    private final transportservice.transport.Transport.RequestHandlers requestHandlers = new transportservice.transport.Transport.RequestHandlers();


    private final ReadWriteLock closeLock = new ReentrantReadWriteLock();
    final StatsTracker statsTracker = new StatsTracker();
    private final AtomicLong outboundConnectionCount = new AtomicLong(); // also used as a correlation ID for open/close logs


    public TcpTransport(
            Settings settings,
            Version version,
            ThreadPool threadPool,
            PageCacheRecycler pageCacheRecycler,
            CircuitBreakerService circuitBreakerService,
            NamedWriteableRegistry namedWriteableRegistry,
            NetworkService networkService
    ) {
        this.settings = settings;
        this.profileSettings = getProfileSettings(Settings.builder().put("transport.profiles.test.port", "5555").put("transport.profiles.default.port", "3333").build());
        this.version = version;
        this.threadPool = threadPool;
        this.pageCacheRecycler = pageCacheRecycler;
        this.circuitBreakerService = circuitBreakerService;
        this.networkService = networkService;
        String nodeName = Node.NODE_NAME_SETTING.get(settings);
        final Settings defaultFeatures = TransportSettings.DEFAULT_FEATURES_SETTING.get(settings);

        String[] features;
        if (defaultFeatures == null) {
            features = new String[0];
        } else {
            defaultFeatures.names().forEach(key -> {
                if (Booleans.parseBoolean(defaultFeatures.get(key)) == false) {
                    throw new IllegalArgumentException("feature settings must have default [true] value");
                }
            });
            // use a sorted set to present the features in a consistent order
            features = new TreeSet<>(defaultFeatures.names()).toArray(new String[defaultFeatures.names().size()]);
        }
        BigArrays bigArrays = new BigArrays(pageCacheRecycler, circuitBreakerService, CircuitBreaker.IN_FLIGHT_REQUESTS);
        this.outboundHandler = new OutboundHandler(nodeName, version, features, statsTracker, threadPool, bigArrays);
        this.handshaker = new TransportHandshaker(
                version,
                threadPool,
                (node, channel, requestId, v) -> outboundHandler.sendRequest(
                        node,
                        channel,
                        requestId,
                        TransportHandshaker.HANDSHAKE_ACTION_NAME,
                        new TransportHandshaker.HandshakeRequest(version),
                        TransportRequestOptions.EMPTY,
                        v,
                        false,
                        true
                )
        );
        this.keepAlive = new TransportKeepAlive(threadPool, this.outboundHandler::sendBytes);
        this.inboundHandler = new InboundHandler(
                threadPool,
                outboundHandler,
                namedWriteableRegistry,
                handshaker,
                keepAlive,
                requestHandlers,
                responseHandlers
        );
    }


    public StatsTracker getStatsTracker() {
        return statsTracker;
    }

    public Supplier<CircuitBreaker> getInflightBreaker() {
        return () -> circuitBreakerService.getBreaker(CircuitBreaker.IN_FLIGHT_REQUESTS);
    }

    @Override
    protected void doStart() {

    }

    protected abstract void stopInternal();

    public void inboundMessage(TcpChannel channel, InboundMessage message) {
        try {
            inboundHandler.inboundMessage(channel, message);
        } catch (Exception e) {
            onException(channel, e);
        }
    }

    public final long getNumHandshakes() {
        return handshaker.getNumHandshakes();
    }
    public final TransportKeepAlive getKeepAlive() {
        return keepAlive;
    }


    /**
     * Validates the first 6 bytes of the message header and returns the length of the message. If 6 bytes
     * are not available, it returns -1.
     *
     * @param networkBytes the will be read
     * @return the length of the message
     * @throws StreamCorruptedException              if the message header format is not recognized
     * @throws org.opensearch.transport.TcpTransport.HttpRequestOnTransportException       if the message header appears to be an HTTP message
     * @throws IllegalArgumentException              if the message length is greater that the maximum allowed frame size.
     *                                               This is dependent on the available memory.
     */

    public static int readMessageLength(BytesReference networkBytes) throws IOException {
        if (networkBytes.length() < BYTES_NEEDED_FOR_MESSAGE_SIZE) {
            return -1;
        } else {
            return readHeaderBuffer(networkBytes);
        }
    }

    private static int readHeaderBuffer(BytesReference headerBuffer) throws IOException {
        if (headerBuffer.get(0) != 'E' || headerBuffer.get(1) != 'S') {
            if (appearsToBeHTTPRequest(headerBuffer)) {
                throw new TcpTransport.HttpRequestOnTransportException("This is not an HTTP port");
            }

            if (appearsToBeHTTPResponse(headerBuffer)) {
                throw new StreamCorruptedException(
                        "received HTTP response on transport port, ensure that transport port (not "
                                + "HTTP port) of a remote node is specified in the configuration"
                );
            }

            String firstBytes = "("
                    + Integer.toHexString(headerBuffer.get(0) & 0xFF)
                    + ","
                    + Integer.toHexString(headerBuffer.get(1) & 0xFF)
                    + ","
                    + Integer.toHexString(headerBuffer.get(2) & 0xFF)
                    + ","
                    + Integer.toHexString(headerBuffer.get(3) & 0xFF)
                    + ")";

            if (appearsToBeTLS(headerBuffer)) {
                throw new StreamCorruptedException("SSL/TLS request received but SSL/TLS is not enabled on this node, got " + firstBytes);
            }

            throw new StreamCorruptedException("invalid internal transport message format, got " + firstBytes);
        }
        final int messageLength = headerBuffer.getInt(TcpHeader.MARKER_BYTES_SIZE);

        if (messageLength == TransportKeepAlive.PING_DATA_SIZE) {
            // This is a ping
            return 0;
        }

        if (messageLength <= 0) {
            throw new StreamCorruptedException("invalid data length: " + messageLength);
        }

        if (messageLength > THIRTY_PER_HEAP_SIZE) {
            throw new IllegalArgumentException(
                    "transport content length received ["
                            + new ByteSizeValue(messageLength)
                            + "] exceeded ["
                            + new ByteSizeValue(THIRTY_PER_HEAP_SIZE)
                            + "]"
            );
        }

        return messageLength;
    }

    private static boolean appearsToBeHTTPRequest(BytesReference headerBuffer) {
        return bufferStartsWith(headerBuffer, "GET")
                || bufferStartsWith(headerBuffer, "POST")
                || bufferStartsWith(headerBuffer, "PUT")
                || bufferStartsWith(headerBuffer, "HEAD")
                || bufferStartsWith(headerBuffer, "DELETE")
                // Actually 'OPTIONS'. But we are only guaranteed to have read six bytes at this point.
                || bufferStartsWith(headerBuffer, "OPTION")
                || bufferStartsWith(headerBuffer, "PATCH")
                || bufferStartsWith(headerBuffer, "TRACE");
    }

    private static boolean appearsToBeHTTPResponse(BytesReference headerBuffer) {
        return bufferStartsWith(headerBuffer, "HTTP");
    }

    private static boolean appearsToBeTLS(BytesReference headerBuffer) {
        return headerBuffer.get(0) == 0x16 && headerBuffer.get(1) == 0x03;
    }

    private static boolean bufferStartsWith(BytesReference buffer, String method) {
        char[] chars = method.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (buffer.get(i) != chars[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * A helper exception to mark an incoming connection as potentially being HTTP
     * so an appropriate error code can be returned
     */
    public static class HttpRequestOnTransportException extends OpenSearchException {

        HttpRequestOnTransportException(String msg) {
            super(msg);
        }

        @Override
        public RestStatus status() {
            return RestStatus.BAD_REQUEST;
        }

        public HttpRequestOnTransportException(StreamInput in) throws IOException {
            super(in);
        }
    }



    @Override
    protected void doStop() {
        final CountDownLatch latch = new CountDownLatch(1);
        // make sure we run it on another thread than a possible IO handler thread
        assert threadPool.generic().isShutdown() == false : "Must stop transport before terminating underlying threadpool";
        threadPool.generic().execute(() -> {
            closeLock.writeLock().lock();
            try {
                keepAlive.close();

                // first stop to accept any incoming connections so nobody can connect to this transport
                for (Map.Entry<String, List<TcpServerChannel>> entry : serverChannels.entrySet()) {
                    String profile = entry.getKey();
                    List<TcpServerChannel> channels = entry.getValue();
                    ActionListener<Void> closeFailLogger = ActionListener.wrap(
                            c -> {},
                            e -> logger.warn(() -> new ParameterizedMessage("Error closing serverChannel for profile [{}]", profile), e)
                    );
                    channels.forEach(c -> c.addCloseListener(closeFailLogger));
                    CloseableChannel.closeChannels(channels, true);
                }
                serverChannels.clear();

                // close all of the incoming channels. The closeChannels method takes a list so we must convert the set.
                CloseableChannel.closeChannels(new ArrayList<>(acceptedChannels), true);
                acceptedChannels.clear();

                stopInternal();
            } finally {
                closeLock.writeLock().unlock();
                latch.countDown();
            }
        });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // ignore
        }
    }

    @Override
    protected void doClose() throws IOException {

    }

    @Override
    public void setMessageListener(transportservice.transport.TransportMessageListener transportMessageListener) {
        outboundHandler.setMessageListener(transportMessageListener);
        inboundHandler.setMessageListener(transportMessageListener);
    }

    @Override
    public BoundTransportAddress boundAddress() {
        return this.boundAddress;
    }

    @Override
    public Map<String, BoundTransportAddress> profileBoundAddresses() {
        return unmodifiableMap(new HashMap<>(profileBoundAddresses));
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
    public void openConnection(DiscoveryNode discoveryNode, ConnectionProfile connectionProfile, transportservice.action.ActionListener<Connection> actionListener) {

    }

    @Override
    public TransportStats getStats() {
        final MeanMetric writeBytesMetric = statsTracker.getWriteBytes();
        final long bytesWritten = statsTracker.getBytesWritten();
        final long messagesSent = statsTracker.getMessagesSent();
        final long messagesReceived = statsTracker.getMessagesReceived();
        final long bytesRead = statsTracker.getBytesRead();
        return new TransportStats(
                acceptedChannels.size(),
                outboundConnectionCount.get(),
                messagesReceived,
                bytesRead,
                messagesSent,
                bytesWritten
        );
    }

    @Override
    public ResponseHandlers getResponseHandlers() {
        return responseHandlers;
    }

    @Override
    public RequestHandlers getRequestHandlers() {
        return requestHandlers;
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

    protected void bindServer(ProfileSettings profileSettings) {
        System.out.println("INSIDE BIND SERVER");
        // Bind and start to accept incoming connections.
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
            this.boundAddress = boundTransportAddress;
        } else {
            profileBoundAddresses.put(profileSettings.profileName, boundTransportAddress);
        }
    }

    protected void onServerException(TcpServerChannel channel, Exception e) {
        if (e instanceof BindException) {
            logger.debug(() -> new ParameterizedMessage("bind exception from server channel caught on transport layer [{}]", channel), e);
        } else {
            logger.error(new ParameterizedMessage("exception from server channel caught on transport layer [{}]", channel), e);
        }
    }

    protected void serverAcceptedChannel(TcpChannel channel) {
        boolean addedOnThisCall = acceptedChannels.add(channel);
        assert addedOnThisCall : "Channel should only be added to accepted channel set once";
        // Mark the channel init time
//        channel.getChannelStats().markAccessed(threadPool.relativeTimeInMillis());
        channel.addCloseListener(ActionListener.wrap(() -> acceptedChannels.remove(channel)));
        logger.trace(() -> new ParameterizedMessage("Tcp transport channel accepted: {}", channel));
    }

    /**
     * Binds to the given {@link InetSocketAddress}
     *
     * @param name    the profile name
     * @param address the address to bind to
     */
    protected abstract TcpServerChannel bind(String name, InetSocketAddress address) throws IOException;


    private BoundTransportAddress createBoundTransportAddress(ProfileSettings profileSettings, List<InetSocketAddress> boundAddresses) {
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

    // package private for tests
    static int resolvePublishPort(ProfileSettings profileSettings, List<InetSocketAddress> boundAddresses, InetAddress publishInetAddress) {
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

    /**
     * Returns all profile settings for the given settings object
     */
    public static Set<ProfileSettings> getProfileSettings(Settings settings) {
        HashSet<ProfileSettings> profiles = new HashSet<>();
        boolean isDefaultSet = false;
        for (String profile : settings.getGroups("transport.profiles.", true).keySet()) {
            profiles.add(new ProfileSettings(settings, profile));
            if (TransportSettings.DEFAULT_PROFILE.equals(profile)) {
                isDefaultSet = true;
            }
        }
        if (isDefaultSet == false) {
            profiles.add(new ProfileSettings(settings, TransportSettings.DEFAULT_PROFILE));
        }
        return Collections.unmodifiableSet(profiles);
    }

    public ThreadPool getThreadPool() {
        return threadPool;
    }

    public Version getVersion() {
        return version;
    }

    public void onException(TcpChannel channel, Exception e) {
        handleException(channel, e, lifecycle);
    }

    // exposed for tests
    static void handleException(TcpChannel channel, Exception e, Lifecycle lifecycle) {
        if (!lifecycle.started()) {
            // just close and ignore - we are already stopped and just need to make sure we release all resources
            CloseableChannel.closeChannel(channel);
            return;
        }

        if (isCloseConnectionException(e)) {
            logger.debug(
                    () -> new ParameterizedMessage(
                            "close connection exception caught on transport layer [{}], disconnecting from relevant node",
                            channel
                    ),
                    e
            );
            // close the channel, which will cause a node to be disconnected if relevant
            CloseableChannel.closeChannel(channel);
        } else if (isConnectException(e)) {
            logger.debug(() -> new ParameterizedMessage("connect exception caught on transport layer [{}]", channel), e);
            // close the channel as safe measure, which will cause a node to be disconnected if relevant
            CloseableChannel.closeChannel(channel);
        } else if (e instanceof BindException) {
            logger.debug(() -> new ParameterizedMessage("bind exception caught on transport layer [{}]", channel), e);
            // close the channel as safe measure, which will cause a node to be disconnected if relevant
            CloseableChannel.closeChannel(channel);
        } else if (e instanceof CancelledKeyException) {
            logger.debug(
                    () -> new ParameterizedMessage(
                            "cancelled key exception caught on transport layer [{}], disconnecting from relevant node",
                            channel
                    ),
                    e
            );
            // close the channel as safe measure, which will cause a node to be disconnected if relevant
            CloseableChannel.closeChannel(channel);
        } else if (e instanceof org.opensearch.transport.TcpTransport.HttpRequestOnTransportException) {
            // in case we are able to return data, serialize the exception content and sent it back to the client
            if (channel.isOpen()) {
                BytesArray message = new BytesArray(e.getMessage().getBytes(StandardCharsets.UTF_8));

            }
        } else if (e instanceof StreamCorruptedException) {
            logger.warn(() -> new ParameterizedMessage("{}, [{}], closing connection", e.getMessage(), channel));
            CloseableChannel.closeChannel(channel);
        } else {
            logger.warn(() -> new ParameterizedMessage("exception caught on transport layer [{}], closing connection", channel), e);
            // close the channel, which will cause a node to be disconnected if relevant
            CloseableChannel.closeChannel(channel);
        }
    }


    /**
     * Representation of a transport profile settings for a {@code transport.profiles.$profilename.*}
     */
    public static final class ProfileSettings {
        public final String profileName;
        public final boolean tcpNoDelay;
        public final boolean tcpKeepAlive;
        public final int tcpKeepIdle;
        public final int tcpKeepInterval;
        public final int tcpKeepCount;
        public final boolean reuseAddress;
        public final ByteSizeValue sendBufferSize;
        public final ByteSizeValue receiveBufferSize;
        public final List<String> bindHosts;
        public final List<String> publishHosts;
        public final String portOrRange;
        public final int publishPort;
        public final boolean isDefaultProfile;

        public ProfileSettings(Settings settings, String profileName) {
            this.profileName = profileName;
            isDefaultProfile = TransportSettings.DEFAULT_PROFILE.equals(profileName);
            tcpKeepAlive = TransportSettings.TCP_KEEP_ALIVE_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            tcpKeepIdle = TransportSettings.TCP_KEEP_IDLE_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            tcpKeepInterval = TransportSettings.TCP_KEEP_INTERVAL_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            tcpKeepCount = TransportSettings.TCP_KEEP_COUNT_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            tcpNoDelay = TransportSettings.TCP_NO_DELAY_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            reuseAddress = TransportSettings.TCP_REUSE_ADDRESS_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            sendBufferSize = TransportSettings.TCP_SEND_BUFFER_SIZE_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            receiveBufferSize = TransportSettings.TCP_RECEIVE_BUFFER_SIZE_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            List<String> profileBindHosts = TransportSettings.BIND_HOST_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            bindHosts = (profileBindHosts.isEmpty() ? NetworkService.GLOBAL_NETWORK_BIND_HOST_SETTING.get(settings) : profileBindHosts);
            publishHosts = TransportSettings.PUBLISH_HOST_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            Setting<String> concretePort = TransportSettings.PORT_PROFILE.getConcreteSettingForNamespace(profileName);
            if (concretePort.exists(settings) == false && isDefaultProfile == false) {
                throw new IllegalStateException("profile [" + profileName + "] has no port configured");
            }
            portOrRange = TransportSettings.PORT_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
            publishPort = isDefaultProfile
                    ? TransportSettings.PUBLISH_PORT.get(settings)
                    : TransportSettings.PUBLISH_PORT_PROFILE.getConcreteSettingForNamespace(profileName).get(settings);
        }
    }



}
