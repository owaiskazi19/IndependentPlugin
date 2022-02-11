package transportservice;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.common.component.AbstractLifecycleComponent;
import org.opensearch.common.transport.BoundTransportAddress;
import org.opensearch.common.util.concurrent.AbstractRunnable;
import org.opensearch.core.internal.io.IOUtils;
import org.opensearch.node.ReportingService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class TransportService extends AbstractLifecycleComponent
        implements
        ReportingService<TransportInfo>,
        TransportMessageListener,
        TransportConnectionListener {

    protected final Transport transport;
    protected final ConnectionManager connectionManager;
    private static final Logger logger = LogManager.getLogger(TransportService.class);
    private final Transport.ResponseHandlers responseHandlers;
    protected final ThreadPool threadPool;

    public TransportService(Transport transport, ConnectionManager connectionManager, Transport.ResponseHandlers responseHandlers, ThreadPool threadPool) {
        this.transport = transport;
        this.connectionManager = connectionManager;
        this.responseHandlers = responseHandlers;
        this.threadPool = threadPool;
    }

    @Override
    protected void doStart() {
        transport.setMessageListener(this);
        connectionManager.addListener(this);
        transport.start();
        if (transport.boundAddress() != null) {
            logger.info("{}", transport.boundAddress());
            for (Map.Entry<String, BoundTransportAddress> entry : transport.profileBoundAddresses().entrySet()) {
                logger.info("profile [{}]: {}", entry.getKey(), entry.getValue());
            }
        }

    }

    private ExecutorService getExecutorService() {
        return threadPool.generic();
    }

    @Override
    protected void doStop() {
        try {
            IOUtils.close(connectionManager, null, transport::stop);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            // in case the transport is not connected to our local node (thus cleaned on node disconnect)
            // make sure to clean any leftover on going handles
            for (final Transport.ResponseContext holderToNotify : responseHandlers.prune(h -> true)) {
                // callback that an exception happened, but on a different thread since we don't
                // want handlers to worry about stack overflows
                getExecutorService().execute(new AbstractRunnable() {
                    @Override
                    public void onRejection(Exception e) {
                        // if we get rejected during node shutdown we don't wanna bubble it up
                        logger.debug(
                                () -> new ParameterizedMessage(
                                        "failed to notify response handler on rejection, action: {}",
                                        holderToNotify.action()
                                ),
                                e
                        );
                    }

                    @Override
                    public void onFailure(Exception e) {
                        logger.warn(
                                () -> new ParameterizedMessage(
                                        "failed to notify response handler on exception, action: {}",
                                        holderToNotify.action()
                                ),
                                e
                        );
                    }

                    @Override
                    public void doRun() {
                        TransportException ex = new SendRequestTransportException(
                                holderToNotify.connection().getNode(),
                                holderToNotify.action(),
                                null
                        );
                        holderToNotify.handler().handleException(ex);
                    }
                });
            }
        }
    }

    @Override
    protected void doClose() throws IOException {

    }

    @Override
    public TransportInfo info() {
        return null;
    }
}
