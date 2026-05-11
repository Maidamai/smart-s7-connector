package io.github.maidamai.s7connector.impl.transport;

import java.io.IOException;

public final class S7TransportException extends IOException {
    private static final long serialVersionUID = 1L;

    public S7TransportException(
            final String stage,
            final S7TransportConfig config,
            final int requestLength,
            final Throwable cause) {
        super(buildMessage(stage, config, requestLength), cause);
    }

    public S7TransportException(
            final String stage,
            final S7TransportConfig config,
            final int requestLength,
            final String detail) {
        super(buildMessage(stage, config, requestLength) + ", detail=" + detail);
    }

    private static String buildMessage(
            final String stage,
            final S7TransportConfig config,
            final int requestLength) {
        return "S7 transport failed at " + stage
                + ", " + config.describe()
                + ", requestLength=" + requestLength;
    }
}
