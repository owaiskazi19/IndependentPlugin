package transportservice;

import org.opensearch.Version;
import org.opensearch.common.settings.Settings;
import org.opensearch.env.Environment;
import org.opensearch.node.NodeValidationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

public class Bootstrap {


    private final Thread keepAliveThread;
    private final CountDownLatch keepAliveLatch = new CountDownLatch(1);

    public static Environment newEnvironment(Settings settings) {
        return new Environment(settings, (Path)null);
    }

    private Environment createEnvironment() throws IOException {
        Path home = Files.createTempDirectory(String.valueOf(Paths.get("test")));
        return newEnvironment(
                Settings.builder()
                        .put(Environment.PATH_HOME_SETTING.getKey(), home.toAbsolutePath())
                        .put(Environment.PATH_REPO_SETTING.getKey(), home.resolve("repo").toAbsolutePath())
                        .build()
        );
    }

    final Environment environment = createEnvironment();

    Bootstrap() throws IOException {
        keepAliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    keepAliveLatch.await();
                } catch (InterruptedException e) {
                    // bail out
                }
            }
        }, "opensearch[keepAlive/" + Version.CURRENT + "]");
        keepAliveThread.setDaemon(false);
        // keep this thread alive (non daemon thread) until we shutdown
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                keepAliveLatch.countDown();
            }
        });


//        node = new IndependentPlugin(environment) {
//            @Override
//            protected void validateNodeBeforeAcceptingRequests(
//                    final BootstrapContext context,
//                    final BoundTransportAddress boundTransportAddress,
//                    List<BootstrapCheck> checks
//            ) throws NodeValidationException {
//
//            }
//        };
    }



}
