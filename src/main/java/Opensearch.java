import org.opensearch.node.NodeValidationException;

import java.io.IOException;

public class Opensearch {
    private static volatile Bootstrap INSTANCE;

    public static void main(String[] args) throws NodeValidationException, IOException {
        INSTANCE = new Bootstrap();
        INSTANCE.start();
    }
}
