package org.apache.rocketmq.remoting.transport.http2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.ChannelEventListener;
import org.apache.rocketmq.remoting.InvokeCallback;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.RemotingServer;
import org.apache.rocketmq.remoting.common.Pair;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.exception.RemotingSendRequestException;
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException;
import org.apache.rocketmq.remoting.exception.RemotingTooMuchRequestException;
import org.apache.rocketmq.remoting.netty.ChannelStatisticsHandler;
import org.apache.rocketmq.remoting.transport.rocketmq.NettyDecoder;
import org.apache.rocketmq.remoting.transport.rocketmq.NettyEncoder;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.transport.NettyRemotingServerAbstract;
import org.apache.rocketmq.remoting.util.JvmUtils;
import org.apache.rocketmq.remoting.util.ThreadUtils;

public class Http2ServerImpl extends NettyRemotingServerAbstract implements RemotingServer {
    private static final InternalLogger LOG = InternalLoggerFactory.getLogger(RemotingHelper.ROCKETMQ_REMOTING);

    private ServerBootstrap serverBootstrap;
    private EventLoopGroup bossGroup;
    private EventLoopGroup ioGroup;
    private EventExecutorGroup workerGroup;
    private Class<? extends ServerSocketChannel> socketChannelClass;
    private NettyServerConfig serverConfig;
    private ChannelEventListener channelEventListener;
    private ExecutorService publicExecutor;
    private int port;
    private RPCHook rpcHook;

    public Http2ServerImpl(NettyServerConfig nettyServerConfig, ChannelEventListener channelEventListener) {
        super(nettyServerConfig.getServerOnewaySemaphoreValue(), nettyServerConfig.getServerAsyncSemaphoreValue());
        init(nettyServerConfig, channelEventListener);
    }

    public Http2ServerImpl() {
        super();
    }

    @Override
    public void init(NettyServerConfig nettyServerConfig, ChannelEventListener channelEventListener) {
        super.init(nettyServerConfig.getServerOnewaySemaphoreValue(), nettyServerConfig.getServerAsyncSemaphoreValue());
        this.serverBootstrap = new ServerBootstrap();
        this.serverConfig = nettyServerConfig;
        this.channelEventListener = channelEventListener;
        this.publicExecutor = ThreadUtils.newFixedThreadPool(
            serverConfig.getServerCallbackExecutorThreads(),
            10000, "Remoting-PublicExecutor", true);
        if (JvmUtils.isLinux() && this.serverConfig.isUseEpollNativeSelector()) {
            this.ioGroup = new EpollEventLoopGroup(serverConfig.getServerSelectorThreads(), ThreadUtils.newGenericThreadFactory("NettyEpollIoThreads",
                serverConfig.getServerSelectorThreads()));
            this.bossGroup = new EpollEventLoopGroup(serverConfig.getServerAcceptorThreads(), ThreadUtils.newGenericThreadFactory("NettyBossThreads",
                serverConfig.getServerAcceptorThreads()));
            this.socketChannelClass = EpollServerSocketChannel.class;
        } else {
            this.bossGroup = new NioEventLoopGroup(serverConfig.getServerAcceptorThreads(), ThreadUtils.newGenericThreadFactory("NettyBossThreads",
                serverConfig.getServerAcceptorThreads()));
            this.ioGroup = new NioEventLoopGroup(serverConfig.getServerSelectorThreads(), ThreadUtils.newGenericThreadFactory("NettyNioIoThreads",
                serverConfig.getServerSelectorThreads()));
            this.socketChannelClass = NioServerSocketChannel.class;
        }

        this.workerGroup = new DefaultEventExecutorGroup(serverConfig.getServerWorkerThreads(),
            ThreadUtils.newGenericThreadFactory("NettyWorkerThreads", serverConfig.getServerWorkerThreads()));
        this.port = nettyServerConfig.getListenPort();
        buildHttp2SslContext();
    }

