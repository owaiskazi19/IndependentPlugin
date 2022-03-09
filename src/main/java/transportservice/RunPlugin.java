package transportservice;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.opensearch.Version;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.BoundTransportAddress;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.indices.breaker.CircuitBreakerService;
import org.opensearch.indices.breaker.NoneCircuitBreakerService;
import org.opensearch.search.SearchModule;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.*;
import transportservice.netty4.Netty;
import transportservice.transport.ClusterConnectionManager;
import transportservice.transport.ConnectionManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.util.Collections;
import java.util.function.Function;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

public class RunPlugin {

    private static final Settings settings = Settings.builder()
            .put("node.name", "NettySizeHeaderFrameDecoderTests")
            .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
            .put(TransportSettings.PORT.getKey(), "0")
            .build();
    private static final Logger logger = LogManager.getLogger(RunPlugin.class);
    private static LocalNodeFactory localNodeFactory = null;
    public static final TransportInterceptor NOOP_TRANSPORT_INTERCEPTOR = new TransportInterceptor() {
    };
    private static final Version CURRENT_VERSION = Version.fromString(String.valueOf(Version.CURRENT.major) + ".0.0");
    protected static final Version version0 = CURRENT_VERSION.minimumCompatibilityVersion();

    public RunPlugin(LocalNodeFactory localNodeFactory) {
        // DUMMY VALUE
        this.localNodeFactory =  new LocalNodeFactory(settings, "5");
    }

    @SuppressForbidden(reason = "need local ephemeral port")
    protected static InetSocketAddress getLocalEphemeral() throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getLocalHost(), 0);
    }

    public static void main(String[] args) {

        ThreadPool threadPool = new TestThreadPool("test");
        NetworkService networkService = new NetworkService(Collections.emptyList());
        PageCacheRecycler pageCacheRecycler = new PageCacheRecycler(settings);
        NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(new SearchModule(Settings.EMPTY, Collections.emptyList()).getNamedWriteables());
        final CircuitBreakerService circuitBreakerService = new NoneCircuitBreakerService();


        Netty transport = new Netty(
                settings,
                Version.CURRENT,
                threadPool,
                networkService,
                pageCacheRecycler,
                namedWriteableRegistry,
                circuitBreakerService,
                new SharedGroupFactory(settings)
        );

        final ConnectionManager connectionManager = new ClusterConnectionManager(settings, transport);



        final TransportService transportService = new TransportService(
                transport,
                connectionManager,
                transport.getResponseHandlers(),
                threadPool,
                localNodeFactory,
                NOOP_TRANSPORT_INTERCEPTOR
        );

//        connectionManager.addListener(transportService);

        transportService.start();
        transportService.acceptIncomingRequests();

        // Action Listener

        boolean flag = true;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(getLocalEphemeral(), 1);
            socket.setReuseAddress(true);
            DiscoveryNode dummy = new DiscoveryNode(
                    "TEST",
                    new TransportAddress(socket.getInetAddress(), socket.getLocalPort()),
                    emptyMap(),
                    emptySet(),
                    version0
            );
            Thread t = new Thread() {
                @Override
                public void run() {
                    try (Socket accept = socket.accept()) {
                        if (flag) { // sometimes wait until the other side sends the message
                            accept.getInputStream().read();
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
            t.start();
            ConnectionProfile.Builder builder = new ConnectionProfile.Builder();
            builder.addConnections(
                    1,
                    TransportRequestOptions.Type.BULK,
                    TransportRequestOptions.Type.PING,
                    TransportRequestOptions.Type.RECOVERY,
                    TransportRequestOptions.Type.REG,
                    TransportRequestOptions.Type.STATE
            );
            builder.setHandshakeTimeout(TimeValue.timeValueHours(1));
            //transportService.connectToNode(dummy, builder.build());
            t.join();

        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static class LocalNodeFactory implements Function<BoundTransportAddress, DiscoveryNode> {
        private final SetOnce<DiscoveryNode> localNode = new SetOnce<>();
        private final String persistentNodeId;
        private final Settings settings;

        private LocalNodeFactory(Settings settings, String persistentNodeId) {
            this.persistentNodeId = persistentNodeId;
            this.settings = settings;
        }

        @Override
        public DiscoveryNode apply(BoundTransportAddress boundTransportAddress) {
            localNode.set(DiscoveryNode.createLocal(settings, boundTransportAddress.publishAddress(), persistentNodeId));
            return localNode.get();
        }

        DiscoveryNode getNode() {
            assert localNode.get() != null;
            return localNode.get();
        }
    }

}
