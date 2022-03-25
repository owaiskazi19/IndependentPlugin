import static org.mockito.Mockito.times;
import java.net.UnknownHostException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opensearch.common.component.Lifecycle;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.TransportSettings;

import transportservice.RunPlugin;
import transportservice.netty4.Netty4Transport;

public class TestMainScript extends OpenSearchTestCase {

    private RunPlugin runPlugin;
    private Settings settings;

    @BeforeEach
    public void setUp() throws UnknownHostException {

        this.runPlugin = new RunPlugin();

        // dummy settings
        this.settings = Settings.builder().put("node.name", "MainScriptTests").build();
    }

    // test RunPlugin getTransportService return type is transport service
    @Test
    public void testGetTransportService() throws UnknownHostException {
        assert (runPlugin.getTransportService(settings) instanceof TransportService);
    }

    // test manager method invokes start on transport service
    @Test
    public void testTransportServiceStarted() throws UnknownHostException {

        // retrieve and mock transport service
        TransportService transportService = Mockito.spy(runPlugin.getTransportService(settings));

        // verify mocked object interaction in manager method
        runPlugin.startTransportService(transportService);
        Mockito.verify(transportService, times(1)).start();
    }

    // test manager method invokes accept incoming requests on transport service
    @Test
    public void testTransportServiceAcceptedIncomingRequests() throws UnknownHostException {

        // retrieve and mock transport service
        TransportService transportService = Mockito.spy(runPlugin.getTransportService(settings));

        // verify mocked object interaction in manager method
        runPlugin.startTransportService(transportService);
        Mockito.verify(transportService, times(1)).acceptIncomingRequests();
    }

    // test Netty can bind to multiple ports, default and additional client
    @Test
    public void testNettyCanBindToMultiplePorts() throws Exception {

        Settings nettySettings = Settings.builder()
            .put("node.name", "netty_test")
            .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
            .put("transport.profiles.default.port", 0)
            .put("transport.profiles.client1.port", 0)
            .build();

        ThreadPool threadPool = new TestThreadPool("test");

        try (Netty4Transport transport = startNettyTransport(runPlugin.getNetty(nettySettings, threadPool))) {
            assertEquals(1, transport.profileBoundAddresses().size());
            assertEquals(1, transport.boundAddress().boundAddresses().length);
        } finally {
            terminate(threadPool);
        }
    }

    // test that default profile inherits from standard settings
    @Test
    public void testDefaultProfileInheritsFomStandardSettings() throws Exception {

        // omit transport.profiles.default.port setting to determine if default port is automatically set
        Settings nettySettings = Settings.builder()
            .put("node.name", "netty_test")
            .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
            .put("transport.profiles.client1.port", 0)
            .build();

        ThreadPool threadPool = new TestThreadPool("test");

        try (Netty4Transport transport = startNettyTransport(runPlugin.getNetty(nettySettings, threadPool))) {
            assertEquals(1, transport.profileBoundAddresses().size());
            assertEquals(1, transport.boundAddress().boundAddresses().length);
        } finally {
            terminate(threadPool);
        }
    }

    // test profile without port settings fails
    public void testThatProfileWithoutPortFails() throws Exception {

        // dummy settings without port for profile client1
        Settings nettySettings = Settings.builder()
            .put("node.name", "netty_test")
            .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
            .put("transport.profiles.no_port.foo", "bar")
            .build();

        ThreadPool threadPool = new TestThreadPool("test");

        try {
            // attempt creating netty object with invalid settings
            IllegalStateException ex = expectThrows(
                IllegalStateException.class,
                () -> startNettyTransport(runPlugin.getNetty(nettySettings, threadPool))
            );
            assertEquals("profile [no_port] has no port configured", ex.getMessage());
        } finally {
            terminate(threadPool);
        }
    }

    // test default progile port overrides general config
    public void testDefaultProfilePortOverridesGeneralConfiguration() throws Exception{
        Settings nettySettings = Settings.builder()
            .put("node.name", "netty_test")
            .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
            .put(TransportSettings.PORT.getKey(), "22") // SSH port will not bind
            .put("transport.profiles.default.port", 0) // default port configuration will overrite
            .build();

        ThreadPool threadPool = new TestThreadPool("test");

        try (Netty4Transport transport = startNettyTransport(runPlugin.getNetty(nettySettings, threadPool))) {
            assertEquals(0, transport.profileBoundAddresses().size());
            assertEquals(1, transport.boundAddress().boundAddresses().length);
        } finally {
            terminate(threadPool);
        }
    }


    // helper method to ensure netty transport was started
    private Netty4Transport startNettyTransport(Netty4Transport transport) {
        transport.start();
        assertEquals(transport.lifecycleState(), Lifecycle.State.STARTED);
        return transport;
    }

}
