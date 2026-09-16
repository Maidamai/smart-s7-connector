package io.github.maidamai.s7connector.examples;

import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.annotation.Datablock;
import io.github.maidamai.s7connector.api.annotation.S7Variable;

/**
 * Example bean, kept in sync with the object serialization section of
 * README.md / README_EN.md.
 *
 * Mapping contract: mapped fields must be public. Private fields do not
 * participate in the mapping.
 */
@Datablock
public final class MotorState {

    @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0)
    public Boolean running;

    @S7Variable(type = S7Type.INT, byteOffset = 2)
    public Short speed;

    public Boolean getRunning() {
        return this.running;
    }

    public Short getSpeed() {
        return this.speed;
    }
}
