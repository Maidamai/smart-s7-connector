package io.github.maidamai.s7connector.examples;

import io.github.maidamai.s7connector.impl.serializer.S7SerializerImpl;

/**
 * Compilable in-memory example: extracts a {@link MotorState} bean from a raw
 * buffer without any PLC connection. The buffer layout mirrors what a PLC
 * would return for the bean's mapping.
 *
 * <p>Run with {@code mvn test -Dtest=BeanReadExampleTest} to see the
 * assertions enforced by the regular test suite.</p>
 */
public final class BeanReadExample {

    public static void main(final String[] args) {
        final MotorState state = readMotorStateFromBuffer();
        System.out.println("running=" + state.getRunning() + ", speed=" + state.getSpeed());
    }

    static MotorState readMotorStateFromBuffer() {
        final byte[] buffer = new byte[4];
        buffer[0] = 0x01; // BOOL running at byte 0, bit 0 -> true
        buffer[1] = 0x00; // unused
        buffer[2] = 0x00; // INT speed at byte 2, big endian 42
        buffer[3] = 0x2A;

        return S7SerializerImpl.extractBytes(MotorState.class, buffer, 0);
    }
}
