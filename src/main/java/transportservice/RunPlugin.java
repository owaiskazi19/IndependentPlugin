package transportservice;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterModule;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.network.NetworkModule;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.indices.IndicesModule;
import org.opensearch.indices.breaker.CircuitBreakerService;
import org.opensearch.indices.breaker.NoneCircuitBreakerService;
import org.opensearch.search.SearchModule;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.*;
import transportservice.netty4.Netty4Transport;

import java.net.*;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptySet;

public class RunPlugin {

    private static final Settings settings = Settings.builder()
        .put("node.name", "node_extension")
        .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
        .put(TransportSettings.PORT.getKey(), "9301")
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

    private void startTransport() throws UnknownHostException {
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
            connectionManager,
            emptySet(),
            true
        );

        transportService.start();
        transportService.acceptIncomingRequests();
        final ActionListener actionListener = new ActionListener();
        actionListener.runActionListener(true);
    }

    public static void main(String[] args) throws UnknownHostException {
        RunPlugin runPlugin = new RunPlugin();
        runPlugin.startTransport();
    }

}
