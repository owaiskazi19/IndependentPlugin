package transportservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterModule;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.network.NetworkModule;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.discovery.PluginRequest;
import org.opensearch.discovery.PluginResponse;
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

import static java.util.Collections.emptySet;
import static org.opensearch.common.UUIDs.randomBase64UUID;

public class RunPlugin {

    public static final String REQUEST_EXTENSION_ACTION_NAME = "internal:discovery/extensions";

    private final ExtensionSettings extensionSettings = getExtensionSettings();
    private final Settings settings = Settings.builder()
        .put("node.name", extensionSettings.getNodename())
        .put(TransportSettings.BIND_HOST.getKey(), extensionSettings.getHostaddress())
        .put(TransportSettings.PORT.getKey(), extensionSettings.getHostport())
        .build();
    private static final Logger logger = LogManager.getLogger(RunPlugin.class);
    public static final TransportInterceptor NOOP_TRANSPORT_INTERCEPTOR = new TransportInterceptor() {
    };
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

    public RunPlugin() throws IOException {}

    public ExtensionSettings getExtensionSettings() throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        File file = new File(classLoader.getResource("extension.yml").getFile());
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        ExtensionSettings extensionSettings = objectMapper.readValue(file, ExtensionSettings.class);
        return extensionSettings;
    }

    private void startTransport() {
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

        final TransportService transportService = new TransportService(
            settings,
            transport,
            threadPool,
            NOOP_TRANSPORT_INTERCEPTOR,
            boundAddress -> DiscoveryNode.createLocal(
                Settings.builder().put("node.name", extensionSettings.getNodename()).build(),
                boundAddress.publishAddress(),
                randomBase64UUID()
            ),
            null,
            emptySet(),
            connectionManager
        );

        transportService.start();
        transportService.acceptIncomingRequests();
        transportService.registerRequestHandler(
            REQUEST_EXTENSION_ACTION_NAME,
            ThreadPool.Names.GENERIC,
            false,
            false,
            PluginRequest::new,
            (request, channel, task) -> channel.sendResponse(handlePluginsRequest(request))
        );
        final ActionListener actionListener = new ActionListener();
        actionListener.runActionListener(true);
    }

    PluginResponse handlePluginsRequest(PluginRequest pluginRequest) {
        logger.info("Handling Plugins Request");
        PluginResponse pluginResponse = new PluginResponse("RealExtension");
        return pluginResponse;
    }

    public static void main(String[] args) throws IOException {
        RunPlugin runPlugin = new RunPlugin();
        runPlugin.startTransport();
    }

}
