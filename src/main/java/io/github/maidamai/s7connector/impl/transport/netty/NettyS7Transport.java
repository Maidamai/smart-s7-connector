package io.github.maidamai.s7connector.impl.transport.netty;

import io.github.maidamai.s7connector.impl.transport.S7Transport;
import io.github.maidamai.s7connector.impl.transport.S7TransportConfig;
import io.github.maidamai.s7connector.impl.transport.S7TransportException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty based S7 transport.
 *
 * <p>Terminal-state contract: only a fully successful
 * {@link #writeAndRead(byte[], int)} exchange leaves the transport usable.
 * After any of the following outcomes the transport is permanently closed
 * ({@link #isClosed()} returns {@code true}, the underlying channel and
 * event loop are shut down, and any pending response future is completed
 * exceptionally) and every subsequent {@link #writeAndRead(byte[], int)}
 * call fails with an {@link S7TransportException} ("channel is not
 * connected"):</p>
 *
 * <ul>
 *   <li>a write timeout ({@code writeTimeout}),</li>
 *   <li>a read timeout ({@code readTimeout}),</li>
 *   <li>the waiting thread was interrupted ({@code read}/{@code write}
 *       with an {@link InterruptedException} cause; the interrupt status
 *       is restored before the exception is thrown),</li>
 *   <li>the remote end closed the channel ({@code channelInactive}),</li>
 *   <li>the initial {@link #connect()} failed, or</li>
 *   <li>{@link #close()} was called explicitly.</li>
 * </ul>
 *
 * <p>All of these are terminal: a transport that failed, timed out, was
 * interrupted or was closed must be discarded and replaced by a new
 * instance. There is no automatic reconnect.</p>
 */
public final class NettyS7Transport implements S7Transport {
    private static final int MAX_FRAME_LENGTH = 2048;
    private static final int TPKT_LENGTH_FIELD_OFFSET = 2;
    private static final int TPKT_LENGTH_FIELD_LENGTH = 2;
    private static final int TPKT_LENGTH_ADJUSTMENT = -4;
    private static final int TPKT_INITIAL_BYTES_TO_STRIP = 0;
    private static final int SHUTDOWN_TIMEOUT_MILLIS = 1000;

    private final S7TransportConfig config;
    private final NettyS7ClientHandler clientHandler;
    private final AtomicBoolean closed;
    private EventLoopGroup eventLoopGroup;
    private Channel channel;

    public NettyS7Transport(final S7TransportConfig config) {
        this.config = config;
        this.clientHandler = new NettyS7ClientHandler();
        this.closed = new AtomicBoolean(false);
    }

    @Override
    public void connect() throws IOException {
        if (this.channel != null && this.channel.isActive()) {
            return;
        }
        if (this.closed.get()) {
            // terminal state: a closed or failed transport is never revived;
            // callers must create a new instance (see docs/api-contract.md)
            throw new S7TransportException("connect", this.config, 0,
                    "transport is closed; create a new instance instead of reconnecting");
        }
        this.eventLoopGroup = new NioEventLoopGroup(1);
        final Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(this.eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, this.config.getConnectTimeoutMillis())
                .option(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel socketChannel) {
                        socketChannel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                MAX_FRAME_LENGTH,
                                TPKT_LENGTH_FIELD_OFFSET,
                                TPKT_LENGTH_FIELD_LENGTH,
                                TPKT_LENGTH_ADJUSTMENT,
                                TPKT_INITIAL_BYTES_TO_STRIP));
                        socketChannel.pipeline().addLast(NettyS7Transport.this.clientHandler);
                    }
                });
        try {
            this.channel = bootstrap.connect(this.config.getHost(), this.config.getPort()).sync().channel();
        } catch (final InterruptedException cause) {
            Thread.currentThread().interrupt();
            this.close();
            throw new S7TransportException("connect", this.config, 0, cause);
        } catch (final Exception cause) {
            // Netty rethrows checked connect failures (e.g. ConnectException)
            // directly, so catch Exception, not only RuntimeException.
            this.close();
            throw new S7TransportException("connect", this.config, 0, cause);
        }
    }

    @Override
    public byte[] writeAndRead(final byte[] request, final int requestLength) throws IOException {
        if (request == null) {
            throw new S7TransportException("writeAndRead", this.config, requestLength, "request must not be null");
        }
        if (requestLength <= 0 || requestLength > request.length) {
            throw new S7TransportException("writeAndRead", this.config, requestLength, "invalid requestLength");
        }
        this.ensureConnected(requestLength);
        final CompletableFuture<byte[]> responseFuture = this.clientHandler.prepareResponseFuture();
        final ChannelFuture writeFuture = this.channel.writeAndFlush(Unpooled.wrappedBuffer(request, 0, requestLength));
        this.awaitWriteCompletion(writeFuture, requestLength);
        return this.awaitResponse(responseFuture, requestLength);
    }

    private void awaitWriteCompletion(final ChannelFuture writeFuture, final int requestLength) throws IOException {
        try {
            if (!writeFuture.await(this.config.getReadTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                this.failPendingAndClose(new TimeoutException("S7 transport write timed out"));
                throw new S7TransportException("writeTimeout", this.config, requestLength, new TimeoutException("write timed out"));
            }
        } catch (final InterruptedException cause) {
            Thread.currentThread().interrupt();
            this.failPendingAndClose(cause);
            throw new S7TransportException("write", this.config, requestLength, cause);
        }
        if (!writeFuture.isSuccess()) {
            final Throwable cause = writeFuture.cause() == null ? new IOException("write failed without cause") : writeFuture.cause();
            this.failPendingAndClose(cause);
            throw new S7TransportException("write", this.config, requestLength, cause);
        }
    }

    private byte[] awaitResponse(final CompletableFuture<byte[]> responseFuture, final int requestLength) throws IOException {
        try {
            return responseFuture.get(this.config.getReadTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException cause) {
            this.close();
            throw new S7TransportException("readTimeout", this.config, requestLength, cause);
        } catch (final InterruptedException cause) {
            Thread.currentThread().interrupt();
            this.failPendingAndClose(cause);
            throw new S7TransportException("read", this.config, requestLength, cause);
        } catch (final ExecutionException cause) {
            if (this.channel == null || !this.channel.isActive()) {
                // the future failed because the channel died (remote close
                // or transport error): the transport is unusable, close it
                this.closeQuietly();
            }
            throw new S7TransportException("read", this.config, requestLength, cause.getCause());
        }
    }

    private void failPendingAndClose(final Throwable cause) {
        this.clientHandler.failPendingResponse(cause);
        try {
            this.close();
        } catch (final IOException closeFailure) {
            cause.addSuppressed(closeFailure);
        }
    }

    private void closeQuietly() {
        try {
            this.close();
        } catch (final IOException closeFailure) {
            // closing after a channel failure is best effort; the read
            // failure already reported the original problem
        }
    }

    @Override
    public void close() throws IOException {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        IOException closeFailure = null;
        if (this.channel != null) {
            try {
                this.channel.close().sync();
            } catch (final InterruptedException cause) {
                Thread.currentThread().interrupt();
                closeFailure = new S7TransportException("closeChannel", this.config, 0, cause);
            }
        }
        if (this.eventLoopGroup != null) {
            try {
                this.eventLoopGroup.shutdownGracefully(0, SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).sync();
            } catch (final InterruptedException cause) {
                Thread.currentThread().interrupt();
                closeFailure = new S7TransportException("closeEventLoop", this.config, 0, cause);
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    public boolean isClosed() {
        return this.closed.get();
    }

    boolean hasPendingResponse() {
        return this.clientHandler.hasPendingResponse();
    }

    private void ensureConnected(final int requestLength) throws IOException {
        if (this.channel == null || !this.channel.isActive()) {
            throw new S7TransportException("writeAndRead", this.config, requestLength, "channel is not connected");
        }
    }
}
