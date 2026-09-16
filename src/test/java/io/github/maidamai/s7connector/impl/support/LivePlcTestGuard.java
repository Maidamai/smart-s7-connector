package io.github.maidamai.s7connector.impl.support;

import io.github.maidamai.s7connector.api.DaveArea;
import org.junit.jupiter.api.Assumptions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Safety gate for integration tests that talk to an external PLC.
 *
 * <p>Read-only tests require {@code plc.host}. Tests that write PLC memory
 * additionally require {@code plc.allowWrites=true} plus an explicit
 * byte-range whitelist in {@code plc.allow.ranges} that covers every write.
 * The host property alone never authorizes a write.</p>
 *
 * <p>Whitelist syntax: comma-separated entries {@code AREA:first-last}
 * (inclusive byte offsets), where AREA is {@code DB<number>} or one of the
 * process-image letters {@code I}, {@code Q}, {@code M}. Example:
 * {@code DB1:0-63,M:0-1023}.</p>
 */
public final class LivePlcTestGuard {
    public static final String HOST_PROPERTY = "plc.host";
    public static final String PORT_PROPERTY = "plc.port";
    public static final String RACK_PROPERTY = "plc.rack";
    public static final String SLOT_PROPERTY = "plc.slot";
    public static final String TIMEOUT_PROPERTY = "plc.timeoutMillis";
    public static final String ALLOW_WRITES_PROPERTY = "plc.allowWrites";
    public static final String ALLOW_RANGES_PROPERTY = "plc.allow.ranges";

    static final int DEFAULT_PORT = 102;
    static final int DEFAULT_RACK = 0;
    static final int DEFAULT_SLOT = 2;
    static final int DEFAULT_TIMEOUT_MILLIS = 3000;

    private static final String ALLOW_WRITES_VALUE = "true";
    private static final String RANGE_SEPARATOR = ",";
    private static final String AREA_OFFSET_SEPARATOR = ":";

    private final Function<String, String> properties;

    LivePlcTestGuard(final Function<String, String> properties) {
        this.properties = properties;
    }

    public static LivePlcTestGuard fromSystemProperties() {
        return new LivePlcTestGuard(System::getProperty);
    }

    /**
     * Resolves the external test target. Skips the test when no host is
     * configured, so plain {@code mvn test} never touches an external PLC.
     */
    public LiveTarget requireLiveTarget() {
        final String host = property(HOST_PROPERTY);
        Assumptions.assumeTrue(host != null && !host.trim().isEmpty(),
                "set -D" + HOST_PROPERTY + " to run live PLC integration tests");
        final int port = positiveProperty(PORT_PROPERTY, DEFAULT_PORT);
        final int rack = nonNegativeProperty(RACK_PROPERTY, DEFAULT_RACK);
        final int slot = nonNegativeProperty(SLOT_PROPERTY, DEFAULT_SLOT);
        final int timeoutMillis = positiveProperty(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_MILLIS);
        return new LiveTarget(host.trim(), port, rack, slot, timeoutMillis);
    }