    private void buildHttp2SslContext() {
        try {
            SslProvider provider = OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
            SelfSignedCertificate ssc;
            //NOTE: the cipher filter may not include all ciphers required by the HTTP/2 specification.
            //Please refer to the HTTP/2 specification for cipher requirements.
            ssc = new SelfSignedCertificate();
            sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .sslProvider(provider)
                .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE).build();
        } catch (Exception e) {
            LOG.error("Can not build SSL context !", e);
        }
    }

    @Override
    public void registerProcessor(int requestCode, NettyRequestProcessor processor, ExecutorService executor) {
        ExecutorService executorThis = executor;
        if (null == executor) {
            executorThis = this.publicExecutor;
        }
        Pair<NettyRequestProcessor, ExecutorService> pair = new Pair<NettyRequestProcessor, ExecutorService>(processor, executorThis);
        this.processorTable.put(requestCode, pair);
    }

    @Override
    public void registerDefaultProcessor(NettyRequestProcessor processor, ExecutorService executor) {
        this.defaultRequestProcessor = new Pair<NettyRequestProcessor, ExecutorService>(processor, executor);
    }

    @Override
    public int localListenPort() {
        return this.port;
    }

    @Override
    public Pair<NettyRequestProcessor, ExecutorService> getProcessorPair(int requestCode) {
        return processorTable.get(requestCode);
    }

    @Override
    public void push(String addr, String sessionId, RemotingCommand remotingCommand) {

    }

    @Override
    public RemotingCommand invokeSync(final Channel channel, final RemotingCommand request, final long timeoutMillis)
        throws InterruptedException, RemotingSendRequestException, RemotingTimeoutException {
        return this.invokeSyncImpl(channel, request, timeoutMillis);
    }

    @Override
    public void invokeAsync(Channel channel, RemotingCommand request, long timeoutMillis, InvokeCallback invokeCallback)
        throws InterruptedException, RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
        this.invokeAsyncImpl(channel, request, timeoutMillis, invokeCallback);
    }

    @Override
    public void invokeOneway(Channel channel, RemotingCommand request, long timeoutMillis) throws InterruptedException,
        RemotingTooMuchRequestException, RemotingTimeoutException, RemotingSendRequestException {
        this.invokeOnewayImpl(channel, request, timeoutMillis);
    }

    private void applyOptions(ServerBootstrap bootstrap) {
        if (null != serverConfig) {
            bootstrap.option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_KEEPALIVE, false)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_SNDBUF, serverConfig.getServerSocketSndBufSize())
                .childOption(ChannelOption.SO_RCVBUF, serverConfig.getServerSocketRcvBufSize());
        }

        if (serverConfig.isServerPooledByteBufAllocatorEnable()) {
            bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        }
    }

    @Override
    public void start() {
        super.start();
        final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        this.serverBootstrap.group(this.bossGroup, this.ioGroup).
            channel(socketChannelClass).childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                channels.add(ch);

                ChannelPipeline cp = ch.pipeline();

                cp.addLast(ChannelStatisticsHandler.NAME, new ChannelStatisticsHandler(channels));

                cp.addLast(workerGroup,
                    Http2Handler.newHandler(true),
                    new NettyEncoder(),
                    new NettyDecoder(),
                    new IdleStateHandler(serverConfig.getConnectionChannelReaderIdleSeconds(),
                        serverConfig.getConnectionChannelWriterIdleSeconds(),
                        serverConfig.getServerChannelMaxIdleTimeSeconds()),
                    new NettyConnectManageHandler(),
                    new NettyServerHandler());
            }
        });

        applyOptions(serverBootstrap);

        ChannelFuture channelFuture = this.serverBootstrap.bind(this.port).syncUninterruptibly();
        this.port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort();
        startUpHouseKeepingService();
    }

    @Override
    public void shutdown() {
        super.shutdown();
        ThreadUtils.shutdownGracefully(publicExecutor, 2000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void registerRPCHook(RPCHook rpcHook) {
        this.rpcHook = rpcHook;
    }

    @Override
    public RPCHook getRPCHook() {
        return this.rpcHook;
    }

    @Override
    public ExecutorService getCallbackExecutor() {
        return this.publicExecutor;
    }

    @Override
    public ChannelEventListener getChannelEventListener() {
        return this.channelEventListener;
    }

}