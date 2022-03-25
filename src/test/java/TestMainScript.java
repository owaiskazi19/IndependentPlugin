import static org.mockito.Mockito.times;
import java.net.UnknownHostException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.TransportSettings;
import transportservice.RunPlugin;

public class TestMainScript extends OpenSearchTestCase {

    private RunPlugin rp;
    private Settings settings;

    @BeforeEach
    public void setUp() throws UnknownHostException {

        this.rp = new RunPlugin();

        // initialize settings required for transport service
        this.settings = Settings.builder()
            .put("node.name", "MainScriptTests")
            .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
            .put(TransportSettings.PORT.getKey(), "0")
            .build();
    }

    // test RunPlugin getTransportService return type is transport service
    @Test
    public void testGetTransportService() throws UnknownHostException {
        assert (rp.getTransportService(settings) instanceof TransportService);
    }

    // test manager method invokes start on transport service
    @Test
    public void testTransportServiceStarted() throws UnknownHostException {

        // retrieve and mock transport service
        TransportService spy = Mockito.spy(rp.getTransportService(settings));

        // verify mocked object interaction in manager method
        rp.startTransportService(spy);
        Mockito.verify(spy, times(1)).start();
    }

    // test manager method invokes accept incoming requests on transport service
    @Test
    public void testTransportServiceAcceptedIncomingRequests() throws UnknownHostException {

        // retrieve and mock transport service
        TransportService spy = Mockito.spy(rp.getTransportService(settings));

        // verify mocked object interaction in manager method
        rp.startTransportService(spy);
        Mockito.verify(spy, times(1)).acceptIncomingRequests();
    }
}
