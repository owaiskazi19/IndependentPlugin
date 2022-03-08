package transportservice;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.LegacyESVersion;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.logging.Loggers;
import org.opensearch.common.regex.Regex;
import org.opensearch.common.transport.BoundTransportAddress;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.AbstractRunnable;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.internal.io.IOUtils;
import org.opensearch.node.NodeClosedException;
import org.opensearch.node.ReportingService;
import org.opensearch.threadpool.Scheduler;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.*;
import transportservice.action.ActionListenerResponseHandler;
import transportservice.action.PlainActionFuture;
import transportservice.component.AbstractLifecycleComponent;
import transportservice.transport.ConnectionProfile;
import transportservice.transport.TransportConnectionListener;
import transportservice.transport.TransportMessageListener;
import transportservice.transport.Transport;
import transportservice.action.ActionListener;
import transportservice.transport.ConnectionManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
    private final Function<BoundTransportAddress, DiscoveryNode> localNodeFactory;
    private final DelegatingTransportMessageListener messageListener = new DelegatingTransportMessageListener();
    volatile DiscoveryNode localNode = null;
    public static final String HANDSHAKE_ACTION_NAME = "internal:transport/handshake";
    private final TransportInterceptor.AsyncSender asyncSender;
    private final TransportInterceptor interceptor;
    private final Logger tracerLog;
    volatile String[] tracerLogInclude;
    volatile String[] tracerLogExclude;
    private final AtomicBoolean handleIncomingRequests = new AtomicBoolean();
    public static final String DIRECT_RESPONSE_PROFILE = ".direct";
    final Map<Long, TimeoutInfoHolder> timeoutInfoHandlers = Collections.synchronizedMap(
            new LinkedHashMap<Long, TimeoutInfoHolder>(100, .75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > 100;
                }
            }
    );

    private final Transport.Connection localNodeConnection = new Transport.Connection() {
        @Override
        public DiscoveryNode getNode() {
            return localNode;
        }

        @Override
        public void sendRequest(long requestId, String action, TransportRequest request, TransportRequestOptions options)
                throws TransportException {
            sendLocalRequest(requestId, action, request, options);
        }

        @Override
        public void addCloseListener(ActionListener<Void> listener) {}

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {}
    };


    public TransportService(Transport transport, ConnectionManager connectionManager, Transport.ResponseHandlers responseHandlers, ThreadPool threadPool, Function<BoundTransportAddress, DiscoveryNode> localNodeFactory, TransportInterceptor transportInterceptor) {
        this.transport = transport;
        this.connectionManager = connectionManager;
        this.responseHandlers = responseHandlers;
        this.threadPool = threadPool;
        this.localNodeFactory = localNodeFactory;
        this.interceptor = transportInterceptor;
        this.asyncSender = interceptor.interceptSender(this::sendRequestInternal);
        tracerLog = Loggers.getLogger(logger, ".tracer");
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

       // localNode = localNodeFactory.apply(transport.boundAddress());

    }

    public void addConnectionListener(transportservice.transport.TransportConnectionListener listener) {
        connectionManager.addListener(listener);
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

    // Connect to Node

    private boolean isLocalNode(DiscoveryNode discoveryNode) {
        return Objects.requireNonNull(discoveryNode, "discovery node must not be null").equals(localNode);
    }

    /**
     * Connect to the specified node with the default connection profile
     *
     * @param node the node to connect to
     */
    public void connectToNode(DiscoveryNode node) throws ConnectTransportException {
        connectToNode(node,  null);
    }

    public void connectToNode(final DiscoveryNode node, ConnectionProfile connectionProfile) {
        PlainActionFuture.get(fut -> connectToNode(node, connectionProfile, ActionListener.map(fut, x -> null)));
    }

    public void connectToNode(final DiscoveryNode node, ConnectionProfile connectionProfile, ActionListener<Void> listener) {
        if (isLocalNode(node)) {
            listener.onResponse(null);
            return;
        }
        connectionManager.connectToNode(node, connectionProfile, connectionValidator(node), listener);
    }

    public ConnectionManager.ConnectionValidator connectionValidator(DiscoveryNode node) {
        return (newConnection, actualProfile, listener) -> {
            // We don't validate cluster names to allow for CCS connections.
            handshake(newConnection, actualProfile.getHandshakeTimeout().millis(), cn -> true, ActionListener.map(listener, resp -> {
                final DiscoveryNode remote = resp.discoveryNode;
                if (node.equals(remote) == false) {
                    throw new ConnectTransportException(node, "handshake failed. unexpected remote node " + remote);
                }
                return null;
            }));
        };
    }


    /**
     * Establishes a new connection to the given node. The connection is NOT maintained by this service, it's the callers
     * responsibility to close the connection once it goes out of scope.
     * The ActionListener will be called on the calling thread or the generic thread pool.
     * @param node the node to connect to
     * @param connectionProfile the connection profile to use
     * @param listener the action listener to notify
     */
    public void openConnection(
            final DiscoveryNode node,
            transportservice.transport.ConnectionProfile connectionProfile,
            ActionListener<Transport.Connection> listener
    ) {
        if (isLocalNode(node)) {
            listener.onResponse(localNodeConnection);
        } else {
            connectionManager.openConnection(node, connectionProfile, listener);
        }
    }


    public void handshake(
            final Transport.Connection connection,
            final long handshakeTimeout,
            Predicate<ClusterName> clusterNamePredicate,
            final ActionListener<HandshakeResponse> listener
    ) {
        final DiscoveryNode node = connection.getNode();
        sendRequest(
                connection,
                HANDSHAKE_ACTION_NAME,
                HandshakeRequest.INSTANCE,
                TransportRequestOptions.builder().withTimeout(handshakeTimeout).build(),
                new ActionListenerResponseHandler<>(new ActionListener<HandshakeResponse>() {
                    @Override
                    public void onResponse(HandshakeResponse response) {
                        if (clusterNamePredicate.test(response.clusterName) == false) {
                            listener.onFailure(
                                    new IllegalStateException(
                                            "handshake with ["
                                                    + node
                                                    + "] failed: remote cluster name ["
                                                    + response.clusterName.value()
                                                    + "] does not match "
                                                    + clusterNamePredicate
                                    )
                            );
                        } else if (response.version.isCompatible(localNode.getVersion()) == false) {
                            listener.onFailure(
                                    new IllegalStateException(
                                            "handshake with ["
                                                    + node
                                                    + "] failed: remote node version ["
                                                    + response.version
                                                    + "] is incompatible with local node version ["
                                                    + localNode.getVersion()
                                                    + "]"
                                    )
                            );
                        } else {
                            listener.onResponse(response);
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }
                }, HandshakeResponse::new, ThreadPool.Names.GENERIC)
        );
    }

    public final <T extends TransportResponse> void sendRequest(
            final Transport.Connection connection,
            final String action,
            final TransportRequest request,
            final TransportRequestOptions options,
            final TransportResponseHandler<T> handler
    ) {
        try {
            final TransportResponseHandler<T> delegate;
            if (request.getParentTask().isSet()) {
                // TODO: capture the connection instead so that we can cancel child tasks on the remote connections.
               // final Releasable unregisterChildNode = taskManager.registerChildNode(request.getParentTask().getId(), connection.getNode());
                delegate = new TransportResponseHandler<T>() {
                    @Override
                    public void handleResponse(T response) {
                       // unregisterChildNode.close();
                        handler.handleResponse(response);
                    }

                    @Override
                    public void handleException(TransportException exp) {
                       // unregisterChildNode.close();
                        handler.handleException(exp);
                    }

                    @Override
                    public String executor() {
                        return handler.executor();
                    }

                    @Override
                    public T read(StreamInput in) throws IOException {
                        return handler.read(in);
                    }

                    @Override
                    public String toString() {
                        return getClass().getName() + "/[" + action + "]:" + handler.toString();
                    }
                };
            } else {
                delegate = handler;
            }
            asyncSender.sendRequest(connection, action, request, options, delegate);
        } catch (final Exception ex) {
            // the caller might not handle this so we invoke the handler
            final TransportException te;
            if (ex instanceof TransportException) {
                te = (TransportException) ex;
            } else {
                te = new TransportException("failure to send", ex);
            }
            handler.handleException(te);
        }
    }

    // Async sender


    private <T extends TransportResponse> void sendRequestInternal(
            final Transport.Connection connection,
            final String action,
            final TransportRequest request,
            final TransportRequestOptions options,
            TransportResponseHandler<T> handler
    ) {
        if (connection == null) {
            throw new IllegalStateException("can't send request to a null connection");
        }
        DiscoveryNode node = connection.getNode();

        Supplier<ThreadContext.StoredContext> storedContextSupplier = threadPool.getThreadContext().newRestorableContext(true);
        ContextRestoreResponseHandler<T> responseHandler = new ContextRestoreResponseHandler<>(storedContextSupplier, handler);
        // TODO we can probably fold this entire request ID dance into connection.sendReqeust but it will be a bigger refactoring
        // DUMMY VALUE
        final long requestId = 256;
        final TransportService.TimeoutHandler timeoutHandler;
        if (options.timeout() != null) {
            timeoutHandler = new TimeoutHandler(requestId, connection.getNode(), action);
            responseHandler.setTimeoutHandler(timeoutHandler);
        } else {
            timeoutHandler = null;
        }
        try {
            if (lifecycle.stoppedOrClosed()) {
                /*
                 * If we are not started the exception handling will remove the request holder again and calls the handler to notify the
                 * caller. It will only notify if toStop hasn't done the work yet.
                 */
                throw new NodeClosedException(localNode);
            }
            if (timeoutHandler != null) {
                assert options.timeout() != null;
                timeoutHandler.scheduleTimeout(options.timeout());
            }
            connection.sendRequest(requestId, action, request, options); // local node optimization happens upstream
        } catch (final Exception e) {
            // usually happen either because we failed to connect to the node
            // or because we failed serializing the message
            final Transport.ResponseContext<? extends TransportResponse> contextToNotify = responseHandlers.remove(requestId);
            // If holderToNotify == null then handler has already been taken care of.
            if (contextToNotify != null) {
                if (timeoutHandler != null) {
                    timeoutHandler.cancel();
                }
                // callback that an exception happened, but on a different thread since we don't
                // want handlers to worry about stack overflows. In the special case of running into a closing node we run on the current
                // thread on a best effort basis though.
                final SendRequestTransportException sendRequestException = new SendRequestTransportException(node, action, e);
                final String executor = lifecycle.stoppedOrClosed() ? ThreadPool.Names.SAME : ThreadPool.Names.GENERIC;
                threadPool.executor(executor).execute(new AbstractRunnable() {
                    @Override
                    public void onRejection(Exception e) {
                        // if we get rejected during node shutdown we don't wanna bubble it up
                        logger.debug(
                                () -> new ParameterizedMessage(
                                        "failed to notify response handler on rejection, action: {}",
                                        contextToNotify.action()
                                ),
                                e
                        );
                    }

                    @Override
                    public void onFailure(Exception e) {
                        logger.warn(
                                () -> new ParameterizedMessage(
                                        "failed to notify response handler on exception, action: {}",
                                        contextToNotify.action()
                                ),
                                e
                        );
                    }

                    @Override
                    protected void doRun() throws Exception {
                        contextToNotify.handler().handleException(sendRequestException);
                    }
                });
            } else {
                logger.debug("Exception while sending request, handler likely already notified due to timeout", e);
            }
        }
    }

    public RequestHandlerRegistry<? extends TransportRequest> getRequestHandler(String action) {
        return transport.getRequestHandlers().getHandler(action);
    }

    private void sendLocalRequest(long requestId, final String action, final TransportRequest request, TransportRequestOptions options) {
        final DirectResponseChannel channel = new DirectResponseChannel(localNode, action, requestId, this, threadPool);
        try {
            onRequestSent(localNode, requestId, action, request, options);
            onRequestReceived(requestId, action);
            final RequestHandlerRegistry reg = getRequestHandler(action);
            if (reg == null) {
                throw new ActionNotFoundTransportException("Action [" + action + "] not found");
            }
            final String executor = reg.getExecutor();
            if (ThreadPool.Names.SAME.equals(executor)) {
                // noinspection unchecked
                reg.processMessageReceived(request, channel);
            } else {
                threadPool.executor(executor).execute(new AbstractRunnable() {
                    @Override
                    protected void doRun() throws Exception {
                        // noinspection unchecked
                        reg.processMessageReceived(request, channel);
                    }

                    @Override
                    public boolean isForceExecution() {
                        return reg.isForceExecution();
                    }

                    @Override
                    public void onFailure(Exception e) {
                        try {
                            channel.sendResponse(e);
                        } catch (Exception inner) {
                            inner.addSuppressed(e);
                            logger.warn(
                                    () -> new ParameterizedMessage("failed to notify channel of error message for action [{}]", action),
                                    inner
                            );
                        }
                    }

                    @Override
                    public String toString() {
                        return "processing of [" + requestId + "][" + action + "]: " + request;
                    }
                });
            }

        } catch (Exception e) {
            try {
                channel.sendResponse(e);
            } catch (Exception inner) {
                inner.addSuppressed(e);
                logger.warn(() -> new ParameterizedMessage("failed to notify channel of error message for action [{}]", action), inner);
            }
        }
    }



    //Ends here


    @Override
    protected void doClose() throws IOException {

    }

    /**
     * start accepting incoming requests.
     * when the transport layer starts up it will block any incoming requests until
     * this method is called
     */
    public final void acceptIncomingRequests() {
        handleIncomingRequests.set(true);
    }

    private boolean shouldTraceAction(String action) {
        return shouldTraceAction(action, tracerLogInclude, tracerLogExclude);
    }

    public static boolean shouldTraceAction(String action, String[] include, String[] exclude) {
        if (include.length > 0) {
            if (Regex.simpleMatch(include, action) == false) {
                return false;
            }
        }
        if (exclude.length > 0) {
            return !Regex.simpleMatch(exclude, action);
        }
        return true;
    }


    /**
     * called by the {@link Transport} implementation when an incoming request arrives but before
     * any parsing of it has happened (with the exception of the requestId and action)
     */
    @Override
    public void onRequestReceived(long requestId, String action) {
        if (handleIncomingRequests.get() == false) {
            throw new IllegalStateException("transport not ready yet to handle incoming requests");
        }
        if (tracerLog.isTraceEnabled() && shouldTraceAction(action)) {
            tracerLog.trace("[{}][{}] received request", requestId, action);
        }
        messageListener.onRequestReceived(requestId, action);
    }

    @Override
    public TransportInfo info() {
        return null;
    }

    public static class HandshakeResponse extends TransportResponse {
        private final DiscoveryNode discoveryNode;
        private final ClusterName clusterName;
        private final Version version;

        public HandshakeResponse(DiscoveryNode discoveryNode, ClusterName clusterName, Version version) {
            this.discoveryNode = discoveryNode;
            this.version = version;
            this.clusterName = clusterName;
        }

        public HandshakeResponse(StreamInput in) throws IOException {
            super(in);
            discoveryNode = in.readOptionalWriteable(DiscoveryNode::new);
            clusterName = new ClusterName(in);
            Version tmpVersion = Version.readVersion(in);
            if (in.getVersion().onOrBefore(LegacyESVersion.V_7_10_2)) {
                tmpVersion = LegacyESVersion.V_7_10_2;
            }
            version = tmpVersion;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeOptionalWriteable(discoveryNode);
            clusterName.writeTo(out);
            if (out.getVersion().before(Version.V_1_0_0)) {
                Version.writeVersion(LegacyESVersion.V_7_10_2, out);
            } else {
                Version.writeVersion(version, out);
            }
        }

        public DiscoveryNode getDiscoveryNode() {
            return discoveryNode;
        }

        public ClusterName getClusterName() {
            return clusterName;
        }
    }

    static class HandshakeRequest extends TransportRequest {

        public static final HandshakeRequest INSTANCE = new HandshakeRequest();

        HandshakeRequest(StreamInput in) throws IOException {
            super(in);
        }

        private HandshakeRequest() {}

    }

    final class TimeoutHandler implements Runnable {

        private final long requestId;
        private final long sentTime = threadPool.relativeTimeInMillis();
        private final String action;
        private final DiscoveryNode node;
        volatile Scheduler.Cancellable cancellable;

        TimeoutHandler(long requestId, DiscoveryNode node, String action) {
            this.requestId = requestId;
            this.node = node;
            this.action = action;
        }

        @Override
        public void run() {
            if (responseHandlers.contains(requestId)) {
                long timeoutTime = threadPool.relativeTimeInMillis();
                timeoutInfoHandlers.put(requestId, new TimeoutInfoHolder(node, action, sentTime, timeoutTime));
                // now that we have the information visible via timeoutInfoHandlers, we try to remove the request id
                final Transport.ResponseContext<? extends TransportResponse> holder = responseHandlers.remove(requestId);
                if (holder != null) {
                    assert holder.action().equals(action);
                    assert holder.connection().getNode().equals(node);
                    holder.handler()
                            .handleException(
                                    new ReceiveTimeoutTransportException(
                                            holder.connection().getNode(),
                                            holder.action(),
                                            "request_id [" + requestId + "] timed out after [" + (timeoutTime - sentTime) + "ms]"
                                    )
                            );
                } else {
                    // response was processed, remove timeout info.
                    timeoutInfoHandlers.remove(requestId);
                }
            }
        }

        /**
         * cancels timeout handling. this is a best effort only to avoid running it. remove the requestId from {@link #responseHandlers}
         * to make sure this doesn't run.
         */
        public void cancel() {
            assert responseHandlers.contains(requestId) == false : "cancel must be called after the requestId ["
                    + requestId
                    + "] has been removed from clientHandlers";
            if (cancellable != null) {
                cancellable.cancel();
            }
        }

        @Override
        public String toString() {
            return "timeout handler for [" + requestId + "][" + action + "]";
        }

        private void scheduleTimeout(TimeValue timeout) {
            this.cancellable = threadPool.schedule(this, timeout, ThreadPool.Names.GENERIC);
        }
    }

    static class TimeoutInfoHolder {

        private final DiscoveryNode node;
        private final String action;
        private final long sentTime;
        private final long timeoutTime;

        TimeoutInfoHolder(DiscoveryNode node, String action, long sentTime, long timeoutTime) {
            this.node = node;
            this.action = action;
            this.sentTime = sentTime;
            this.timeoutTime = timeoutTime;
        }

        public DiscoveryNode node() {
            return node;
        }

        public String action() {
            return action;
        }

        public long sentTime() {
            return sentTime;
        }

        public long timeoutTime() {
            return timeoutTime;
        }
    }

    public static final class ContextRestoreResponseHandler<T extends TransportResponse> implements TransportResponseHandler<T> {

        private final TransportResponseHandler<T> delegate;
        private final Supplier<ThreadContext.StoredContext> contextSupplier;
        private volatile TimeoutHandler handler;

        public ContextRestoreResponseHandler(Supplier<ThreadContext.StoredContext> contextSupplier, TransportResponseHandler<T> delegate) {
            this.delegate = delegate;
            this.contextSupplier = contextSupplier;
        }

        @Override
        public T read(StreamInput in) throws IOException {
            return delegate.read(in);
        }

        @Override
        public void handleResponse(T response) {
            if (handler != null) {
                handler.cancel();
            }
            try (ThreadContext.StoredContext ignore = contextSupplier.get()) {
                delegate.handleResponse(response);
            }
        }

        @Override
        public void handleException(TransportException exp) {
            if (handler != null) {
                handler.cancel();
            }
            try (ThreadContext.StoredContext ignore = contextSupplier.get()) {
                delegate.handleException(exp);
            }
        }

        @Override
        public String executor() {
            return delegate.executor();
        }

        @Override
        public String toString() {
            return getClass().getName() + "/" + delegate.toString();
        }

        void setTimeoutHandler(TimeoutHandler handler) {
            this.handler = handler;
        }

    }

    private static final class DelegatingTransportMessageListener implements TransportMessageListener {

        private final List<TransportMessageListener> listeners = new CopyOnWriteArrayList<>();

        @Override
        public void onRequestReceived(long requestId, String action) {
            for (TransportMessageListener listener : listeners) {
                listener.onRequestReceived(requestId, action);
            }
        }

        @Override
        public void onResponseSent(long requestId, String action, TransportResponse response) {
            for (TransportMessageListener listener : listeners) {
                listener.onResponseSent(requestId, action, response);
            }
        }

        @Override
        public void onResponseSent(long requestId, String action, Exception error) {
            for (TransportMessageListener listener : listeners) {
                listener.onResponseSent(requestId, action, error);
            }
        }

        @Override
        public void onRequestSent(
                DiscoveryNode node,
                long requestId,
                String action,
                TransportRequest request,
                TransportRequestOptions finalOptions
        ) {
            for (TransportMessageListener listener : listeners) {
                listener.onRequestSent(node, requestId, action, request, finalOptions);
            }
        }

        @Override
        public void onResponseReceived(long requestId, transportservice.transport.Transport.ResponseContext holder) {
            for (TransportMessageListener listener : listeners) {
                listener.onResponseReceived(requestId, holder);
            }
        }
    }

    static class DirectResponseChannel implements TransportChannel {
        final DiscoveryNode localNode;
        private final String action;
        private final long requestId;
        final TransportService service;
        final ThreadPool threadPool;

        DirectResponseChannel(DiscoveryNode localNode, String action, long requestId, TransportService service, ThreadPool threadPool) {
            this.localNode = localNode;
            this.action = action;
            this.requestId = requestId;
            this.service = service;
            this.threadPool = threadPool;
        }

        @Override
        public String getProfileName() {
            return DIRECT_RESPONSE_PROFILE;
        }

        @Override
        public void sendResponse(TransportResponse response) throws IOException {
            service.onResponseSent(requestId, action, response);
            final TransportResponseHandler handler = service.responseHandlers.onResponseReceived(requestId, service);
            // ignore if its null, the service logs it
            if (handler != null) {
                final String executor = handler.executor();
                if (ThreadPool.Names.SAME.equals(executor)) {
                    processResponse(handler, response);
                } else {
                    threadPool.executor(executor).execute(new Runnable() {
                        @Override
                        public void run() {
                            processResponse(handler, response);
                        }

                        @Override
                        public String toString() {
                            return "delivery of response to [" + requestId + "][" + action + "]: " + response;
                        }
                    });
                }
            }
        }

        @SuppressWarnings("unchecked")
        protected void processResponse(TransportResponseHandler handler, TransportResponse response) {
            try {
                handler.handleResponse(response);
            } catch (Exception e) {
                processException(handler, wrapInRemote(new ResponseHandlerFailureTransportException(e)));
            }
        }

        @Override
        public void sendResponse(Exception exception) throws IOException {
            service.onResponseSent(requestId, action, exception);
            final TransportResponseHandler handler = service.responseHandlers.onResponseReceived(requestId, service);
            // ignore if its null, the service logs it
            if (handler != null) {
                final RemoteTransportException rtx = wrapInRemote(exception);
                final String executor = handler.executor();
                if (ThreadPool.Names.SAME.equals(executor)) {
                    processException(handler, rtx);
                } else {
                    threadPool.executor(handler.executor()).execute(new Runnable() {
                        @Override
                        public void run() {
                            processException(handler, rtx);
                        }

                        @Override
                        public String toString() {
                            return "delivery of failure response to [" + requestId + "][" + action + "]: " + exception;
                        }
                    });
                }
            }
        }

        protected RemoteTransportException wrapInRemote(Exception e) {
            if (e instanceof RemoteTransportException) {
                return (RemoteTransportException) e;
            }
            return new RemoteTransportException(localNode.getName(), localNode.getAddress(), action, e);
        }

        protected void processException(final TransportResponseHandler handler, final RemoteTransportException rtx) {
            try {
                handler.handleException(rtx);
            } catch (Exception e) {
                logger.error(
                        () -> new ParameterizedMessage("failed to handle exception for action [{}], handler [{}]", action, handler),
                        e
                );
            }
        }

        @Override
        public String getChannelType() {
            return "direct";
        }

        @Override
        public Version getVersion() {
            return localNode.getVersion();
        }
    }


}
