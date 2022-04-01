import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.common.component.Lifecycle;
import org.opensearch.common.network.NetworkAddress;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.TransportSettings;
import java.net.*;
import java.io.*;

import transportservice.RunPlugin;
import transportservice.netty4.Netty4Transport;
import org.opensearch.threadpool.ThreadPool;

public class TransportCommunicationIT extends OpenSearchIntegTestCase {

    private int port;
    private Settings settings;
    private final int minPort = 49152;
    private final int maxPort = 65535;
    private final String host = "127.0.0.1";
    private volatile String clientResult;

    @BeforeEach
    public void setUp() {

        // randomize port selection when creating settings
        port = getRandomPort();

        settings = Settings.builder()
            .put("node.name", "node_extension_test")
            .put(TransportSettings.BIND_HOST.getKey(), host)
            .put(TransportSettings.PORT.getKey(), port)
            .build();
    }

    @Test
    public void testThatInfosAreExposed() {

        RunPlugin runPlugin = new RunPlugin();
        ThreadPool threadPool = new TestThreadPool("test");
        Netty4Transport transport = runPlugin.getNetty4Transport(settings, threadPool);

        // start netty transport and ensure that address info is exposed
        try {
            transport.start();
            assertEquals(transport.lifecycleState(), Lifecycle.State.STARTED);

            // check bound addresses
            for (TransportAddress transportAddress : transport.boundAddress().boundAddresses()) {
                assert (transportAddress instanceof TransportAddress);
                assertEquals(host, transportAddress.getAddress());
                assertEquals(port, transportAddress.getPort());
            }

            // check publish addresses
            assert (transport.boundAddress().publishAddress() instanceof TransportAddress);
            TransportAddress publishAddress = transport.boundAddress().publishAddress();
            assertEquals(host, NetworkAddress.format(publishAddress.address().getAddress()));
            assertEquals(port, publishAddress.address().getPort());

        } finally {
            terminate(threadPool);
        }
    }

    @Test
    public void testInvalidMessageFormat() throws UnknownHostException, InterruptedException {
        Thread client = new Thread() {
            @Override
            public void run() {
                try {

                    // Connect to the server
                    Socket socket = new Socket(host, port);

                    // Create input/output stream to read/write to server
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    PrintStream out = new PrintStream(socket.getOutputStream());

                    // note : message validation is only done if message length >= 6 bytes
                    out.println("TESTT");

                    // disconnection by foreign host indicated by a read return value of -1
                    clientResult = String.valueOf(in.read());

                    // Close stream and socket connection
                    out.close();
                    socket.close();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        // start transport service and attempt tcp client connection
        startTransportandClient(settings, client);

        // expecting -1 from client attempt to read from server, indicating connection closed by host
        assertEquals("-1", clientResult);
    }

    @Test
    public void testMismatchingPort() throws UnknownHostException, InterruptedException {

        Thread client = new Thread() {
            @Override
            public void run() {
                try {
                    // test ports are in range
                    Socket socket = new Socket(host, 0);
                    socket.close();
                } catch (Exception e) {
                    clientResult = e.getMessage();
                }

            }
        };

        // start transport service and attempt client connection
        startTransportandClient(settings, client);

        // expecting server response "Connection refused"
        assertEquals("Connection refused", clientResult);
    }

    // TODO : test SDK message protocol, extension handshake recieved and acknowledged
    // @Test
    // public void testHandshakeRequestRecievedAndAcknowledged() {

    // send handshake request to SDK transport service
    // - this is done when opensearch transport attempts to connect to extension node
    // - SDK expected behavior is to recieve and validate message, and identify message as a handshake request
    // - upon identification, SDK logs handshake request recieved
    // - SDK then sends response to acknowledge handshake request
    // assert that SDK logs : [internal:tcp/handshake] received request
    // assert that SDK logs : [internal:tcp/handshake] sent response
    // }

    // TODO : test SDK message deserialization / serialization

    private int getRandomPort() {
        // generate port number within IANA suggested range for dynamic or private ports
        return (int) ((Math.random() * (maxPort - minPort)) + minPort);
    }

    private void startTransportandClient(Settings settings, Thread client) throws UnknownHostException, InterruptedException {

        // retrieve transport service
        RunPlugin runPlugin = new RunPlugin();
        TransportService transportService = runPlugin.getTransportService(settings);

        // start transport service
        runPlugin.startTransportService(transportService);
        assertEquals(transportService.lifecycleState(), Lifecycle.State.STARTED);

        // connect client server to transport service
        client.start();

        // listen for messages, set timeout to close server socket connection
        runPlugin.startActionListener(1000);

        // wait for client thread to finish execution
        client.join();
    }

}
