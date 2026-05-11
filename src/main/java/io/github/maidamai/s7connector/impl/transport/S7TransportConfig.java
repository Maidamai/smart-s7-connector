package io.github.maidamai.s7connector.impl.transport;

public final class S7TransportConfig {
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public S7TransportConfig(
            final String host,
            final int port,
            final int connectTimeoutMillis,
            final int readTimeoutMillis) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("port must be between " + MIN_PORT + " and " + MAX_PORT + ": " + port);
        }
        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("connectTimeoutMillis must be positive: " + connectTimeoutMillis);
        }
        if (readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("readTimeoutMillis must be positive: " + readTimeoutMillis);
        }
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public String getHost() {
        return this.host;
    }

    public int getPort() {
        return this.port;
    }

    public int getConnectTimeoutMillis() {
        return this.connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return this.readTimeoutMillis;
    }

    public String describe() {
        return "host=" + this.host
                + ", port=" + this.port
                + ", connectTimeoutMillis=" + this.connectTimeoutMillis
                + ", readTimeoutMillis=" + this.readTimeoutMillis;
    }
}
