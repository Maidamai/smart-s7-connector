package io.github.maidamai.s7connector.impl.transport.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class NettyS7ClientHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private final AtomicReference<CompletableFuture<byte[]>> pendingResponse;

    public NettyS7ClientHandler() {
        this.pendingResponse = new AtomicReference<>();
    }

    public CompletableFuture<byte[]> prepareResponseFuture() throws IOException {
        final CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
        if (!this.pendingResponse.compareAndSet(null, responseFuture)) {
            throw new IOException("S7 transport already has a pending request");
        }
        return responseFuture;
    }

    public void failPendingResponse(final Throwable cause) {
        this.completeExceptionally(cause);
    }

    boolean hasPendingResponse() {
        return this.pendingResponse.get() != null;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext context, final ByteBuf message) {
        final byte[] response = new byte[message.readableBytes()];
        message.readBytes(response);
        final CompletableFuture<byte[]> responseFuture = this.pendingResponse.getAndSet(null);
        if (responseFuture != null) {
            responseFuture.complete(response);
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
        this.completeExceptionally(cause);
        context.close();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext context) {
        this.completeExceptionally(new IOException("S7 transport channel closed"));
        context.fireChannelInactive();
    }

    private void completeExceptionally(final Throwable cause) {
        final CompletableFuture<byte[]> responseFuture = this.pendingResponse.getAndSet(null);
        if (responseFuture != null) {
            responseFuture.completeExceptionally(cause);
        }
    }
}
