
package io.github.maidamai.s7connector.api.factory;

import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.impl.S7TCPConnection;

/**
 * S7 connector factory, currently only for TCP connections
 *
 * <p>Parameter constraints and the connection lifecycle contract are
 * documented in {@code docs/api-contract.md}. In short: {@code rack} and
 * {@code slot} must be >= 0, the PLC type must not be null, and a built
 * connector is a single-use resource — after {@code close()} or after any
 * failure that invalidated the transport, build a new connector instead of
 * reusing the instance.</p>
 *
 * @author Thomas Rudin
 */
public class S7ConnectorFactory {

    /**
     * TCP Connection builder
     */
    public static class TCPConnectionBuilder {

        private String host;

        private SiemensPLCS plcsType;

        private int rack = 0, slot = 2, port = 102, timeout = 2000;

        TCPConnectionBuilder(SiemensPLCS type) {
            this.plcsType = type;
        }

        /**
         * Builds and connects a new connector with the given parameters.
         *
         * <p>The returned connector is a single-use resource: it holds one
         * TCP connection, may be shared across threads (requests are
         * serialized internally), must be closed, and is permanently unusable
         * after {@code close()} or a transport failure. See
         * {@code docs/api-contract.md}.</p>
         *
         * @return a new, connected connector
         * @throws IllegalArgumentException if the PLC type is null or rack/slot is negative
         */
        public S7Connector build() {
            if (this.plcsType == null) {
                throw new IllegalArgumentException("plcsType must not be null");
            }
            if (this.rack < 0) {
                throw new IllegalArgumentException("rack must not be negative: " + this.rack);
            }
            if (this.slot < 0) {
                throw new IllegalArgumentException("slot must not be negative: " + this.slot);
            }
            return new S7TCPConnection(this.host, this.rack, this.slot, this.port, this.timeout, this.plcsType);
        }

        /**
         * use hostname/ip
         */
        public TCPConnectionBuilder withHost(final String host) {
            this.host = host;
            return this;
        }

        /**
         * use port, default is 102
         */
        public TCPConnectionBuilder withPort(final int port) {
            this.port = port;
            return this;
        }

        /**
         * use rack, default is 0
         *
         * @throws IllegalArgumentException if rack is negative
         */
        public TCPConnectionBuilder withRack(final int rack) {
            if (rack < 0) {
                throw new IllegalArgumentException("rack must not be negative: " + rack);
            }
            this.rack = rack;
            return this;
        }

        /**
         * use slot, default is 2
         *
         * @throws IllegalArgumentException if slot is negative
         */
        public TCPConnectionBuilder withSlot(final int slot) {
            if (slot < 0) {
                throw new IllegalArgumentException("slot must not be negative: " + slot);
            }
            this.slot = slot;
            return this;
        }

        /**
         * use timeout, default is 2000
         */
        public TCPConnectionBuilder withTimeout(final int timeout) {
            this.timeout = timeout;
            return this;
        }

    }

    /**
     * @param type choose a siemens plc type to build a tcp connector.
     * @return returns a new TCP connection builder
     * @throws IllegalArgumentException if type is null
     */
    public static TCPConnectionBuilder buildTCPConnector(SiemensPLCS type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        return new TCPConnectionBuilder(type);
    }

    public static TCPConnectionBuilder buildTCPConnector() {
        return new TCPConnectionBuilder(SiemensPLCS.SNon200);
    }

}
