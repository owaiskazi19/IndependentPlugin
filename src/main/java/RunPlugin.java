import org.opensearch.common.settings.Settings;
import org.opensearch.transport.TcpTransport;

import java.util.Set;

import static org.opensearch.transport.TcpTransport.getProfileSettings;

public class RunPlugin {

    protected static Set<TcpTransport.ProfileSettings> profileSettings = null;
    private static SharedGroupFactory sharedGroupFactory;
    private static volatile SharedGroupFactory.SharedGroup sharedGroup;

    public RunPlugin() {
        this.profileSettings = getProfileSettings(Settings.builder().put("transport.profiles.test.port", "5555").put("transport.profiles.default.port", "3333").build());
    }


    public static void main(String[] args) {
        IndependentPlugin independentPlugin = new IndependentPlugin();
        sharedGroup = sharedGroupFactory.getTransportGroup();
        for (TcpTransport.ProfileSettings profileSettings : profileSettings) {
            independentPlugin.createServerBootstrap(profileSettings, sharedGroup);
            independentPlugin.bindServer(profileSettings);
        }
        independentPlugin.doStart();
    }
}
