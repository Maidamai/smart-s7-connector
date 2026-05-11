package io.github.maidamai.s7connector.impl.transport;

import java.io.Closeable;
import java.io.IOException;

public interface S7Transport extends Closeable {
    void connect() throws IOException;

    byte[] writeAndRead(byte[] request, int requestLength) throws IOException;

    @Override
    void close() throws IOException;
}
