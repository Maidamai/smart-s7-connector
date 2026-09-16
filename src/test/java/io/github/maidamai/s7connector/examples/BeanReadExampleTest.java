package io.github.maidamai.s7connector.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the in-memory {@link BeanReadExample} through the regular suite so its
 * assertions are enforced by {@code mvn test}.
 */
class BeanReadExampleTest {

    @Test
    void extractsExpectedValuesFromInMemoryBuffer() {
        final MotorState state = BeanReadExample.readMotorStateFromBuffer();

        assertEquals(Boolean.TRUE, state.getRunning(), "bit 0 of byte 0 must map to running");
        assertEquals(Short.valueOf((short) 42), state.getSpeed(), "bytes 2..3 must map to speed");
    }
}
