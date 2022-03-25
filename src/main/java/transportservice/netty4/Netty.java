// package transportservice.netty4;
//
// import io.netty.bootstrap.Bootstrap;
// import io.netty.bootstrap.ServerBootstrap;
// import io.netty.channel.*;
// import io.netty.channel.socket.nio.NioChannelOption;
// import io.netty.util.AttributeKey;
// import org.apache.logging.log4j.LogManager;
// import org.apache.logging.log4j.Logger;
// import org.apache.logging.log4j.message.ParameterizedMessage;
// import org.opensearch.ExceptionsHelper;
// import org.opensearch.Version;
// import org.opensearch.cluster.node.DiscoveryNode;
// import org.opensearch.common.SuppressForbidden;
// import org.opensearch.common.io.stream.NamedWriteableRegistry;
// import org.opensearch.common.lease.Releasables;
// import org.opensearch.common.network.NetworkService;
// import org.opensearch.common.settings.Setting;
// import org.opensearch.common.settings.Settings;
// import org.opensearch.common.unit.ByteSizeUnit;
// import org.opensearch.common.unit.ByteSizeValue;
// import org.opensearch.common.util.PageCacheRecycler;
// import org.opensearch.common.util.concurrent.OpenSearchExecutors;
// import org.opensearch.core.internal.net.NetUtils;
// import org.opensearch.indices.breaker.CircuitBreakerService;
// import org.opensearch.threadpool.ThreadPool;
// import org.opensearch.transport.TcpChannel;
// import org.opensearch.transport.TcpServerChannel;
// import org.opensearch.transport.TransportSettings;
// import transportservice.*;
//
// import java.io.IOException;
// import java.net.InetSocketAddress;
// import java.net.SocketOption;
// import java.util.Map;
// import java.util.Set;
//
// import static org.opensearch.common.settings.Setting.byteSizeSetting;
// import static org.opensearch.common.util.concurrent.ConcurrentCollections.newConcurrentMap;
//
// public class Netty extends TcpTransport {
//
// private volatile SharedGroupFactory.SharedGroup sharedGroup;
// private final SharedGroupFactory sharedGroupFactory;
// private final RecvByteBufAllocator recvByteBufAllocator;
// private static final Logger logger = LogManager.getLogger(Netty.class);
// private final ByteSizeValue receivePredictorMin;
// private final ByteSizeValue receivePredictorMax;
// private volatile Bootstrap clientBootstrap;
// private final Map<String, ServerBootstrap> serverBootstraps = newConcurrentMap();
// protected Set<ProfileSettings> profileSettings;
// public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_SIZE = Setting.byteSizeSetting(
// "transportservice.transport.netty.receive_predictor_size",
// new ByteSizeValue(64, ByteSizeUnit.KB),
// Setting.Property.NodeScope
// );
// public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_MIN = byteSizeSetting(
// "transportservice.transport.netty.receive_predictor_min",
// NETTY_RECEIVE_PREDICTOR_SIZE,
// Setting.Property.NodeScope
// );
// public static final Setting<ByteSizeValue> NETTY_RECEIVE_PREDICTOR_MAX = byteSizeSetting(
// "transportservice.transport.netty.receive_predictor_max",
// NETTY_RECEIVE_PREDICTOR_SIZE,
// Setting.Property.NodeScope
// );
//
// public static final Setting<Integer> WORKER_COUNT = new Setting<>(
// "transport.netty.worker_count",
// (s) -> Integer.toString(OpenSearchExecutors.allocatedProcessors(s)),
// (s) -> Setting.parseInt(s, 1, "transport.netty.worker_count"),
// Setting.Property.NodeScope
// );
//
// public Netty(
// Settings settings,
// Version version,
// ThreadPool threadPool,
// NetworkService networkService,
// PageCacheRecycler pageCacheRecycler,
// NamedWriteableRegistry namedWriteableRegistry,
// CircuitBreakerService circuitBreakerService,
// SharedGroupFactory sharedGroupFactory
// ) {
// super(settings, version, threadPool, pageCacheRecycler, circuitBreakerService, namedWriteableRegistry, networkService);
// Netty4Utils.setAvailableProcessors(OpenSearchExecutors.NODE_PROCESSORS_SETTING.get(settings));
// NettyAllocator.logAllocatorDescriptionIfNeeded();
// this.sharedGroupFactory = sharedGroupFactory;
// this.profileSettings = getProfileSettings(Settings.builder().put("transport.profiles.test.port",
// "5555").put("transport.profiles.default.port", "3333").build());
// // See AdaptiveReceiveBufferSizePredictor#DEFAULT_XXX for default values in netty..., we can use higher ones for us, even fixed one
// this.receivePredictorMin = NETTY_RECEIVE_PREDICTOR_MIN.get(settings);
// this.receivePredictorMax = NETTY_RECEIVE_PREDICTOR_MAX.get(settings);
// if (receivePredictorMax.getBytes() == receivePredictorMin.getBytes()) {
// recvByteBufAllocator = new FixedRecvByteBufAllocator((int) receivePredictorMax.getBytes());
// } else {
// recvByteBufAllocator = new AdaptiveRecvByteBufAllocator(
// (int) receivePredictorMin.getBytes(),
// (int) receivePredictorMin.getBytes(),
// (int) receivePredictorMax.getBytes()
// );
// }
// }
//
// @Override
// protected void doStart() {
// boolean success = false;
// try {
// sharedGroup = sharedGroupFactory.getTransportGroup();
// clientBootstrap = createClientBootstrap(sharedGroup);
// if (NetworkService.NETWORK_SERVER.get(settings)) {
// for (ProfileSettings profileSettings : this.profileSettings) {
// createServerBootstrap(profileSettings, sharedGroup);
// bindServer(profileSettings);
// }
// }
// super.doStart();
// success = true;
// } finally {
// if (success == false) {
// doStop();
// }
// }
// }
//
// @Override
// @SuppressForbidden(reason = "debug")
// protected void stopInternal() {
// Releasables.close(() -> {
// if (sharedGroup != null) {
// sharedGroup.shutdown();
// }
// }, serverBootstraps::clear, () -> clientBootstrap = null);
// }
//
// protected ChannelHandler getServerChannelInitializer(String name) {
// return new ServerChannelInitializer(name);
// }
//
// // server
// private void createServerBootstrap(ProfileSettings profileSettings, SharedGroupFactory.SharedGroup sharedGroup) {
// String name = profileSettings.profileName;
// if (logger.isDebugEnabled()) {
// logger.debug(
// "using profile[{}], worker_count[{}], port[{}], bind_host[{}], publish_host[{}], receive_predictor[{}->{}]",
// name,
// sharedGroupFactory.getTransportWorkerCount(),
// profileSettings.portOrRange,
// profileSettings.bindHosts,
// profileSettings.publishHosts,
// receivePredictorMin,
// receivePredictorMax
// );
// }
//
// final ServerBootstrap serverBootstrap = new ServerBootstrap();
//
// serverBootstrap.group(sharedGroup.getLowLevelGroup());
//
// // NettyAllocator will return the channel type designed to work with the configuredAllocator
// serverBootstrap.channel(NettyAllocator.getServerChannelType());
//
// // Set the allocators for both the server channel and the child channels created
// serverBootstrap.option(ChannelOption.ALLOCATOR, NettyAllocator.getAllocator());
// serverBootstrap.childOption(ChannelOption.ALLOCATOR, NettyAllocator.getAllocator());
//
// serverBootstrap.childHandler(getServerChannelInitializer(name));
// serverBootstrap.handler(new ServerChannelExceptionHandler());
//
// serverBootstrap.childOption(ChannelOption.TCP_NODELAY, profileSettings.tcpNoDelay);
// serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, profileSettings.tcpKeepAlive);
// if (profileSettings.tcpKeepAlive) {
// // Note that transportservice.netty4.Netty logs a warning if it can't set the option
// if (profileSettings.tcpKeepIdle >= 0) {
// final SocketOption<Integer> keepIdleOption = NetUtils.getTcpKeepIdleSocketOptionOrNull();
// if (keepIdleOption != null) {
// serverBootstrap.childOption(NioChannelOption.of(keepIdleOption), profileSettings.tcpKeepIdle);
// }
// }
// if (profileSettings.tcpKeepInterval >= 0) {
// final SocketOption<Integer> keepIntervalOption = NetUtils.getTcpKeepIntervalSocketOptionOrNull();
// if (keepIntervalOption != null) {
// serverBootstrap.childOption(NioChannelOption.of(keepIntervalOption), profileSettings.tcpKeepInterval);
// }
//
// }
// if (profileSettings.tcpKeepCount >= 0) {
// final SocketOption<Integer> keepCountOption = NetUtils.getTcpKeepCountSocketOptionOrNull();
// if (keepCountOption != null) {
// serverBootstrap.childOption(NioChannelOption.of(keepCountOption), profileSettings.tcpKeepCount);
// }
// }
// }
//
// if (profileSettings.sendBufferSize.getBytes() != -1) {
// serverBootstrap.childOption(ChannelOption.SO_SNDBUF, Math.toIntExact(profileSettings.sendBufferSize.getBytes()));
// }
//
// if (profileSettings.receiveBufferSize.getBytes() != -1) {
// serverBootstrap.childOption(ChannelOption.SO_RCVBUF, Math.toIntExact(profileSettings.receiveBufferSize.bytesAsInt()));
// }
//
// serverBootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator);
// serverBootstrap.childOption(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator);
//
// serverBootstrap.option(ChannelOption.SO_REUSEADDR, profileSettings.reuseAddress);
// serverBootstrap.childOption(ChannelOption.SO_REUSEADDR, profileSettings.reuseAddress);
// serverBootstrap.validate();
//
// serverBootstraps.put(name, serverBootstrap);
// }
//
//
// @Override
// protected TcpServerChannel bind(String s, InetSocketAddress inetSocketAddress) throws IOException {
// Channel channel = serverBootstraps.get(s).bind(inetSocketAddress).syncUninterruptibly().channel();
// Netty4TcpServerChannel esChannel = new Netty4TcpServerChannel(channel);
// channel.attr(SERVER_CHANNEL_KEY).set(esChannel);
// return esChannel;
// }
//
//
// // client
// private Bootstrap createClientBootstrap(SharedGroupFactory.SharedGroup sharedGroup) {
// final Bootstrap bootstrap = new Bootstrap();
// bootstrap.group(sharedGroup.getLowLevelGroup());
//
// // NettyAllocator will return the channel type designed to work with the configured allocator
// assert Netty4NioSocketChannel.class.isAssignableFrom(NettyAllocator.getChannelType());
// bootstrap.channel(NettyAllocator.getChannelType());
// bootstrap.option(ChannelOption.ALLOCATOR, NettyAllocator.getAllocator());
//
// bootstrap.option(ChannelOption.TCP_NODELAY, TransportSettings.TCP_NO_DELAY.get(settings));
// bootstrap.option(ChannelOption.SO_KEEPALIVE, TransportSettings.TCP_KEEP_ALIVE.get(settings));
// if (TransportSettings.TCP_KEEP_ALIVE.get(settings)) {
// // Note that transportservice.Netty logs a warning if it can't set the option
// if (TransportSettings.TCP_KEEP_IDLE.get(settings) >= 0) {
// final SocketOption<Integer> keepIdleOption = NetUtils.getTcpKeepIdleSocketOptionOrNull();
// if (keepIdleOption != null) {
// bootstrap.option(NioChannelOption.of(keepIdleOption), TransportSettings.TCP_KEEP_IDLE.get(settings));
// }
// }
// if (TransportSettings.TCP_KEEP_INTERVAL.get(settings) >= 0) {
// final SocketOption<Integer> keepIntervalOption = NetUtils.getTcpKeepIntervalSocketOptionOrNull();
// if (keepIntervalOption != null) {
// bootstrap.option(NioChannelOption.of(keepIntervalOption), TransportSettings.TCP_KEEP_INTERVAL.get(settings));
// }
// }
// if (TransportSettings.TCP_KEEP_COUNT.get(settings) >= 0) {
// final SocketOption<Integer> keepCountOption = NetUtils.getTcpKeepCountSocketOptionOrNull();
// if (keepCountOption != null) {
// bootstrap.option(NioChannelOption.of(keepCountOption), TransportSettings.TCP_KEEP_COUNT.get(settings));
// }
// }
// }
//
// final ByteSizeValue tcpSendBufferSize = TransportSettings.TCP_SEND_BUFFER_SIZE.get(settings);
// if (tcpSendBufferSize.getBytes() > 0) {
// bootstrap.option(ChannelOption.SO_SNDBUF, Math.toIntExact(tcpSendBufferSize.getBytes()));
// }
//
// final ByteSizeValue tcpReceiveBufferSize = TransportSettings.TCP_RECEIVE_BUFFER_SIZE.get(settings);
// if (tcpReceiveBufferSize.getBytes() > 0) {
// bootstrap.option(ChannelOption.SO_RCVBUF, Math.toIntExact(tcpReceiveBufferSize.getBytes()));
// }
//
// bootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator);
//
// final boolean reuseAddress = TransportSettings.TCP_REUSE_ADDRESS.get(settings);
// bootstrap.option(ChannelOption.SO_REUSEADDR, reuseAddress);
//
// return bootstrap;
// }
//
// static final AttributeKey<Netty4TcpServerChannel> SERVER_CHANNEL_KEY = AttributeKey.newInstance("es-server-channel");
// static final AttributeKey<transportservice.netty4.Netty4TcpChannel> CHANNEL_KEY = AttributeKey.newInstance("es-channel");
//
// @ChannelHandler.Sharable
// private class ServerChannelExceptionHandler extends ChannelInboundHandlerAdapter {
//
// @Override
// public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
// ExceptionsHelper.maybeDieOnAnotherThread(cause);
// Netty4TcpServerChannel serverChannel = ctx.channel().attr(SERVER_CHANNEL_KEY).get();
// if (cause instanceof Error) {
// onServerException(serverChannel, new Exception(cause));
// } else {
// onServerException(serverChannel, (Exception) cause);
// }
// }
// }
//
// private void addClosedExceptionLogger(Channel channel) {
// channel.closeFuture().addListener(f -> {
// if (f.isSuccess() == false) {
// logger.debug(() -> new ParameterizedMessage("exception while closing channel: {}", channel), f.cause());
// }
// });
// }
//
//
//
// // Another class
// protected class ServerChannelInitializer extends ChannelInitializer<Channel> {
//
// protected final String name;
// private final NettyByteBufSizer sizer = new NettyByteBufSizer();
//
// protected ServerChannelInitializer(String name) {
// this.name = name;
// }
//
// @Override
// protected void initChannel(Channel ch) throws Exception {
// addClosedExceptionLogger(ch);
// assert ch instanceof Netty4NioSocketChannel;
// NetUtils.tryEnsureReasonableKeepAliveConfig(((Netty4NioSocketChannel) ch).javaChannel());
// Netty4TcpChannel nettyTcpChannel = new Netty4TcpChannel(ch, true, name, ch.newSucceededFuture());
// ch.attr(CHANNEL_KEY).set(nettyTcpChannel);
// ch.pipeline().addLast("byte_buf_sizer", sizer);
// ch.pipeline().addLast("logging", new OpenSearchLoggingHandler());
// ch.pipeline().addLast("dispatcher", new Netty4MessageChannelHandler(pageCacheRecycler, Netty4Transport.this));
// serverAcceptedChannel(nettyTcpChannel);
// }
//
// @Override
// public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
// ExceptionsHelper.maybeDieOnAnotherThread(cause);
// super.exceptionCaught(ctx, cause);
// }
// }
//
// }
