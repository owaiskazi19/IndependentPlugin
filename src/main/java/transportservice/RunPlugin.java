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
        .put("node.name", "NettySizeHeaderFrameDecoderTests")
        .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
        .put(TransportSettings.PORT.getKey(), "9301")
        .build();
    private static final Logger logger = LogManager.getLogger(RunPlugin.class);
    public static final TransportInterceptor NOOP_TRANSPORT_INTERCEPTOR = new TransportInterceptor() {
    };

    // method : build netty transport
    public Netty4Transport getNetty4Transport(Settings settings, ThreadPool threadPool) {

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

        // create netty
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

        return transport;
    }

    // method : set up transport service
    public TransportService getTransportService(Settings settings) throws UnknownHostException {

        ThreadPool threadPool = new ThreadPool(settings);

        // create netty transport
        final Netty4Transport transport = getNetty4Transport(settings, threadPool);

        // create ConnectionManager
        final ConnectionManager connectionManager = new ClusterConnectionManager(settings, transport);

        // create transport service
        final TransportService transportService = new TransportService(
            settings,
            transport,
            threadPool,
            NOOP_TRANSPORT_INTERCEPTOR,
            connectionManager,
            emptySet(),
            true
        );

        return transportService;
    }

    // manager method for transport service
    public void startTransportService(TransportService transportService) {

        // start transport service and accept incoming requests
        transportService.start();
        transportService.acceptIncomingRequests();
    }

    // manager method for action listener
    public void startActionListener() {
        final ActionListener actionListener = new ActionListener();
        actionListener.runActionListener(true);
    }

    public static void main(String[] args) throws UnknownHostException {

        // start transport service and action listener
        RunPlugin runPlugin = new RunPlugin();

        // configure and retrieve transport service with settings
        TransportService transportService = runPlugin.getTransportService(settings);

        // start transport service and action listener
        runPlugin.startTransportService(transportService);
        runPlugin.startActionListener();

    }

}
