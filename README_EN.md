# smart-s7-connector

English | [简体中文](README.md)

smart-s7-connector is a lightweight Java library for Siemens S7 PLC communication. It provides TCP connectivity, S7 memory-area read/write operations, batched point reads, and annotation-based object serialization.

The project is based on [s7connector](https://github.com/s7connector/s7connector). It keeps the simple public API style of the original project and adds Netty transport, negotiated PDU read-window splitting, and pre-merged batch reads for S7-1200 / S7-1500 oriented workloads.

## Features

- Connect to Siemens S7 PLCs over TCP.
- Read and write S7 areas such as DB, I, Q, and M.
- Support BOOL, BYTE, INT, DINT, WORD, DWORD, REAL, STRING, DATE, TIME, DATE_AND_TIME, and STRUCT.
- Map DB bytes to Java objects with `@Datablock`, `@S7Variable`, and `@Array`.
- Batch-read point lists by merging continuous or near-continuous points into fewer PLC requests.
- Split large reads according to the negotiated PDU size to avoid exceeding the PLC read window.
- Keep Netty types out of the public API. Application code only depends on `S7Connector` and `S7Serializer`.

## Differences from s7connector

smart-s7-connector keeps the core API and annotation-based serialization model from s7connector, while adding engineering, transport, and batch-read improvements:

| Area | Change |
| --- | --- |
| Maven coordinates | Uses `io.github.maidamai:smart-s7-connector` without an internal parent POM |
| Package name | Uses `io.github.maidamai.s7connector` |
| Transport | Adds a Netty TCP transport implementation without exposing Netty in the public API |
| Large reads | Splits reads by the negotiated PDU length to stay within the available read window |
| Batch point reads | Merges continuous or near-continuous points before issuing PLC reads |
| S7-1200 / S7-1500 | Adds connection and read validation for S7-1200 / S7-1500 scenarios |
| Error context | Includes area, DB, offset, length, and related context in read/write and serialization errors |
| Tests | Adds loopback, batch-planning, Netty transport, and concurrency tests |

Batch-read performance comes from reducing PLC network round trips, not from changing how each point is decoded. For example, in local tests, 1000 continuous BYTE points are merged into fewer reads by the dynamic read window. With a 96-byte window, they are split into 11 reads instead of 1000 point-by-point reads.

In the project PLC environment, single-read latency can be kept within 40 ms, and batched reads complete at the hundred-millisecond level with lower latency than the original point-by-point read path. Actual throughput depends on the PLC model, network conditions, negotiated PDU length, and point distribution. Benchmark with your own tag list before using the numbers as operational targets.

## Requirements

- JDK 8 or later.
- Maven 3.6 or later.
- S7 TCP communication enabled on the PLC, with the correct rack, slot, port, and DB access settings.

Default port: `102`.

Common connection parameters:

| PLC family | rack | slot |
| --- | ---: | ---: |
| S7-200 Smart | 0 | 1 |
| S7-300 / S7-400 | 0 | 2 |
| S7-1200 / S7-1500 | 0 | 1 or 2, depending on the PLC configuration |

## Installation

Install from source into your local Maven repository:

```bash
git clone https://github.com/Maidamai/smart-s7-connector.git
cd smart-s7-connector
mvn test
mvn install
```

Then add the dependency to your application:

```xml
<dependency>
    <groupId>io.github.maidamai</groupId>
    <artifactId>smart-s7-connector</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

### Raw Byte Read/Write

```java
import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;

import java.io.IOException;

public final class RawReadWriteExample {
    public static void main(final String[] args) throws IOException {
        try (S7Connector connector = S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                .withHost("192.168.0.10")
                .withPort(102)
                .withRack(0)
                .withSlot(2)
                .withTimeout(3000)
                .build()) {

            final byte[] dbBytes = connector.read(DaveArea.DB, 1, 10, 0);
            dbBytes[0] = 0x01;
            connector.write(DaveArea.DB, 1, 0, dbBytes);
        }
    }
}
```

`read(area, areaNumber, bytes, offset)`:

| Parameter | Description |
| --- | --- |
| `area` | S7 area, such as `DaveArea.DB`, `DaveArea.INPUTS`, `DaveArea.OUTPUTS`, or `DaveArea.FLAGS` |
| `areaNumber` | DB number. For non-DB areas, use `0` or your application convention |
| `bytes` | Number of bytes to read |
| `offset` | Start byte offset |

`write(area, areaNumber, offset, buffer)` writes the full `buffer` from the specified offset.

### Object Serialization

```java
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.annotation.Datablock;
import io.github.maidamai.s7connector.api.annotation.S7Variable;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.api.factory.S7SerializerFactory;

import java.io.IOException;

@Datablock
public final class MotorState {
    @S7Variable(type = S7Type.BOOL, byteOffset = 0, bitOffset = 0)
    private Boolean running;

    @S7Variable(type = S7Type.INT, byteOffset = 2)
    private Short speed;

    public Boolean getRunning() {
        return this.running;
    }

    public Short getSpeed() {
        return this.speed;
    }
}

public final class BeanReadExample {
    public static void main(final String[] args) throws IOException {
        try (S7Connector connector = S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                .withHost("192.168.0.10")
                .withPort(102)
                .withRack(0)
                .withSlot(2)
                .withTimeout(3000)
                .build()) {

            final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);
            final MotorState state = serializer.dispense(MotorState.class, 1, 0);
            System.out.println("running=" + state.getRunning() + ", speed=" + state.getSpeed());
        }
    }
}
```

### Batch Point Reads

```java
import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.api.factory.S7SerializerFactory;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class BatchPointReadExample {
    public static void main(final String[] args) throws IOException {
        try (S7Connector connector = S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                .withHost("192.168.0.10")
                .withPort(102)
                .withRack(0)
                .withSlot(2)
                .withTimeout(3000)
                .build()) {

            final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);
            final List<PlcS7PointVariable> points = Arrays.asList(
                    new PlcS7PointVariable(1, 0, 0, 1, DaveArea.DB, S7Type.BOOL, Boolean.class),
                    new PlcS7PointVariable(1, 2, 0, 2, DaveArea.DB, S7Type.INT, Short.class),
                    new PlcS7PointVariable(1, 4, 0, 4, DaveArea.DB, S7Type.DINT, Long.class));

            final List<?> values = (List<?>) serializer.dispense(points);
            System.out.println(values);
        }
    }
}
```

### Single-Channel Write and Multi-Channel Read

This pattern is useful when a system writes a small number of control channels and reads many status channels back. A single-channel write first reads and merges the byte range that contains the channel, which avoids overwriting other bits in the same byte. Multi-channel reads are planned by area, DB, and offset before issuing fewer PLC read requests.

```java
import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.S7Serializer;
import io.github.maidamai.s7connector.api.S7Type;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;
import io.github.maidamai.s7connector.api.factory.S7SerializerFactory;
import io.github.maidamai.s7connector.bean.PlcS7PointVariable;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class WriteThenBatchReadExample {
    public static void main(final String[] args) throws IOException {
        try (S7Connector connector = S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                .withHost("192.168.0.10")
                .withPort(102)
                .withRack(0)
                .withSlot(2)
                .withTimeout(3000)
                .build()) {

            final S7Serializer serializer = S7SerializerFactory.buildSerializer(connector);
            final PlcS7PointVariable startCommand =
                    new PlcS7PointVariable(1, 0, 0, 1, DaveArea.DB, S7Type.BOOL, Boolean.class);
            serializer.store(Boolean.TRUE, startCommand);

            final List<PlcS7PointVariable> statusPoints = Arrays.asList(
                    new PlcS7PointVariable(1, 0, 1, 1, DaveArea.DB, S7Type.BOOL, Boolean.class),
                    new PlcS7PointVariable(1, 2, 0, 2, DaveArea.DB, S7Type.INT, Short.class),
                    new PlcS7PointVariable(1, 4, 0, 4, DaveArea.DB, S7Type.REAL, Float.class));

            final List<?> values = (List<?>) serializer.dispense(statusPoints);
            System.out.println(values);
        }
    }
}
```

## Testing

```bash
mvn test
```

The default tests use local loopback servers or in-memory connectors to verify protocol encoding/decoding, PDU-window splitting, batch-read planning, and serialization behavior.

To run integration checks against a real PLC, pass the connection parameters as system properties:

```bash
mvn test -Dplc.host=192.168.0.10 -Dplc.port=102 -Dplc.rack=0 -Dplc.slot=2
```

## Project Layout

```text
.
├── pom.xml
├── README.md
├── README_EN.md
├── LICENSE
├── NOTICE
├── LICENSE_LIBNODAVE.txt
└── src
    ├── main/java/io/github/maidamai/s7connector
    │   ├── api
    │   ├── bean
    │   ├── exception
    │   └── impl
    └── test/java/io/github/maidamai/s7connector
```

## License and Attribution

smart-s7-connector is licensed under the Apache License 2.0.

This project is based on [s7connector](https://github.com/s7connector/s7connector). s7connector is licensed under the Apache License 2.0 and states that it is based on libnodave. `NOTICE` and `LICENSE_LIBNODAVE.txt` are kept in this repository for upstream attribution.

## Disclaimer

PLC communication can directly affect field equipment state. Validate read/write behavior in a simulator, test PLC, or offline DB before connecting to production equipment. Applications should implement their own permission checks, range validation, and operation auditing for write operations.
