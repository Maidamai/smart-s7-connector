package io.github.maidamai.s7connector.impl.support;

import io.github.maidamai.s7connector.api.DaveArea;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocalS1500Server implements AutoCloseable {
    private static final int MAX_FRAME_LENGTH = 4096;
    private static final int TPKT_LENGTH_FIELD_OFFSET = 2;
    private static final int TPKT_LENGTH_FIELD_LENGTH = 2;
    private static final int TPKT_LENGTH_ADJUSTMENT = -4;
    private static final int TPKT_INITIAL_BYTES_TO_STRIP = 0;
    private static final int COTP_LENGTH = 3;
    private static final int PDU_START = 7;
    private static final int PDU_HEADER_LENGTH = 10;
    private static final int READ_PARAM_START = PDU_START + PDU_HEADER_LENGTH;
    private static final int READ_ITEM_START = READ_PARAM_START + 2;
    private static final int WRITE_ITEM_LENGTH = 12;
    private static final int WRITE_DATA_START = READ_PARAM_START + 2 + WRITE_ITEM_LENGTH;
    private static final int WRITE_PAYLOAD_START = WRITE_DATA_START + 4;
    private static final int DB_AREA_CODE = DaveArea.DB.getCode();

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel channel;
    private final int negotiatedPduLength;
    private final byte[] dbMemory;
    private final BlockingQueue<ReadRequest> readRequests;
    private final BlockingQueue<WriteRequest> writeRequests;
    private final ConcurrentLinkedQueue<Byte> scriptedWriteItemStatuses;
    private final ConcurrentLinkedQueue<ScriptedResponse> scriptedRawResponses;
    private final List<Integer> requestS7PduLengths;
    private final AtomicInteger readRequestCount;
    private final AtomicInteger writeRequestCount;

    public LocalS1500Server(final int negotiatedPduLength, final byte[] dbMemory) throws IOException {
        if (negotiatedPduLength <= 0) {
            throw new IllegalArgumentException("negotiatedPduLength must be positive: " + negotiatedPduLength);
        }
        if (dbMemory == null) {
            throw new IllegalArgumentException("dbMemory must not be null");
        }
        this.negotiatedPduLength = negotiatedPduLength;
        this.dbMemory = dbMemory.clone();
        this.readRequests = new LinkedBlockingQueue<>();
        this.writeRequests = new LinkedBlockingQueue<>();
        this.scriptedWriteItemStatuses = new ConcurrentLinkedQueue<>();
        this.scriptedRawResponses = new ConcurrentLinkedQueue<>();
        this.requestS7PduLengths = new CopyOnWriteArrayList<>();
        this.readRequestCount = new AtomicInteger(0);
        this.writeRequestCount = new AtomicInteger(0);
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup(1);
        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(this.bossGroup, this.workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel socketChannel) {
                        socketChannel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                MAX_FRAME_LENGTH,
                                TPKT_LENGTH_FIELD_OFFSET,
                                TPKT_LENGTH_FIELD_LENGTH,
                                TPKT_LENGTH_ADJUSTMENT,
                                TPKT_INITIAL_BYTES_TO_STRIP));
                        socketChannel.pipeline().addLast(new RequestHandler());
                    }
                });
        try {
            final ChannelFuture bindFuture = bootstrap.bind("127.0.0.1", 0).sync();
            this.channel = bindFuture.channel();
        } catch (final InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IOException("failed to bind local S7-1500 server", cause);
        }
    }

    public int getPort() {
        return ((InetSocketAddress) this.channel.localAddress()).getPort();
    }

    public int getReadRequestCount() {
        return this.readRequestCount.get();
    }

    public int getWriteRequestCount() {
        return this.writeRequestCount.get();
    }

    /**
     * S7 PDU lengths (without TPKT/COTP) of all read and write requests the
     * server has seen, in order; lets tests assert no request exceeded the
     * negotiated PDU length.
     */
    public List<Integer> getRequestS7PduLengths() {
        return this.requestS7PduLengths;
    }

    /**
     * Queues a raw response frame (full TPKT frame) that is sent instead of
     * the generated response for the next request. With
     * {@code echoRequestNumber} the scripted frame's PDU number is patched
     * from the incoming request, so tests can inject malformed frames that
     * still pass the reference check, or valid frames with a wrong reference.
     */
    public void queueRawResponse(final byte[] tpktFrame, final boolean echoRequestNumber) {
        this.scriptedRawResponses.add(new ScriptedResponse(tpktFrame.clone(), echoRequestNumber));
    }

    /**
     * Queues an item status the next write response shall carry instead of
     * 0xFF; the write is then NOT applied to the local memory, mirroring a
     * PLC that rejects the item.
     */
    public void queueWriteItemStatus(final int itemStatus) {
        this.scriptedWriteItemStatuses.add((byte) itemStatus);
    }

    public ReadRequest takeReadRequest() throws InterruptedException {
        final ReadRequest request = this.readRequests.poll(1, TimeUnit.SECONDS);
        if (request == null) {
            throw new AssertionError("local S7-1500 server did not receive a read request");
        }
        return request;
    }

    public WriteRequest takeWriteRequest() throws InterruptedException {
        final WriteRequest request = this.writeRequests.poll(1, TimeUnit.SECONDS);
        if (request == null) {
            throw new AssertionError("local S7-1500 server did not receive a write request");
        }
        return request;
    }

    @Override
    public void close() {
        this.channel.close().awaitUninterruptibly();
        this.bossGroup.shutdownGracefully().awaitUninterruptibly();
        this.workerGroup.shutdownGracefully().awaitUninterruptibly();
    }

    private byte[] responseFor(final byte[] request) {
        if (isIsoConnectRequest(request)) {
            return tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80});
        }
        final ScriptedResponse scripted = this.scriptedRawResponses.poll();
        if (scripted != null) {
            if (scripted.echoRequestNumber && request.length > PDU_START + 5
                    && scripted.frame.length > PDU_START + 5) {
                scripted.frame[PDU_START + 4] = request[PDU_START + 4];
                scripted.frame[PDU_START + 5] = request[PDU_START + 5];
            }
            return scripted.frame;
        }
        if (isPduNegotiationRequest(request)) {
            return pduNegotiationResponse(request, this.negotiatedPduLength);
        }
        if (isWriteRequest(request)) {
            return writeResponse(request);
        }
        if (isReadRequest(request)) {
            return readResponse(request);
        }
        return tpkt(new byte[]{0x02, (byte) 0xf0, (byte) 0x80});
    }

    private static boolean isIsoConnectRequest(final byte[] request) {
        return request.length > 5 && request[4] == 0x11 && request[5] == (byte) 0xe0;
    }

    private static boolean isPduNegotiationRequest(final byte[] request) {
        return request.length > READ_PARAM_START && request[PDU_START] == 0x32
                && request[READ_PARAM_START] == (byte) 0xf0;
    }

    private static boolean isReadRequest(final byte[] request) {
        return request.length > READ_ITEM_START + 11 && request[PDU_START] == 0x32
                && request[READ_PARAM_START] == 0x04;
    }

    private static boolean isWriteRequest(final byte[] request) {
        return request.length > WRITE_PAYLOAD_START && request[PDU_START] == 0x32
                && request[READ_PARAM_START] == 0x05;
    }

    private byte[] readResponse(final byte[] request) {
        final int length = readUnsignedWord(request, READ_ITEM_START + 4);
        final int dbNumber = readUnsignedWord(request, READ_ITEM_START + 6);
        final int area = request[READ_ITEM_START + 8] & 0xFF;
        final int bitAddress = readUnsigned24(request, READ_ITEM_START + 9);
        final int offset = bitAddress / 8;
        if (area != DB_AREA_CODE) {
            throw new IllegalArgumentException("only DB read is supported by local S7-1500 server, area=" + area);
        }
        if (offset < 0 || offset + length > this.dbMemory.length) {
            throw new IllegalArgumentException("read is outside local DB memory, dbNumber=" + dbNumber
                    + ", offset=" + offset + ", length=" + length + ", memoryLength=" + this.dbMemory.length);
        }
        this.requestS7PduLengths.add(Integer.valueOf(request.length - PDU_START));
        this.readRequestCount.incrementAndGet();
        this.readRequests.add(new ReadRequest(dbNumber, offset, length));
        final byte[] data = new byte[length];
        System.arraycopy(this.dbMemory, offset, data, 0, length);
        return readResponseFrame(request, data);
    }

    private byte[] writeResponse(final byte[] request) {
        final int length = readUnsignedWord(request, READ_ITEM_START + 4);
        final int dbNumber = readUnsignedWord(request, READ_ITEM_START + 6);
        final int area = request[READ_ITEM_START + 8] & 0xFF;
        final int bitAddress = readUnsigned24(request, READ_ITEM_START + 9);
        final int offset = bitAddress / 8;
        if (area != DB_AREA_CODE) {
            throw new IllegalArgumentException("only DB write is supported by local S7-1500 server, area=" + area);
        }
        if (length < 0 || WRITE_PAYLOAD_START + length > request.length) {
            throw new IllegalArgumentException("write payload exceeds the received frame, length=" + length);
        }
        if (bitAddress % 8 != 0) {
            throw new IllegalArgumentException("only byte-aligned writes are supported, bitAddress=" + bitAddress);
        }
        this.requestS7PduLengths.add(Integer.valueOf(request.length - PDU_START));
        this.writeRequestCount.incrementAndGet();
        this.writeRequests.add(new WriteRequest(dbNumber, offset, length));

        final Byte scriptedStatus = this.scriptedWriteItemStatuses.poll();
        final byte itemStatus = scriptedStatus == null ? (byte) 0xFF : scriptedStatus.byteValue();
        if (itemStatus == (byte) 0xFF) {
            if (offset < 0 || offset + length > this.dbMemory.length) {
                throw new IllegalArgumentException("write is outside local DB memory, dbNumber=" + dbNumber
                        + ", offset=" + offset + ", length=" + length + ", memoryLength=" + this.dbMemory.length);
            }
            System.arraycopy(request, WRITE_PAYLOAD_START, this.dbMemory, offset, length);
        }
        return writeResponseFrame(request, itemStatus);
    }

    private static byte[] readResponseFrame(final byte[] request, final byte[] data) {
        final int dataLength = 4 + data.length;
        final byte[] payload = new byte[COTP_LENGTH + 12 + 2 + dataLength];
        payload[0] = 0x02;
        payload[1] = (byte) 0xf0;
        payload[2] = (byte) 0x80;
        final int pdu = COTP_LENGTH;
        payload[pdu] = 0x32;
        payload[pdu + 1] = 0x03;
        payload[pdu + 4] = request[PDU_START + 4];
        payload[pdu + 5] = request[PDU_START + 5];
        writeUnsignedWord(payload, pdu + 6, 2);
        writeUnsignedWord(payload, pdu + 8, dataLength);
        payload[pdu + 12] = 0x04;
        payload[pdu + 13] = 0x01;
        final int dataStart = pdu + 14;
        payload[dataStart] = (byte) 0xff;
        payload[dataStart + 1] = 0x04;
        writeUnsignedWord(payload, dataStart + 2, data.length * 8);
        System.arraycopy(data, 0, payload, dataStart + 4, data.length);
        return tpkt(payload);
    }

    private static byte[] writeResponseFrame(final byte[] request, final byte itemStatus) {
        final byte[] payload = new byte[COTP_LENGTH + 12 + 2 + 1];
        payload[0] = 0x02;
        payload[1] = (byte) 0xf0;
        payload[2] = (byte) 0x80;
        final int pdu = COTP_LENGTH;
        payload[pdu] = 0x32;
        payload[pdu + 1] = 0x03;
        payload[pdu + 4] = request[PDU_START + 4];
        payload[pdu + 5] = request[PDU_START + 5];
        writeUnsignedWord(payload, pdu + 6, 2);
        writeUnsignedWord(payload, pdu + 8, 1);
        payload[pdu + 12] = 0x05;
        payload[pdu + 13] = 0x01;
        payload[pdu + 14] = itemStatus;
        return tpkt(payload);
    }

    private static byte[] pduNegotiationResponse(final byte[] request, final int pduLength) {
        final byte[] payload = new byte[23];
        payload[0] = 0x02;
        payload[1] = (byte) 0xf0;
        payload[2] = (byte) 0x80;
        payload[3] = 0x32;
        payload[4] = 0x03;
        payload[7] = request[PDU_START + 4];
        payload[8] = request[PDU_START + 5];
        payload[10] = 0x08;
        payload[15] = (byte) 0xf0;
        payload[16] = 0x00;
        payload[17] = 0x00;
        payload[18] = 0x01;
        payload[19] = 0x00;
        payload[20] = 0x01;
        final byte[] frame = tpkt(payload);
        writeUnsignedWord(frame, 25, pduLength);
        return frame;
    }

    private static byte[] tpkt(final byte[] payload) {
        final byte[] frame = new byte[payload.length + 4];
        frame[0] = 0x03;
        frame[1] = 0x00;
        writeUnsignedWord(frame, 2, frame.length);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        return frame;
    }

    private static int readUnsignedWord(final byte[] bytes, final int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static int readUnsigned24(final byte[] bytes, final int offset) {
        return ((bytes[offset] & 0xFF) << 16)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | (bytes[offset + 2] & 0xFF);
    }

    private static void writeUnsignedWord(final byte[] bytes, final int offset, final int value) {
        bytes[offset] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 1] = (byte) (value & 0xFF);
    }

    public static byte[] incrementingDbMemory(final int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        final byte[] memory = new byte[length];
        for (int i = 0; i < memory.length; i++) {
            memory[i] = (byte) (i & 0xFF);
        }
        return memory;
    }

    private final class RequestHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(final ChannelHandlerContext context, final ByteBuf message) {
            final byte[] request = new byte[message.readableBytes()];
            message.readBytes(request);
            context.writeAndFlush(Unpooled.wrappedBuffer(responseFor(request)));
        }
    }

    public static final class ReadRequest {
        private final int dbNumber;
        private final int offset;
        private final int length;

        private ReadRequest(final int dbNumber, final int offset, final int length) {
            this.dbNumber = dbNumber;
            this.offset = offset;
            this.length = length;
        }

        public int getDbNumber() {
            return this.dbNumber;
        }

        public int getOffset() {
            return this.offset;
        }

        public int getLength() {
            return this.length;
        }
    }

    public static final class WriteRequest {
        private final int dbNumber;
        private final int offset;
        private final int length;

        private WriteRequest(final int dbNumber, final int offset, final int length) {
            this.dbNumber = dbNumber;
            this.offset = offset;
            this.length = length;
        }

        public int getDbNumber() {
            return this.dbNumber;
        }

        public int getOffset() {
            return this.offset;
        }

        public int getLength() {
            return this.length;
        }
    }

    private static final class ScriptedResponse {
        private final byte[] frame;
        private final boolean echoRequestNumber;

        private ScriptedResponse(final byte[] frame, final boolean echoRequestNumber) {
            this.frame = frame;
            this.echoRequestNumber = echoRequestNumber;
        }
    }
}