    /**
     * Verifies, before any connection is opened, that this test may write the
     * given memory range. Skips when writes are not explicitly enabled; fails
     * when writes are enabled but the whitelist is missing, malformed, or does
     * not cover the range.
     */
    public void requireWriteRange(final DaveArea area, final int dbNumber, final int offset, final int length) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
        if (dbNumber < 0) {
            throw new IllegalArgumentException("dbNumber must not be negative: " + dbNumber);
        }
        if (offset < 0 || length <= 0) {
            throw new IllegalArgumentException("offset/length must describe a non-empty range: offset=" + offset
                    + ", length=" + length);
        }
        if (!ALLOW_WRITES_VALUE.equals(property(ALLOW_WRITES_PROPERTY))) {
            Assumptions.assumeTrue(false,
                    "live PLC writes need -D" + ALLOW_WRITES_PROPERTY + "=true and -D" + ALLOW_RANGES_PROPERTY
                            + "=<ranges> covering every written range, e.g. DB1:0-63");
            return;
        }
        final List<AllowedRange> allowedRanges = parseAllowedRanges();
        final String request = describeRange(area, dbNumber, offset, length);
        for (final AllowedRange allowedRange : allowedRanges) {
            if (allowedRange.covers(area, dbNumber, offset, length)) {
                return;
            }
        }
        throw new IllegalStateException("write range " + request + " is not covered by " + ALLOW_RANGES_PROPERTY
                + "=" + property(ALLOW_RANGES_PROPERTY)
                + "; extend the whitelist or narrow the test range before running against a live PLC");
    }

    private List<AllowedRange> parseAllowedRanges() {
        final String whitelist = property(ALLOW_RANGES_PROPERTY);
        if (whitelist == null || whitelist.trim().isEmpty()) {
            throw new IllegalStateException(ALLOW_WRITES_PROPERTY + "=true also requires -D" + ALLOW_RANGES_PROPERTY
                    + "=<ranges>, e.g. DB1:0-63,M:0-1023; refusing to write without an explicit whitelist");
        }
        final List<AllowedRange> allowedRanges = new ArrayList<>();
        for (final String entry : whitelist.split(RANGE_SEPARATOR)) {
            final String trimmedEntry = entry.trim();
            if (trimmedEntry.isEmpty()) {
                continue;
            }
            allowedRanges.add(parseAllowedRange(trimmedEntry));
        }
        if (allowedRanges.isEmpty()) {
            throw new IllegalStateException(ALLOW_RANGES_PROPERTY + " contains no ranges: " + whitelist);
        }
        return allowedRanges;
    }

    private AllowedRange parseAllowedRange(final String entry) {
        final int areaSeparator = entry.indexOf(AREA_OFFSET_SEPARATOR);
        if (areaSeparator <= 0 || areaSeparator == entry.length() - 1) {
            throw malformedWhitelistEntry(entry);
        }
        final String areaToken = entry.substring(0, areaSeparator).trim();
        final String offsets = entry.substring(areaSeparator + 1).trim();
        final int rangeSeparator = offsets.indexOf('-');
        if (rangeSeparator <= 0 || rangeSeparator == offsets.length() - 1) {
            throw malformedWhitelistEntry(entry);
        }
        final int start;
        final int end;
        try {
            start = Integer.parseInt(offsets.substring(0, rangeSeparator).trim());
            end = Integer.parseInt(offsets.substring(rangeSeparator + 1).trim());
        } catch (final NumberFormatException cause) {
            throw malformedWhitelistEntry(entry);
        }
        if (start < 0 || end < start) {
            throw malformedWhitelistEntry(entry);
        }
        final DaveArea area;
        final int dbNumber;
        final String upperAreaToken = areaToken.toUpperCase(java.util.Locale.ROOT);
        if (upperAreaToken.equals("DB")) {
            throw malformedWhitelistEntry(entry);
        }
        if (upperAreaToken.startsWith("DB")) {
            try {
                dbNumber = Integer.parseInt(upperAreaToken.substring(2));
            } catch (final NumberFormatException cause) {
                throw malformedWhitelistEntry(entry);
            }
            if (dbNumber <= 0) {
                throw malformedWhitelistEntry(entry);
            }
            area = DaveArea.DB;
        } else if (upperAreaToken.equals("I")) {
            area = DaveArea.INPUTS;
            dbNumber = 0;
        } else if (upperAreaToken.equals("Q")) {
            area = DaveArea.OUTPUTS;
            dbNumber = 0;
        } else if (upperAreaToken.equals("M")) {
            area = DaveArea.FLAGS;
            dbNumber = 0;
        } else {
            throw malformedWhitelistEntry(entry);
        }
        return new AllowedRange(area, dbNumber, start, end);
    }

    private static IllegalStateException malformedWhitelistEntry(final String entry) {
        return new IllegalStateException("malformed " + ALLOW_RANGES_PROPERTY + " entry '" + entry
                + "'; expected AREA:first-last with AREA being DB<number>, I, Q or M, e.g. DB1:0-63,M:0-1023");
    }

    private static String describeRange(final DaveArea area, final int dbNumber, final int offset, final int length) {
        return areaToken(area, dbNumber) + ":" + offset + "-" + (offset + length - 1);
    }

    private static String areaToken(final DaveArea area, final int dbNumber) {
        if (area == DaveArea.DB) {
            return "DB" + dbNumber;
        }
        if (area == DaveArea.INPUTS) {
            return "I";
        }
        if (area == DaveArea.OUTPUTS) {
            return "Q";
        }
        if (area == DaveArea.FLAGS) {
            return "M";
        }
        return area.name();
    }

    private String property(final String name) {
        return this.properties.apply(name);
    }

    private int positiveProperty(final String name, final int defaultValue) {
        return intProperty(name, defaultValue, true);
    }

    private int nonNegativeProperty(final String name, final int defaultValue) {
        return intProperty(name, defaultValue, false);
    }

    private int intProperty(final String name, final int defaultValue, final boolean strictlyPositive) {
        final String value = property(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (final NumberFormatException cause) {
            throw new IllegalStateException("property " + name + " is not an integer: " + value, cause);
        }
        if (strictlyPositive ? parsed <= 0 : parsed < 0) {
            throw new IllegalStateException("property " + name + " is out of range: " + value);
        }
        return parsed;
    }

    public static final class LiveTarget {
        private final String host;
        private final int port;
        private final int rack;
        private final int slot;
        private final int timeoutMillis;

        private LiveTarget(final String host, final int port, final int rack, final int slot, final int timeoutMillis) {
            this.host = host;
            this.port = port;
            this.rack = rack;
            this.slot = slot;
            this.timeoutMillis = timeoutMillis;
        }

        public String getHost() {
            return this.host;
        }

        public int getPort() {
            return this.port;
        }

        public int getRack() {
            return this.rack;
        }

        public int getSlot() {
            return this.slot;
        }

        public int getTimeoutMillis() {
            return this.timeoutMillis;
        }
    }

    private static final class AllowedRange {
        private final DaveArea area;
        private final int dbNumber;
        private final int start;
        private final int end;

        private AllowedRange(final DaveArea area, final int dbNumber, final int start, final int end) {
            this.area = area;
            this.dbNumber = dbNumber;
            this.start = start;
            this.end = end;
        }

        private boolean covers(final DaveArea area, final int dbNumber, final int offset, final int length) {
            return this.area == area
                    && this.dbNumber == dbNumber
                    && offset >= this.start
                    && offset + length - 1 <= this.end;
        }
    }
}
