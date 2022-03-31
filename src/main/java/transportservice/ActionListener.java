package transportservice;

import org.opensearch.Version;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.transport.ConnectionProfile;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.TransportSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

public class ActionListener {
    private final Version CURRENT_VERSION = Version.fromString(String.valueOf(Version.CURRENT.major) + ".0.0");
    protected final Version version0 = CURRENT_VERSION.minimumCompatibilityVersion();

    @SuppressForbidden(reason = "need local ephemeral port")
    protected static InetSocketAddress getLocalEphemeral() throws UnknownHostException {
        return new InetSocketAddress(InetAddress.getLocalHost(), 0);
    }

    public void runActionListener(boolean flag, Settings settings) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(getLocalEphemeral(), 1);
            socket.setReuseAddress(true);
//            DiscoveryNode dummy = new DiscoveryNode(
//                    "TEST",
//                    new TransportAddress(socket.getInetAddress(), socket.getLocalPort()),
//                    emptyMap(),
//                    emptySet(),
//                    version0
//            );


            Thread t = new Thread() {
                @Override
                public void run() {
                    try (Socket accept = socket.accept()) {
                        if (flag) { // sometimes wait until the other side sends the message
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
            builder.setConnectTimeout(TransportSettings.CONNECT_TIMEOUT.get(settings));
            builder.setHandshakeTimeout(TransportSettings.CONNECT_TIMEOUT.get(settings));
            builder.setPingInterval(TransportSettings.PING_SCHEDULE.get(settings));
            builder.setCompressionEnabled(TransportSettings.TRANSPORT_COMPRESS.get(settings));
//            builder.setHandshakeTimeout(TransportSettings.CONNECT_TIMEOUT.get(settings));
            //final ConnectionProfile profile = builder.build();
            //transportService.openConnection(dummy, profile);
            //transportService.connectToNode(dummy, builder.build());
            t.join();

        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
