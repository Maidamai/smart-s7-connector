

/*
 Part of Libnodave, a free communication libray for Siemens S7

 (C) Thomas Hergenhahn (thomas.hergenhahn@web.de) 2005.

 Libnodave is free software; you can redistribute it and/or modify
 it under the terms of the GNU Library General Public License as published by
 the Free Software Foundation; either version 2, or (at your option)
 any later version.

 Libnodave is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU Library General Public License
 along with this; see the file COPYING.  If not, write to
 the Free Software Foundation, 675 Mass Ave, Cambridge, MA 02139, USA.
*/
package io.github.maidamai.s7connector.impl.nodave;

import io.github.maidamai.s7connector.impl.transport.S7Transport;

import java.io.IOException;

/**
 * The Class TCPConnection.
 *
 * @author Thomas Rudin
 */
public final class TCPConnection extends S7Connection {

    /**
     * The rack.
     */
    int rack;

    /**
     * The slot.
     */
    int slot;
    private final S7Transport transport;
    private byte[] lastIsoResponse;

    /**
     * Instantiates a new TCP connection.
     *
     * @param ifa  the plc interface
     * @param rack the rack
     * @param slot the slot
     */
    public TCPConnection(final PLCinterface ifa, final int rack, final int slot) {
        this(ifa, rack, slot, null);
    }

    public TCPConnection(final PLCinterface ifa, final int rack, final int slot, final S7Transport transport) {
        super(ifa);
        this.rack = rack;
        this.slot = slot;
        this.transport = transport;
        this.PDUstartIn = 7;
        this.PDUstartOut = 7;
    }

    /**
     * We have our own connectPLC(), but no disconnect() Open connection to a
     * PLC. This assumes that dc is initialized by daveNewConnection and is not
     * yet used. (or reused for the same PLC ?)
     *
     * @return the int
     */
    public int connectPLC() throws IOException {
        int packetLength;
        if (iface.protocol == Nodave.PROTOCOL_ISOTCP243) {
            final byte[] b243 = {
                    (byte) 0x11, (byte) 0xE0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                    (byte) 0xC1, (byte) 0x02, (byte) 0x4D, (byte) 0x57, (byte) 0xC2, (byte) 0x02, (byte) 0x4D, (byte) 0x57,
                    (byte) 0xC0, (byte) 0x01, (byte) 0x09
            };
            System.arraycopy(b243, 0, this.msgOut, 4, b243.length);
            packetLength = b243.length;
        } else {
            final byte[] b4 = {
                    (byte) 0x11, (byte) 0xE0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00,
                    (byte) 0xC1, (byte) 0x02, (byte) 0x01, (byte) 0x00, (byte) 0xC2, (byte) 0x02, (byte) 0x01, (byte) 0x02,
                    (byte) 0xC0, (byte) 0x01, (byte) 0x09
            };
            System.arraycopy(b4, 0, this.msgOut, 4, b4.length);
            this.msgOut[17] = (byte) (this.rack + 1);
            this.msgOut[18] = (byte) this.slot;
            packetLength = b4.length;
        }
        this.sendISOPacket(packetLength);
        this.readISOPacket();
        /*
         * PDU p = new PDU(msgOut, 7); p.initHeader(1); p.addParam(b61);
         * exchange(p); return (0);
         */
        return this.negPDUlengthRequest();
    }

    /**
     * {@inheritDoc}
     *
     * Every request gets a sequence number that the PLC must echo. Frames
     * that cannot be attributed to the current request (mismatched PDU
     * number, non-DT COTP header, protocol violations, truncated PDUs)
     * poison the stream, so the transport is closed and the error code is
     * returned; a proper PLC error answer (type 2/3 header error) does not
     * close the connection.
     */
    @Override
    public int exchange(final PDU p1) throws IOException {
        this.messageNumber++;
        if (this.messageNumber > 0xFFFF) {
            this.messageNumber = 1;
        }
        p1.setNumber(this.messageNumber);
        this.msgOut[4] = (byte) 0x02;
        this.msgOut[5] = (byte) 0xf0;
        this.msgOut[6] = (byte) 0x80;
        this.sendISOPacket(3 + p1.hlen + p1.plen + p1.dlen);
        this.readISOPacket();
        if (this.answLen < this.PDUstartIn + 10) {
            this.closeTransportAfterInvalidResponse();
            return Nodave.RESULT_SHORT_PACKET;
        }
        if (this.msgIn[4] != 0x02 || this.msgIn[5] != (byte) 0xF0) {
            this.closeTransportAfterInvalidResponse();
            return Nodave.RESULT_CANNOT_EVALUATE_PDU;
        }
        final PDU p2 = new PDU(this.msgIn, this.PDUstartIn);
        final int res = p2.setupReceivedPDU(this.answLen - this.PDUstartIn);
        if (res == Nodave.RESULT_SHORT_PACKET || res == Nodave.RESULT_CANNOT_EVALUATE_PDU) {
            this.closeTransportAfterInvalidResponse();
            return res;
        }
        if (p2.getNumber() != this.messageNumber) {
            this.closeTransportAfterInvalidResponse();
            return Nodave.RESULT_UNEXPECTED_REFERENCE;
        }
        return res;
    }

    private void closeTransportAfterInvalidResponse() {
        if (this.transport == null) {
            return;
        }
        try {
            this.transport.close();
        } catch (final IOException closeFailure) {
            // the stream is already untrustworthy; the parse error is the
            // relevant failure, closing failures are not surfaced here
        }
    }

    /**
     * Read iso packet.
     *
     * @return the number of bytes actually received
     */
    protected int readISOPacket() {
        if (this.lastIsoResponse == null || this.lastIsoResponse.length == 0) {
            this.answLen = 0;
            return 0;
        }
        System.arraycopy(this.lastIsoResponse, 0, this.msgIn, 0, this.lastIsoResponse.length);
        final int responseLength = this.lastIsoResponse.length;
        this.lastIsoResponse = null;
        this.answLen = responseLength;
        return responseLength;
    }

    /**
     * Send iso packet.
     *
     * @param size the size
     * @return the int
     */
    protected int sendISOPacket(int size) throws IOException {
        size += 4;
        this.msgOut[0] = (byte) 0x03;
        this.msgOut[1] = (byte) 0x0;
        this.msgOut[2] = (byte) (size / 0x100);
        this.msgOut[3] = (byte) (size % 0x100);
        /*
         * if (messageNumber == 0) { messageNumber = 1; msgOut[11] = (byte)
         * ((messageNumber + 1) & 0xff); messageNumber++; messageNumber &= 0xff;
         * //!! }
         */

        if (this.transport == null) {
            throw new IOException("S7 transport is required for ISO packet exchange");
        }
        this.lastIsoResponse = this.transport.writeAndRead(this.msgOut, size);
        return 0;
    }
}
