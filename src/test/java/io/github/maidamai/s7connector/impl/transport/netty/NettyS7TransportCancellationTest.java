package io.github.maidamai.s7connector.impl.transport.netty;

import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.impl.transport.S7TransportConfig;
import io.github.maidamai.s7connector.impl.transport.S7TransportException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyS7TransportCancellationTest {

    @Test
    void interruptedReadFailsTransportClearsPendingAndRestoresInterruptFlag() throws Exception {
        try (SilentServer server = new SilentServer(false)) {
            final NettyS7Transport transport = new NettyS7Transport(config(server.getPort(), 5000));
            try {
                transport.connect();
                final byte[] request = tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80, 0x01});
                final AtomicReference<Throwable> failure = new AtomicReference<>();
                // observed on the worker itself: the interrupt status of an
                // already-terminated thread is not reliably observable
                final AtomicReference<Boolean> interruptRestored = new AtomicReference<>();
                final CountDownLatch requestSent = new CountDownLatch(1);
                final Thread worker = new Thread(() -> {
                    try {
                        requestSent.countDown();
                        transport.writeAndRead(request, request.length);
                    } catch (final Throwable t) {
                        failure.set(t);
                        interruptRestored.set(Boolean.valueOf(Thread.currentThread().isInterrupted()));
                    }
                }, "s7-interrupted-reader");
                try {
                    worker.start();
                    assertTrue(requestSent.await(2, TimeUnit.SECONDS), "worker should start waiting");
                    Thread.sleep(200);
                    worker.interrupt();
                    worker.join(5000);
                    assertFalse(worker.isAlive(), "worker should terminate after interruption");

                    final Throwable thrown = failure.get();
                    assertNotNull(thrown, "interrupted writeAndRead should throw");
                    assertTrue(thrown instanceof S7TransportException, "expected S7TransportException but got " + thrown);
                    assertTrue(thrown.getCause() instanceof InterruptedException,
                            "cause should be InterruptedException but was " + thrown.getCause());
                    assertTrue(transport.isClosed(), "interrupt should close the transport");
                    assertFalse(transport.hasPendingResponse(), "interrupt should clear the pending response future");
                    assertTrue(interruptRestored.get().booleanValue(),
                            "interrupt flag should be restored on the worker thread");

                    assertThrows(S7TransportException.class, () -> transport.writeAndRead(request, request.length),
                            "closed transport should reject further writes");
                } finally {
                    worker.interrupt();
                    worker.join(5000);
                    transport.close();
                }
            } finally {
                transport.close();
            }
        }
    }

    @Test
    void readTimeoutClosesTransportAndRejectsFurtherWrites() throws IOException {
        try (SilentServer server = new SilentServer(false)) {
            final NettyS7Transport transport = new NettyS7Transport(config(server.getPort(), 100));
            try {
                transport.connect();
                final byte[] request = tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80, 0x02});

                assertThrows(S7TransportException.class, () -> transport.writeAndRead(request, request.length),
                        "missing response should fail with a read timeout");
                assertTrue(transport.isClosed(), "read timeout should close the transport");
                assertFalse(transport.hasPendingResponse(), "read timeout should clear the pending response future");
                assertThrows(S7TransportException.class, () -> transport.writeAndRead(request, request.length),
                        "closed transport should reject further writes");
            } finally {
                transport.close();
            }
        }
    }

    @Test
    void remoteImmediateCloseFailsExchangeAndClosesTransport() throws IOException {
        try (SilentServer server = new SilentServer(true)) {
            final NettyS7Transport transport = new NettyS7Transport(config(server.getPort(), 2000));
            try {
                transport.connect();
                final byte[] request = tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80, 0x03});

                assertThrows(S7TransportException.class, () -> transport.writeAndRead(request, request.length),
                        "remote close should fail the pending read");
                assertTrue(transport.isClosed(), "remote close should leave the transport closed");
                assertFalse(transport.hasPendingResponse(), "remote close should clear the pending response future");
            } finally {
                transport.close();
            }
        }
    }

    @Test
    void failedConnectClosesTransportWithoutLeakingEventLoop() throws IOException {
        final int unusedPort = findUnusedPort();
        final NettyS7Transport transport = new NettyS7Transport(config(unusedPort, 200));
        try {
            assertThrows(IOException.class, transport::connect,
                    "connecting to a closed port should fail");
            assertTrue(transport.isClosed(), "failed connect should leave the transport closed");
            final byte[] request = tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80, 0x04});
            assertThrows(S7TransportException.class, () -> transport.writeAndRead(request, request.length),
                    "transport with a failed connect should reject writes");
        } finally {
            transport.close();
        }
    }

    @Test
    void connectAfterCloseIsRejected() throws IOException {
        try (SilentServer server = new SilentServer(false)) {
            final NettyS7Transport transport = new NettyS7Transport(config(server.getPort(), 500));
            try {
                transport.connect();
                transport.close();

                assertThrows(S7TransportException.class, transport::connect,
                        "a closed transport must not be revivable; create a new instance instead");
                assertTrue(transport.isClosed(), "the transport stays closed");
            } finally {
                transport.close();
            }
        }
    }

    @Test
    void builderRejectsNullPlcType() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> S7ConnectorFactory.buildTCPConnector(null),
                "null PLC type should fail fast");
        assertTrue(error.getMessage().contains("type"), "message should name the parameter: " + error.getMessage());
    }

    @Test
    void builderRejectsNegativeRack() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> S7ConnectorFactory.buildTCPConnector().withRack(-1),
                "negative rack should fail fast");
        assertTrue(error.getMessage().contains("rack"), "message should name the parameter: " + error.getMessage());
        assertTrue(error.getMessage().contains("-1"), "message should include the value: " + error.getMessage());
    }

    @Test
    void builderRejectsNegativeSlot() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> S7ConnectorFactory.buildTCPConnector().withSlot(-3),
                "negative slot should fail fast");
        assertTrue(error.getMessage().contains("slot"), "message should name the parameter: " + error.getMessage());
        assertTrue(error.getMessage().contains("-3"), "message should include the value: " + error.getMessage());
    }

    private static S7TransportConfig config(final int port, final int timeoutMillis) {
        return new S7TransportConfig("127.0.0.1", port, timeoutMillis, timeoutMillis);
    }

    private static byte[] tpkt(final byte[] payload) {
        final byte[] frame = new byte[payload.length + 4];
        frame[0] = 0x03;
        frame[1] = 0x00;
        frame[2] = (byte) (frame.length / 0x100);
        frame[3] = (byte) (frame.length % 0x100);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        return frame;
    }

    private static int findUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    /**
     * Local Netty server that accepts connections and never answers.
     * Optionally closes the connection right after receiving the first request.
     */
    private static final class SilentServer implements AutoCloseable {
        private static final int MAX_FRAME_LENGTH = 2048;

        private final EventLoopGroup bossGroup;
        private final EventLoopGroup workerGroup;
        private final Channel channel;

        SilentServer(final boolean closeAfterFirstRequest) throws IOException {
            this.bossGroup = new NioEventLoopGroup(1);
            this.workerGroup = new NioEventLoopGroup(1);
            final ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(this.bossGroup, this.workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel socketChannel) {
                            socketChannel.pipeline().addLast(
                                    new io.netty.handler.codec.LengthFieldBasedFrameDecoder(
                                            MAX_FRAME_LENGTH, 2, 2, -4, 0));
                            socketChannel.pipeline().addLast(new SimpleChannelInboundHandler<io.netty.buffer.ByteBuf>() {
                                @Override
                                protected void channelRead0(final ChannelHandlerContext context,
                                                            final io.netty.buffer.ByteBuf message) {
                                    message.clear();
                                    if (closeAfterFirstRequest) {
                                        context.close();
                                    }
                                }
                            });
                        }
                    });
            try {
                final ChannelFuture bindFuture = bootstrap.bind("127.0.0.1", 0).sync();
                this.channel = bindFuture.channel();
            } catch (final InterruptedException cause) {
                Thread.currentThread().interrupt();
                this.bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
                this.workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
                throw new IOException("failed to bind silent test server", cause);
            }
        }

        int getPort() {
            return ((InetSocketAddress) this.channel.localAddress()).getPort();
        }

        @Override
        public void close() {
            this.channel.close().awaitUninterruptibly();
            this.bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
            this.workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
        }
    }
}
