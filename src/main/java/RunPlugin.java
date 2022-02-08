import org.opensearch.common.settings.Settings;
import org.opensearch.transport.TcpTransport;
import org.opensearch.transport.TransportSettings;

import java.util.Set;

import static org.opensearch.transport.TcpTransport.getProfileSettings;

public class RunPlugin {

    protected static Set<TcpTransport.ProfileSettings> profileSettings = getProfileSettings(Settings.builder().put("transport.profiles.test.port", "5555").put("transport.profiles.default.port", "3333").build());;
    private static SharedGroupFactory sharedGroupFactory;
    private static final Settings settings = Settings.builder()
            .put("node.name", "NettySizeHeaderFrameDecoderTests")
            .put(TransportSettings.BIND_HOST.getKey(), "127.0.0.1")
            .put(TransportSettings.PORT.getKey(), "0")
            .build();


    public static void main(String[] args) {
        IndependentPlugin independentPlugin = new IndependentPlugin(new SharedGroupFactory(settings));

        for (TcpTransport.ProfileSettings profileSettings : profileSettings) {
            independentPlugin.createServerBootstrap(profileSettings);
            independentPlugin.bindServer(profileSettings);
        }
        independentPlugin.doStart();
    }
}
