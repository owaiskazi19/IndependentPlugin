//package transportservice.netty4;
//
//import org.apache.lucene.util.SetOnce;
//import org.opensearch.Version;
//import org.opensearch.common.io.stream.NamedWriteableRegistry;
//import org.opensearch.common.network.NetworkService;
//import org.opensearch.common.settings.Settings;
//import org.opensearch.common.util.PageCacheRecycler;
//import org.opensearch.indices.breaker.CircuitBreakerService;
//import org.opensearch.threadpool.ThreadPool;
//import transportservice.SharedGroupFactory;
//
//import java.util.Collections;
//import java.util.Map;
//
//public class Netty4Plugin {
//
//    public static final String NETTY_TRANSPORT_NAME = "netty4";
//    private final SetOnce<SharedGroupFactory> groupFactory = new SetOnce<>();
//
//    public Map<String, Object> getTransports(
//            Settings settings,
//            ThreadPool threadPool,
//            PageCacheRecycler pageCacheRecycler,
//            CircuitBreakerService circuitBreakerService,
//            NamedWriteableRegistry namedWriteableRegistry,
//            NetworkService networkService
//    ) {
//        return Collections.singletonMap(
//                NETTY_TRANSPORT_NAME,
//                () -> new Netty(
//                        settings,
//                        Version.CURRENT,
//                        threadPool,
//                        networkService,
//                        pageCacheRecycler,
//                        namedWriteableRegistry,
//                        circuitBreakerService,
//                        getSharedGroupFactory(settings)
//                )
//        );
//    }
//
//    private SharedGroupFactory getSharedGroupFactory(Settings settings) {
//        SharedGroupFactory groupFactory = this.groupFactory.get();
//        if (groupFactory != null) {
//            assert groupFactory.getSettings().equals(settings) : "Different settings than originally provided";
//            return groupFactory;
//        } else {
//            this.groupFactory.set(new SharedGroupFactory(settings));
//            return this.groupFactory.get();
//        }
//    }
//
//
//}
