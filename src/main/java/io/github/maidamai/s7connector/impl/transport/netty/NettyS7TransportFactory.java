package io.github.maidamai.s7connector.impl.transport.netty;

import io.github.maidamai.s7connector.impl.transport.S7TransportConfig;

public final class NettyS7TransportFactory {
    public NettyS7Transport create(final S7TransportConfig config) {
        return new NettyS7Transport(config);
    }
}
