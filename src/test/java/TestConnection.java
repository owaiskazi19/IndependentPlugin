//import org.hamcrest.MatcherAssert;
//import org.junit.After;
//import org.junit.Before;
//import org.junit.jupiter.api.Test;
//import org.opensearch.Version;
//import org.opensearch.cluster.node.DiscoveryNode;
//import org.opensearch.common.Nullable;
//import org.opensearch.common.io.stream.StreamInput;
//import org.opensearch.common.settings.ClusterSettings;
//import org.opensearch.common.settings.Setting;
//import org.opensearch.common.settings.Settings;
//import org.opensearch.core.internal.io.IOUtils;
//import org.opensearch.node.Node;
//import org.opensearch.test.OpenSearchTestCase;
//import org.opensearch.test.transport.MockTransportService;
//import org.opensearch.threadpool.TestThreadPool;
//import org.opensearch.threadpool.ThreadPool;
//import org.opensearch.transport.*;
//import transportservice.transport.Transport;
//
//import static org.hamcrest.Matchers.equalTo;
//import static org.hamcrest.Matchers.notNullValue;
//import static org.hamcrest.MatcherAssert.assertThat;
//import static transportservice.TransportService.NOOP_TRANSPORT_INTERCEPTOR;
//import transportservice.TcpTransport;
//
//import java.io.IOException;
//import java.util.Collections;
//import java.util.Set;
//
//
//public abstract class TestConnection extends OpenSearchTestCase {
//    protected volatile MockTransportService serviceA;
//    private static final Version CURRENT_VERSION = Version.fromString(String.valueOf(Version.CURRENT.major) + ".0.0");
//    protected static final Version version0 = CURRENT_VERSION.minimumCompatibilityVersion();
//    protected ClusterSettings clusterSettingsA;
//    protected ThreadPool threadPool;
//    protected volatile DiscoveryNode nodeA;
//
//    @Override
//    @Before
//    public void setUp() throws Exception {
//        super.setUp();
//
//        threadPool = new TestThreadPool(getClass().getName());
//        final Settings.Builder connectionSettingsBuilder = Settings.builder()
//                .put(TransportSettings.CONNECTIONS_PER_NODE_RECOVERY.getKey(), 1)
//                .put(TransportSettings.CONNECTIONS_PER_NODE_BULK.getKey(), 1)
//                .put(TransportSettings.CONNECTIONS_PER_NODE_REG.getKey(), 2)
//                .put(TransportSettings.CONNECTIONS_PER_NODE_STATE.getKey(), 1)
//                .put(TransportSettings.CONNECTIONS_PER_NODE_PING.getKey(), 1);
//
//        connectionSettingsBuilder.put(TransportSettings.TCP_KEEP_ALIVE.getKey(), randomBoolean());
//        if (randomBoolean()) {
//            connectionSettingsBuilder.put(TransportSettings.TCP_KEEP_IDLE.getKey(), randomIntBetween(1, 300));
//        }
//        if (randomBoolean()) {
//            connectionSettingsBuilder.put(TransportSettings.TCP_KEEP_INTERVAL.getKey(), randomIntBetween(1, 300));
//        }
//        if (randomBoolean()) {
//            connectionSettingsBuilder.put(TransportSettings.TCP_KEEP_COUNT.getKey(), randomIntBetween(1, 10));
//        }
//
//        final Settings connectionSettings = connectionSettingsBuilder.build();
//        serviceA = buildService("TS_A", version0, clusterSettingsA, connectionSettings); // this one supports dynamic tracer updates
//        nodeA = serviceA.getLocalNode();
//    }
//
//    protected Set<Setting<?>> getSupportedSettings() {
//        return ClusterSettings.BUILT_IN_CLUSTER_SETTINGS;
//    }
//
//    protected abstract Transport build(Settings settings, Version version, ClusterSettings clusterSettings, boolean doHandshake);
//
//    private MockTransportService buildService(
//            final String name,
//            final Version version,
//            @Nullable ClusterSettings clusterSettings,
//            Settings settings,
//            boolean acceptRequests,
//            boolean doHandshake,
//            TransportInterceptor interceptor
//    ) {
//        Settings updatedSettings = Settings.builder()
//                .put(TransportSettings.PORT.getKey(), getPortRange())
//                .put(settings)
//                .put(Node.NODE_NAME_SETTING.getKey(), name)
//                .build();
//        if (clusterSettings == null) {
//            clusterSettings = new ClusterSettings(updatedSettings, getSupportedSettings());
//        }
//        Transport transport = build(updatedSettings, version, clusterSettings, doHandshake);
//        MockTransportService service = MockTransportService.createNewService(
//                updatedSettings,
//                (org.opensearch.transport.Transport) transport,
//                version,
//                threadPool,
//                clusterSettings,
//                Collections.emptySet(),
//                interceptor
//        );
//        service.start();
//        if (acceptRequests) {
//            service.acceptIncomingRequests();
//        }
//        return service;
//    }
//
//    private MockTransportService buildService(
//            final String name,
//            final Version version,
//            @Nullable ClusterSettings clusterSettings,
//            Settings settings,
//            boolean acceptRequests,
//            boolean doHandshake
//    ) {
//        return buildService(name, version, clusterSettings, settings, acceptRequests, doHandshake, (TransportInterceptor) NOOP_TRANSPORT_INTERCEPTOR);
//    }
//
//    protected MockTransportService buildService(final String name, final Version version, Settings settings) {
//        return buildService(name, version, null, settings);
//    }
//
//    protected MockTransportService buildService(
//            final String name,
//            final Version version,
//            ClusterSettings clusterSettings,
//            Settings settings
//    ) {
//        return buildService(name, version, clusterSettings, settings, true, true);
//    }
//
//    public void assertNoPendingHandshakes(Transport transport) {
//        if (transport instanceof TcpTransport) {
//            assertEquals(0, ((TcpTransport) transport).getNumPendingHandshakes());
//        }
//    }
//
////    @Override
////    @After
////    public void tearDown() throws Exception {
////        super.tearDown();
////        try {
////            assertNoPendingHandshakes(serviceA.getOriginalTransport());
////            assertNoPendingHandshakes(serviceB.getOriginalTransport());
////        } finally {
////            IOUtils.close(serviceA, serviceB, () -> terminate(threadPool));
////        }
////    }
//
//    @Test
//    public void testVoidMessageCompressed() {
//        try (MockTransportService serviceC = buildService("TS_C", CURRENT_VERSION, Settings.EMPTY)) {
//            serviceC.start();
//            serviceC.acceptIncomingRequests();
//
//            serviceA.registerRequestHandler(
//                    "internal:sayHello",
//                    ThreadPool.Names.GENERIC,
//                    TransportRequest.Empty::new,
//                    (request, channel, task) -> {
//                        try {
//                            channel.sendResponse(TransportResponse.Empty.INSTANCE);
//                        } catch (IOException e) {
//                            logger.error("Unexpected failure", e);
//                            fail(e.getMessage());
//                        }
//                    }
//            );
//
//            Settings settingsWithCompress = Settings.builder().put(TransportSettings.TRANSPORT_COMPRESS.getKey(), true).build();
//            ConnectionProfile connectionProfile = ConnectionProfile.buildDefaultConnectionProfile(settingsWithCompress);
//            serviceC.connectToNode(serviceA.getLocalDiscoNode(), connectionProfile);
//
//            TransportFuture<TransportResponse.Empty> res = serviceC.submitRequest(
//                    nodeA,
//                    "internal:sayHello",
//                    TransportRequest.Empty.INSTANCE,
//                    TransportRequestOptions.EMPTY,
//                    new TransportResponseHandler<TransportResponse.Empty>() {
//                        @Override
//                        public TransportResponse.Empty read(StreamInput in) {
//                            return TransportResponse.Empty.INSTANCE;
//                        }
//
//                        @Override
//                        public String executor() {
//                            return ThreadPool.Names.GENERIC;
//                        }
//
//                        @Override
//                        public void handleResponse(TransportResponse.Empty response) {}
//
//                        @Override
//                        public void handleException(TransportException exp) {
//                            logger.error("Unexpected failure", exp);
//                            fail("got exception instead of a response: " + exp.getMessage());
//                        }
//                    }
//            );
//
//            try {
//                TransportResponse.Empty message = res.get();
//                MatcherAssert.assertThat(message, notNullValue());
//            } catch (Exception e) {
//                MatcherAssert.assertThat(e.getMessage(), false, equalTo(true));
//            }
//        }
//    }
//}
