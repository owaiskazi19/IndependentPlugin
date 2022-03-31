package transportservice;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.opensearch.Version;
import org.opensearch.client.Client;
import org.opensearch.cluster.ClusterModule;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.network.NetworkModule;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.BoundTransportAddress;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.indices.IndicesModule;
import org.opensearch.indices.breaker.CircuitBreakerService;
import org.opensearch.indices.breaker.NoneCircuitBreakerService;
import org.opensearch.search.SearchModule;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.*;
import transportservice.netty4.Netty4Transport;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.opensearch.common.UUIDs.randomBase64UUID;

public class RunPlugin {



    private static final Settings settings = Settings.builder()
        .put("node.name", "node_extension")
        .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
        .put(TransportSettings.PORT.getKey(), "4532")
        .build();
    private NodeEnvironment nodeEnvironment;
    private static final Logger logger = LogManager.getLogger(RunPlugin.class);
//    private final Environment environment;
    public static final TransportInterceptor NOOP_TRANSPORT_INTERCEPTOR = new TransportInterceptor() {
    };
    private static final String CLIENT_TYPE = "node";
    private final SetOnce<DiscoveryNode> discoveryNode = new SetOnce<>();
//    final Environment initialEnvironment;
    private LocalNodeFactory localNodeFactory = new LocalNodeFactory(settings, "5");;
    ThreadPool threadPool = new ThreadPool(settings);
    NetworkService networkService = new NetworkService(Collections.emptyList());
    PageCacheRecycler pageCacheRecycler = new PageCacheRecycler(settings);
    IndicesModule indicesModule = new IndicesModule(Collections.emptyList());
    SearchModule searchModule = new SearchModule(settings, Collections.emptyList());
    List<NamedWriteableRegistry.Entry> namedWriteables = Stream.of(
        NetworkModule.getNamedWriteables().stream(),
        indicesModule.getNamedWriteables().stream(),
        searchModule.getNamedWriteables().stream(),
        null,
        ClusterModule.getNamedWriteables().stream()
    ).flatMap(Function.identity()).collect(Collectors.toList());
    final NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(namedWriteables);
    final CircuitBreakerService circuitBreakerService = new NoneCircuitBreakerService();

//    public RunPlugin(NodeEnvironment nodeEnvironment, Environment environment, Environment initialEnvironment, LocalNodeFactory localNodeFactory) {
//        this.nodeEnvironment = nodeEnvironment;
//        this.environment = environment;
//        this.initialEnvironment = initialEnvironment;
//        this.localNodeFactory = localNodeFactory;
//    }

    private void startTransport() throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        File file = new File(classLoader.getResource("extension.yml").getFile());
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ExtensionSettings extensionSettings = objectMapper.readValue(file, ExtensionSettings.class);
        System.out.println(extensionSettings.getNodeName());
        Netty4Transport transport = new Netty4Transport(
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
//        Settings tmpSettings = Settings.builder()
//                .put(initialEnvironment.settings())
//                .put(Client.CLIENT_TYPE_SETTING_S.getKey(), CLIENT_TYPE)
//                .build();
//
//
//        nodeEnvironment = new NodeEnvironment(tmpSettings, environment);
        //localNodeFactory = new LocalNodeFactory(settings, "5");
//        final Function<BoundTransportAddress, DiscoveryNode> boundTransportAddressDiscoveryNodeFunction = address -> {
//            discoveryNode.set(new DiscoveryNode("node", address.publishAddress(), emptyMap(), emptySet(), Version.CURRENT));
//            return discoveryNode.get();
//        };

        final TransportService transportService = new TransportService(
            settings,
            transport,
            threadPool,
            NOOP_TRANSPORT_INTERCEPTOR,
            boundAddress -> DiscoveryNode.createLocal(
                    Settings.builder().put("node.name", "node_extension").build(),
                    boundAddress.publishAddress(),
                    randomBase64UUID()
            ),
            null,
            emptySet(),
            connectionManager
        );

        transportService.start();
        transportService.acceptIncomingRequests();
        final ActionListener actionListener = new ActionListener();
        actionListener.runActionListener(true, settings);
    }

    public static void main(String[] args) throws IOException {
        RunPlugin runPlugin = new RunPlugin();
        runPlugin.startTransport();
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
