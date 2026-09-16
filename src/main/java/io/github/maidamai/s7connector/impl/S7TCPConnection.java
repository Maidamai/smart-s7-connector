
package io.github.maidamai.s7connector.impl;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.exception.S7Exception;
import io.github.maidamai.s7connector.impl.nodave.Nodave;
import io.github.maidamai.s7connector.impl.nodave.PLCinterface;
import io.github.maidamai.s7connector.impl.nodave.TCPConnection;
import io.github.maidamai.s7connector.impl.transport.S7TransportConfig;
import io.github.maidamai.s7connector.impl.transport.netty.NettyS7Transport;
import io.github.maidamai.s7connector.impl.transport.netty.NettyS7TransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * TCP_Connection to a S7 PLC
 *
 * <p>Lifecycle: a connection object is single-use. After any failure
 * (construction/setup error, transport timeout, interrupted exchange,
 * remote disconnect) or after {@link #close()}, the object is unusable and
 * must be discarded; create a new {@code S7TCPConnection} instead. There is
 * no automatic reconnect and no supported way to revive a closed or failed
 * connection.</p>
 *
 * @author Thomas Rudin
 * @href http://libnodave.sourceforge.net/
 */
public final class S7TCPConnection extends S7BaseConnection {
    private static final Logger log = LoggerFactory.getLogger(S7TCPConnection.class);

    /**
     * The Connection
     */
    private TCPConnection dc;

    /**
     * The Interface
     */
    private PLCinterface di;

    /**
     * The Host to connect to
     */
    private final String host;

    /**
     * The port to connect to
     */
    private final int port;

    /**
     * Rack and slot number
     */
    private final int rack, slot;

    /**
     * Timeout number
     */
    private final int timeout;

    /**
     * The Socket
     */
    private NettyS7Transport transport;

    /**
     * The connect device type,such as S200
     */
    private SiemensPLCS plcType;

    /**
     * Creates a new Instance to the given host, rack, slot and port
     *
     * @param host
     * @throws S7Exception
     */
    public S7TCPConnection(final String host, final int rack, final int slot, final int port, final int timeout, final SiemensPLCS plcType) throws S7Exception {
        this.host = host;
        this.rack = rack;
        this.slot = slot;
        this.port = port;
        this.timeout = timeout;
        this.plcType = plcType;
        this.setupSocket();
    }

    @Override
    public void close() {
        try {
            if (this.transport != null) {
                this.transport.close();
            }
        } catch (final IOException e) {
            log.warn("Failed to close S7 TCP connection host={} port={} rack={} slot={}", this.host, this.port, this.rack, this.slot, e);
        }
    }

    /**
     * Sets up the socket
     */
    private void setupSocket() {
        NettyS7Transport createdTransport = null;
        try {
            //select the plc interface protocol by the plcsType
            int protocol;
            switch (this.plcType) {
                case S200:
                    protocol = Nodave.PROTOCOL_ISOTCP243;
                    break;
                case SNon200:
                case S300:
                case S400:
                case S1200:
                case S1500:
                case S200Smart:
                default:
                    protocol = Nodave.PROTOCOL_ISOTCP;
                    break;
            }
            final S7TransportConfig transportConfig = new S7TransportConfig(this.host, this.port, this.timeout, this.timeout);
            createdTransport = new NettyS7TransportFactory().create(transportConfig);
            createdTransport.connect();
            final PLCinterface createdInterface = new PLCinterface(protocol);
            final TCPConnection createdConnection = new TCPConnection(createdInterface, this.rack, this.slot, createdTransport);
            final int res = createdConnection.connectPLC();
            checkResult(res);

            super.init(createdConnection, createdConnection.getMaxPDUlength());
            this.transport = createdTransport;
            this.di = createdInterface;
            this.dc = createdConnection;
        } catch (final IOException e) {
            this.closeTransportAfterSetupFailure(createdTransport);
            throw new S7Exception("constructor host(" + this.host + ") port(" + this.port + ") rack(" + this.rack + ") slot(" + this.slot + ") timeout(" + this.timeout + ")", e);
        } catch (final RuntimeException e) {
            this.closeTransportAfterSetupFailure(createdTransport);
            throw new S7Exception("constructor host(" + this.host + ") port(" + this.port + ") rack(" + this.rack + ") slot(" + this.slot + ") timeout(" + this.timeout + ")", e);
        }

    }

    private void closeTransportAfterSetupFailure(final NettyS7Transport failedTransport) {
        if (failedTransport == null) {
            return;
        }
        try {
            failedTransport.close();
        } catch (final IOException closeFailure) {
            log.warn("Failed to close S7 TCP connection after setup failure host={} port={} rack={} slot={}", this.host, this.port, this.rack, this.slot, closeFailure);
        } finally {
            this.transport = null;
        }
    }

    public boolean isTransportClosed() {
        return this.transport == null || this.transport.isClosed();
    }

}
