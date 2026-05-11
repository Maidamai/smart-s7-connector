package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.exception.S7Exception;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S7TCPConnectionIntegrationTest {
    @Test
    void factoryBuildsNettyConnectionAndInjectsNegotiatedPduWindow() throws IOException, InterruptedException {
        try (TestNettyServer server = TestNettyServer.responding(isoConnectResponse(), pduNegotiationResponse(480))) {
            final S7Connector connector = S7ConnectorFactory.buildTCPConnector()
                    .withHost("127.0.0.1")
                    .withPort(server.getPort())
                    .withTimeout(1000)
                    .build();

            final S7TCPConnection connection = (S7TCPConnection) connector;
            assertEquals(462, connection.getMaxReadBytes(),
                    "PDU negotiation value should be injected into the read splitting window");
            assertFalse(connection.isTransportClosed(), "connection should keep Netty transport open after construction");
            assertTrue(server.takeRequest().length > 0, "server must receive the ISO connect request");
            assertTrue(server.takeRequest().length > 0, "server must receive the PDU negotiation request");

            connection.close();

            assertTrue(connection.isTransportClosed(), "close should release the Netty transport");
        }
    }

    @Test
    void constructionFailureClosesConnectedTransport() throws IOException, InterruptedException {
        try (TestNettyServer server = TestNettyServer.responding(isoConnectResponse())) {
            assertThrows(S7Exception.class, () -> S7ConnectorFactory.buildTCPConnector()
                    .withHost("127.0.0.1")
                    .withPort(server.getPort())
                    .withTimeout(100)
                    .build(), "missing PDU negotiation response should fail construction");

            assertTrue(server.takeClientDisconnect(), "construction failure should close the connected Netty transport");
        }
    }

    private static byte[] isoConnectResponse() {
        return tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80});
    }

    private static byte[] pduNegotiationResponse(final int pduLength) {
        final byte[] payload = new byte[23];
        payload[0] = 0x02;
        payload[1] = (byte) 0xf0;
        payload[2] = (byte) 0x80;
        payload[3] = 0x32;
        payload[4] = 0x03;
        payload[8] = 0x01;
        payload[9] = 0x00;
        payload[10] = 0x08;
        payload[15] = (byte) 0xf0;
        payload[16] = 0x00;
        payload[17] = 0x00;
        payload[18] = 0x01;
        payload[19] = 0x00;
        payload[20] = 0x01;
        final byte[] frame = tpkt(payload);
        frame[25] = (byte) (pduLength / 0x100);
        frame[26] = (byte) (pduLength % 0x100);
        return frame;
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
        private final BlockingQueue<Boolean> clientDisconnects;

        private TestNettyServer(final Queue<byte[]> responses) throws IOException {
            this.bossGroup = new NioEventLoopGroup(1);
            this.workerGroup = new NioEventLoopGroup(1);
            this.receivedRequests = new LinkedBlockingQueue<>();
            this.clientDisconnects = new LinkedBlockingQueue<>();
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
                                }

                                @Override
                                public void channelInactive(final ChannelHandlerContext context) {
                                    TestNettyServer.this.clientDisconnects.add(Boolean.TRUE);
                                    context.fireChannelInactive();
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
            return new TestNettyServer(new ArrayDeque<>(Arrays.asList(responses)));
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

        private boolean takeClientDisconnect() throws InterruptedException {
            final Boolean disconnected = this.clientDisconnects.poll(2, TimeUnit.SECONDS);
            if (disconnected == null) {
                throw new AssertionError("server did not observe client disconnect");
            }
            return disconnected.booleanValue();
        }

        @Override
        public void close() {
            this.channel.close().awaitUninterruptibly();
            this.bossGroup.shutdownGracefully().awaitUninterruptibly();
            this.workerGroup.shutdownGracefully().awaitUninterruptibly();
        }
    }
}
