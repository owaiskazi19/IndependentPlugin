import org.junit.jupiter.api.Test;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.transport.ConnectTransportException;
import org.opensearch.transport.ConnectionProfile;
import org.opensearch.transport.TransportRequestOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestConnection {
    private static final Version CURRENT_VERSION = Version.fromString(String.valueOf(Version.CURRENT.major) + ".0.0");
    protected static final Version version0 = CURRENT_VERSION.minimumCompatibilityVersion();

    @Test
    public void testTcpHandshakeConnectionReset() throws IOException, InterruptedException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(getLocalEphemeral(), 1);
            socket.setReuseAddress(true);
            DiscoveryNode dummy = new DiscoveryNode(
                    "TEST",
                    new TransportAddress(socket.getInetAddress(), socket.getLocalPort()),
                    emptyMap(),
                    emptySet(),
                    version0
            );
            Thread t = new Thread() {
                @Override
                public void run() {
                    try (Socket accept = socket.accept()) {
                        if (randomBoolean()) { // sometimes wait until the other side sends the message
                            accept.getInputStream().read();
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
            t.start();
            ConnectionProfile.Builder builder = new ConnectionProfile.Builder();
            builder.addConnections(
                    1,
                    TransportRequestOptions.Type.BULK,
                    TransportRequestOptions.Type.PING,
                    TransportRequestOptions.Type.RECOVERY,
                    TransportRequestOptions.Type.REG,
                    TransportRequestOptions.Type.STATE
            );
            builder.setHandshakeTimeout(TimeValue.timeValueHours(1));
            ConnectTransportException ex = assertThrows(
                    ConnectTransportException.class,
                    () -> connectToNode(dummy, builder.build())
            );
            assertEquals(ex.getMessage(), "[][" + dummy.getAddress() + "] general node connection failure");
            //assertThat(ex.getCause().getMessage(), startsWith("handshake failed"));
            t.join();
            t.stop();
        }
    }

    @SuppressForbidden(reason = "need local ephemeral port")
    protected InetSocketAddress getLocalEphemeral() throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getLocalHost(), 0);
    }

    public static boolean randomBoolean() {
        return true;
    }

    public void connectToNode(DiscoveryNode node, ConnectionProfile connectionProfile) {
        PlainActionFuture.get((fut) -> {
            this.connectToNode(node, connectionProfile, ActionListener.map(fut, (x) -> {
                return null;
            }));
        });
    }

    private <Response> void connectToNode(DiscoveryNode node, ConnectionProfile connectionProfile, ActionListener<Response> map) {
    }

}
