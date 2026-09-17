package io.github.maidamai.smoke;

import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;

import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory {@link S7Connector} for consumer smoke tests: no PLC, no
 * network. Memory is kept per (area, areaNumber) and grows zero-filled on
 * demand, so every read returns deterministic bytes that earlier writes put
 * there. Argument validation mirrors the connector contract documented on
 * {@link S7Connector}.
 */
public final class InMemoryS7Connector implements S7Connector {

    private final Map<String, byte[]> memory = new HashMap<String, byte[]>();

    private byte[] memoryFor(final DaveArea area, final int areaNumber, final int requiredLength) {
        final String key = area + ":" + areaNumber;
        byte[] mem = memory.get(key);
        if (mem == null || mem.length < requiredLength) {
            final byte[] grown = new byte[Math.max(requiredLength, 64)];
            if (mem != null) {
                System.arraycopy(mem, 0, grown, 0, mem.length);
            }
            memory.put(key, grown);
            mem = grown;
        }
        return mem;
    }

    @Override
    public byte[] read(final DaveArea area, final int areaNumber, final int bytes, final int offset) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
        if (areaNumber < 0 || bytes < 0 || offset < 0) {
            throw new IllegalArgumentException("negative argument: areaNumber=" + areaNumber
                    + " bytes=" + bytes + " offset=" + offset);
        }
        final byte[] mem = memoryFor(area, areaNumber, offset + bytes);
        final byte[] out = new byte[bytes];
        System.arraycopy(mem, offset, out, 0, bytes);
        return out;
    }

    @Override
    public void write(final DaveArea area, final int areaNumber, final int offset, final byte[] buffer) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (areaNumber < 0 || offset < 0) {
            throw new IllegalArgumentException("negative argument: areaNumber=" + areaNumber
                    + " offset=" + offset);
        }
        final byte[] mem = memoryFor(area, areaNumber, offset + buffer.length);
        System.arraycopy(buffer, 0, mem, offset, buffer.length);
    }

    @Override
    public void close() {
        // nothing to release
    }
}
