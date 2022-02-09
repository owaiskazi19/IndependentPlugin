package transportservice;

import org.opensearch.Version;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.indices.breaker.CircuitBreakerService;
import org.opensearch.indices.breaker.NoneCircuitBreakerService;
import org.opensearch.search.SearchModule;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.ClusterConnectionManager;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportInterceptor;
import org.opensearch.transport.TransportSettings;
import transportservice.netty4.Netty;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class RunPlugin {

    private static final Settings settings = Settings.builder()
            .put("node.name", "NettySizeHeaderFrameDecoderTests")
            .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
            .put(TransportSettings.PORT.getKey(), "0")
            .build();

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

        final TransportService transportService = new TransportService(
                transport,
                new ClusterConnectionManager(settings, transport),
                transport.getResponseHandlers(),
                threadPool
        );

        transportService.start();

    }
}
