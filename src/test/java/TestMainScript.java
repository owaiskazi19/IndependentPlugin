import static org.mockito.Mockito.times;
import java.net.UnknownHostException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.TransportService;
import transportservice.RunPlugin;

public class TestMainScript extends OpenSearchTestCase {

    private RunPlugin runPlugin;
    private Settings settings;

    @BeforeEach
    public void setUp() throws UnknownHostException {

        this.runPlugin = new RunPlugin();
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
}
