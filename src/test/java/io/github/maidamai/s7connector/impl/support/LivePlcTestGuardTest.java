package io.github.maidamai.s7connector.impl.support;

import io.github.maidamai.s7connector.api.DaveArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the live-PLC test gate without any PLC: writes must be impossible
 * unless explicitly authorized and whitelisted, and misconfiguration must fail
 * loudly instead of silently narrowing the guard.
 */
class LivePlcTestGuardTest {

    @Test
    void liveTargetSkipsWhenNoHostConfigured() {
        final LivePlcTestGuard guard = guard(withProperties());

        assertAborted(() -> guard.requireLiveTarget(), "plc.host");
    }

    @Test
    void liveTargetUsesDefaultsForOptionalParameters() {
        final LivePlcTestGuard guard = guard(withProperties(LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10"));

        final LivePlcTestGuard.LiveTarget target = guard.requireLiveTarget();
        assertEquals("192.168.0.10", target.getHost());
        assertEquals(LivePlcTestGuard.DEFAULT_PORT, target.getPort());
        assertEquals(LivePlcTestGuard.DEFAULT_RACK, target.getRack());
        assertEquals(LivePlcTestGuard.DEFAULT_SLOT, target.getSlot());
        assertEquals(LivePlcTestGuard.DEFAULT_TIMEOUT_MILLIS, target.getTimeoutMillis());
    }

    @Test
    void liveTargetHonorsConfiguredParameters() {
        final LivePlcTestGuard guard = guard(withProperties(
                LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10",
                LivePlcTestGuard.PORT_PROPERTY, "1102",
                LivePlcTestGuard.RACK_PROPERTY, "1",
                LivePlcTestGuard.SLOT_PROPERTY, "3",
                LivePlcTestGuard.TIMEOUT_PROPERTY, "5000"));

        final LivePlcTestGuard.LiveTarget target = guard.requireLiveTarget();
        assertEquals(1102, target.getPort());
        assertEquals(1, target.getRack());
        assertEquals(3, target.getSlot());
        assertEquals(5000, target.getTimeoutMillis());
    }

    @Test
    void liveTargetRejectsInvalidPort() {
        final LivePlcTestGuard guard = guard(withProperties(
                LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10",
                LivePlcTestGuard.PORT_PROPERTY, "not-a-port"));

        assertThrows(IllegalStateException.class, () -> guard.requireLiveTarget());
    }

    @Test
    void writeRangeSkipsWhenWritesNotExplicitlyAllowed() {
        final LivePlcTestGuard guard = guard(withProperties(LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10"));

        assertAborted(() -> guard.requireWriteRange(DaveArea.DB, 1, 0, 8),
                "plc.allowWrites");
    }

    @Test
    void writeRangeFailsWithoutWhitelistEvenWhenWritesAllowed() {
        final LivePlcTestGuard guard = guard(withProperties(
                LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10",
                LivePlcTestGuard.ALLOW_WRITES_PROPERTY, "true"));

        assertThrows(IllegalStateException.class, () -> guard.requireWriteRange(DaveArea.DB, 1, 0, 8));
    }

    @Test
    void writeRangeFailsOnMalformedWhitelistEntries() {
        assertMalformed("DB1");
        assertMalformed("DB1:");
        assertMalformed("DB1:0-");
        assertMalformed("DB1:-8");
        assertMalformed("DB1:8-0");
        assertMalformed("DB1:a-b");
        assertMalformed("DB:0-8");
        assertMalformed("DB0:0-8");
        assertMalformed("X:0-8");
        assertMalformed(",");
    }

    @Test
    void writeRangeAcceptsCoveredDbRange() {
        final LivePlcTestGuard guard = guard(withProperties(
                LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10",
                LivePlcTestGuard.ALLOW_WRITES_PROPERTY, "true",
                LivePlcTestGuard.ALLOW_RANGES_PROPERTY, "DB1:0-63,M:0-1023"));

        guard.requireWriteRange(DaveArea.DB, 1, 0, 8);
        guard.requireWriteRange(DaveArea.DB, 1, 56, 8);
        guard.requireWriteRange(DaveArea.FLAGS, 0, 0, 1024);
    }

    @Test
    void writeRangeRejectsUncoveredRanges() {
        final LivePlcTestGuard guard = guard(withProperties(
                LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10",
                LivePlcTestGuard.ALLOW_WRITES_PROPERTY, "true",
                LivePlcTestGuard.ALLOW_RANGES_PROPERTY, "DB1:0-63"));

        assertThrows(IllegalStateException.class, () -> guard.requireWriteRange(DaveArea.DB, 2, 0, 8),
                "a different DB must not be covered by the DB1 whitelist entry");
        assertThrows(IllegalStateException.class, () -> guard.requireWriteRange(DaveArea.DB, 1, 60, 8),
                "a range crossing the whitelist end must be rejected");
        assertThrows(IllegalStateException.class, () -> guard.requireWriteRange(DaveArea.INPUTS, 0, 0, 8),
                "an area absent from the whitelist must be rejected");
        assertThrows(IllegalStateException.class, () -> guard.requireWriteRange(DaveArea.TIMER, 0, 0, 2),
                "areas without whitelist syntax must be rejected");
    }

    @Test
    void writeRangeRejectsInvalidRequestedRanges() {
        final LivePlcTestGuard guard = guard(withProperties(
                LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10",
                LivePlcTestGuard.ALLOW_WRITES_PROPERTY, "true",
                LivePlcTestGuard.ALLOW_RANGES_PROPERTY, "DB1:0-63"));

        assertThrows(IllegalArgumentException.class, () -> guard.requireWriteRange(null, 1, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> guard.requireWriteRange(DaveArea.DB, -1, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> guard.requireWriteRange(DaveArea.DB, 1, -1, 8));
        assertThrows(IllegalArgumentException.class, () -> guard.requireWriteRange(DaveArea.DB, 1, 0, 0));
    }

    @Test
    void writeAuthorizationValueMustBeExactTrue() {
        final LivePlcTestGuard guard = guard(withProperties(
                LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10",
                LivePlcTestGuard.ALLOW_WRITES_PROPERTY, "TRUE",
                LivePlcTestGuard.ALLOW_RANGES_PROPERTY, "DB1:0-63"));

        assertAborted(() -> guard.requireWriteRange(DaveArea.DB, 1, 0, 8),
                "plc.allowWrites");
    }

    private static void assertAborted(final Executable executable, final String expectedReasonFragment) {
        try {
            executable.execute();
            fail("expected the guard to abort the test");
        } catch (final org.opentest4j.TestAbortedException aborted) {
            if (aborted.getMessage() == null || !aborted.getMessage().contains(expectedReasonFragment)) {
                fail("abort reason should mention '" + expectedReasonFragment + "' but was: " + aborted.getMessage());
            }
        } catch (final Throwable other) {
            fail("expected a test abort but got: " + other);
        }
    }

    private static void assertMalformed(final String ranges) {
        final LivePlcTestGuard guard = guard(withProperties(
                LivePlcTestGuard.HOST_PROPERTY, "192.168.0.10",
                LivePlcTestGuard.ALLOW_WRITES_PROPERTY, "true",
                LivePlcTestGuard.ALLOW_RANGES_PROPERTY, ranges));
        assertThrows(IllegalStateException.class, () -> guard.requireWriteRange(DaveArea.DB, 1, 0, 8),
                "whitelist '" + ranges + "' must be rejected");
    }

    private static LivePlcTestGuard guard(final Map<String, String> properties) {
        return new LivePlcTestGuard(properties::get);
    }

    private static Map<String, String> withProperties(final String... pairs) {
        final Map<String, String> properties = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            properties.put(pairs[i], pairs[i + 1]);
        }
        return properties;
    }
}
