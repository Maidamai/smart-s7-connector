package io.github.maidamai.s7connector.impl.transport.netty;

import io.github.maidamai.s7connector.impl.nodave.PDU;
import io.github.maidamai.s7connector.impl.nodave.PLCinterface;
import io.github.maidamai.s7connector.impl.nodave.TCPConnection;
import io.github.maidamai.s7connector.impl.transport.S7TransportConfig;
import io.github.maidamai.s7connector.impl.transport.S7TransportException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyS7TransportIntegrationTest {
    @Test
    void writesRequestAndReadsFullTpktResponse() throws IOException, InterruptedException {
        final byte[] response = tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80, 0x11, 0x22});
        try (TestNettyServer server = TestNettyServer.responding(response)) {
            final NettyS7Transport transport = new NettyS7Transport(config(server.getPort(), 1000));
            transport.connect();
            final byte[] request = tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80, 0x55});

            final byte[] actualResponse = transport.writeAndRead(request, request.length);

            assertArrayEquals(request, server.takeRequest(), "server should receive the exact TPKT request bytes");
            assertArrayEquals(response, actualResponse, "client should receive the exact full TPKT response bytes");
            transport.close();
            assertTrue(transport.isClosed(), "transport should report closed after close");
        }
    }

    @Test
    void readTimesOutWhenServerDoesNotRespond() throws IOException {
        try (TestNettyServer server = TestNettyServer.responding()) {
            final NettyS7Transport transport = new NettyS7Transport(config(server.getPort(), 100));
            transport.connect();
            final byte[] request = tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80, 0x66});

            assertThrows(S7TransportException.class, () -> transport.writeAndRead(request, request.length),
                    "missing server response should fail with a transport timeout");
            assertTrue(transport.isClosed(), "timeout should close the transport");
        }
    }

    @Test
    void closeIsIdempotent() throws IOException {
        try (TestNettyServer server = TestNettyServer.responding()) {
            final NettyS7Transport transport = new NettyS7Transport(config(server.getPort(), 1000));
            transport.connect();

            assertDoesNotThrow(transport::close, "first close should succeed");
            assertDoesNotThrow(transport::close, "second close should be safe");
        }
    }

    @Test
    void remoteCloseFailsFastAndClearsPendingResponse() throws IOException, InterruptedException {
        try (TestNettyServer server = TestNettyServer.closingAfterRequest()) {
            final NettyS7Transport transport = new NettyS7Transport(config(server.getPort(), 2000));
            transport.connect();
            final byte[] request = tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80, 0x44});
            final long startedAtNanos = System.nanoTime();

            assertThrows(S7TransportException.class, () -> transport.writeAndRead(request, request.length),
                    "remote close should fail the pending read with a transport exception");

            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            assertTrue(elapsedMillis < 1500, "remote close should not wait for the full read timeout");
            assertArrayEquals(request, server.takeRequest(), "server should receive the request before closing");
            assertFalse(transport.hasPendingResponse(), "remote close should clear the pending response future");
            assertThrows(S7TransportException.class, () -> transport.writeAndRead(request, request.length),
                    "a write attempt on a closed channel should fail without stale pending state");
            transport.close();
        }
    }

    @Test
    void tcpConnectionExchangeUsesTransportIsoPacketBoundary() throws IOException, InterruptedException {
        final byte[] response = tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80, 0x32, 0x01, 0x00, 0x00});
        try (TestNettyServer server = TestNettyServer.responding(response)) {
            final NettyS7Transport transport = new NettyS7Transport(config(server.getPort(), 1000));
            transport.connect();
            final TCPConnection connection = new TCPConnection(new PLCinterface(4), 0, 2, transport);
            final PDU pdu = new PDU(connection.msgOut, connection.PDUstartOut);
            pdu.initHeader(1);
            pdu.addParam(new byte[]{0x04, 0x00});

            connection.exchange(pdu);

            final byte[] request = server.takeRequest();
            assertTrue(request.length >= 7, "ISO packet request should include TPKT and COTP headers");
            assertArrayEquals(Arrays.copyOf(response, response.length), Arrays.copyOf(connection.msgIn, response.length),
                    "TCPConnection should copy the transport response into msgIn");
            assertArrayEquals(new byte[]{0x03, 0x00}, Arrays.copyOf(request, 2), "request must begin with a TPKT header");
            assertArrayEquals(new byte[]{0x02, (byte) 0xf0, (byte) 0x80}, Arrays.copyOfRange(request, 4, 7),
                    "request must include the ISO-on-TCP data COTP header");
            transport.close();
        }
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

    private static final class TestNettyServer implements AutoCloseable {
        private static final int MAX_FRAME_LENGTH = 2048;
        private final EventLoopGroup bossGroup;
        private final EventLoopGroup workerGroup;
        private final Channel channel;
        private final BlockingQueue<byte[]> receivedRequests;
        private final boolean closeAfterRequest;

        private TestNettyServer(final Queue<byte[]> responses, final boolean closeAfterRequest) throws IOException {
            this.bossGroup = new NioEventLoopGroup(1);
            this.workerGroup = new NioEventLoopGroup(1);
            this.receivedRequests = new LinkedBlockingQueue<>();
            this.closeAfterRequest = closeAfterRequest;
            final ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(this.bossGroup, this.workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel socketChannel) {
                            socketChannel.pipeline().addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 2, 2, -4, 0));
                            socketChannel.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                @Override
                                protected void channelRead0(final ChannelHandlerContext context, final ByteBuf message) {
                                    final byte[] request = new byte[message.readableBytes()];
                                    message.readBytes(request);
                                    TestNettyServer.this.receivedRequests.add(request);
                                    final byte[] response = responses.poll();
                                    if (response != null) {
                                        context.writeAndFlush(Unpooled.wrappedBuffer(response));
                                    }
                                    if (TestNettyServer.this.closeAfterRequest) {
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
                throw new IOException("failed to bind test Netty server", cause);
            }
        }

        private static TestNettyServer responding(final byte[]... responses) throws IOException {
            return new TestNettyServer(new ArrayDeque<>(Arrays.asList(responses)), false);
        }

        private static TestNettyServer closingAfterRequest() throws IOException {
            return new TestNettyServer(new ArrayDeque<byte[]>(), true);
        }

        private int getPort() {
            return ((InetSocketAddress) this.channel.localAddress()).getPort();
        }

        private byte[] takeRequest() throws InterruptedException {
            final byte[] request = this.receivedRequests.poll(1, TimeUnit.SECONDS);
            if (request == null) {
                throw new AssertionError("server did not receive request bytes");
            }
            return request;
        }

        @Override
        public void close() {
            this.channel.close().awaitUninterruptibly();
            this.bossGroup.shutdownGracefully().awaitUninterruptibly();
            this.workerGroup.shutdownGracefully().awaitUninterruptibly();
        }
    }
}
