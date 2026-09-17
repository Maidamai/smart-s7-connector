# smart-s7-connector

[English](README_EN.md) | 简体中文

smart-s7-connector 是一个轻量级 Java Siemens S7 PLC 通信库，提供 TCP 连接、S7 数据区读写、批量点位读取和注解驱动的对象序列化能力。

项目基于 [s7connector](https://github.com/s7connector/s7connector) 演进，保留其简洁 API 风格，并针对 S7-1200 / S7-1500 场景补充了 Netty 传输、PDU 读窗口拆分和批量点位预合并等能力。

## 特性

- TCP 方式连接 Siemens S7 PLC。
- 支持 DB、I、Q、M 等 S7 数据区读写。
- 支持 BOOL、BYTE、INT、DINT、WORD、DWORD、REAL、STRING、DATE、TIME、DATE_AND_TIME、STRUCT 等类型。
- 支持通过 `@Datablock`、`@S7Variable`、`@Array` 将 DB 数据映射为 Java 对象。
- 支持点位列表批量读取，连续或近连续点位会先合并为较少的 PLC 读请求。
- 根据 PDU 协商结果拆分大块读取，避免单次读取超过 PLC 支持窗口。
- 公共 API 不暴露 Netty 类型，调用方只依赖 `S7Connector` / `S7Serializer`。

## 与 s7connector 的主要差异

smart-s7-connector 保留了上游 s7connector 的核心 API 和注解序列化模型，并在工程化、传输层和批量读取场景做了增强：

| 方向 | smart-s7-connector 的变化 |
| --- | --- |
| Maven 坐标 | 使用独立坐标 `io.github.maidamai:smart-s7-connector`，不依赖内部父 POM |
| 包名 | 使用 `io.github.maidamai.s7connector` |
| 传输层 | 增加 Netty TCP 传输实现，公共 API 不暴露 Netty 类型 |
| 大块读取 | 根据 PLC 协商得到的 PDU 长度拆分请求，避免单次读取超过可用窗口 |
| 批量点位读取 | 对连续或近连续点位先做请求合并，减少逐点网络读取 |
| S7-1200 / S7-1500 | 补充了面向 S7-1200 / S7-1500 的连接和读取验证 |
| 错误上下文 | 读写和序列化异常包含 area、DB、offset、length 等排查信息 |
| 测试覆盖 | 增加本地 loopback、批量读取规划、Netty 传输和并发相关测试 |

批量读取的收益来自“减少 PLC 网络请求次数”，不是改变单个点位的解析规则。例如在本地测试中，1000 个连续 BYTE 点位会按动态读取窗口合并为少量读取请求；当窗口为 96 字节时会拆成 11 次读取，而不是 1000 次逐点读取。

仓库历史材料中出现过“单次读取 40ms 内、批量读取百毫秒级”等性能数字，这些属于**维护者历史自述，未在仓库中复现**（无环境、点表与原始数据），不应作为本库性能依据。当前自动化测试只在本地 loopback 上断言“吞吐大于 0、分位数有序、批量读取的请求合并次数”，不是生产基准，也没有退化阈值。真实吞吐受 PLC 型号、网络环境、PDU 长度和点位分布影响；未来如补充正式性能报告，要素要求见 [docs/performance.md](docs/performance.md)。

## 环境要求

- JDK 8 或更高版本。
- Maven 3.6 或更高版本。
- PLC 侧需开启 S7 TCP 通信，并确认机架号、槽号、端口和 DB 访问权限。

默认端口为 `102`。常见参数如下：

| PLC 系列 | rack | slot |
| --- | ---: | ---: |
| S7-200 Smart | 0 | 1 |
| S7-300 / S7-400 | 0 | 2 |
| S7-1200 / S7-1500 | 0 | 1 或 2，按项目配置确认 |

## 安装

可以先从源码安装到本地 Maven 仓库：

```bash
git clone https://github.com/Maidamai/smart-s7-connector.git
cd smart-s7-connector
mvn test
mvn install
```

然后在业务项目中引用：

```xml
<dependency>
    <groupId>io.github.maidamai</groupId>
    <artifactId>smart-s7-connector</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

（当前为源码本地安装版本，尚未发布到 Maven Central；正式发布前版本号为 1.0.0-SNAPSHOT）

## 快速开始

完整契约（线程模型、错误类别、读写窗口、响应校验规则）见 [docs/api-contract.md](docs/api-contract.md)。

### 原始字节读取（只读示例）

```java
import io.github.maidamai.s7connector.api.DaveArea;
import io.github.maidamai.s7connector.api.S7Connector;
import io.github.maidamai.s7connector.api.SiemensPLCS;
import io.github.maidamai.s7connector.api.factory.S7ConnectorFactory;

import java.io.IOException;

public final class RawReadExample {
    public static void main(final String[] args) throws IOException {
        try (S7Connector connector = S7ConnectorFactory.buildTCPConnector(SiemensPLCS.S1500)
                .withHost("192.168.0.10")
                .withPort(102)
                .withRack(0)
                .withSlot(2)
                .withTimeout(3000)
                .build()) {

            final byte[] dbBytes = connector.read(DaveArea.DB, 1, 10, 0);
            System.out.println("DB1 bytes 0..9: " + java.util.Arrays.toString(dbBytes));
        }
    }
}
```

`read(area, areaNumber, bytes, offset)` 参数含义：

| 参数 | 说明 |
| --- | --- |
| `area` | 数据区，例如 `DaveArea.DB`、`DaveArea.INPUTS`、`DaveArea.OUTPUTS`、`DaveArea.FLAGS` |
| `areaNumber` | DB 编号；非 DB 区通常传 `0` 或业务约定值 |
| `bytes` | 读取字节数 |
| `offset` | 起始字节偏移 |

`write(area, areaNumber, offset, buffer)` 会从指定偏移整块写入完整 `buffer`（整块覆盖语义，见下文“写入的风险与限制”）。

### 对象序列化

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

注意：映射字段必须为 public；private 字段不参与映射。

### 批量点位读取

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

            final List<?> values = serializer.dispensePoints(points);
            System.out.println(values);
        }
    }
}
```

## 写入的风险与限制

写入会直接改变 PLC 内存，请先阅读 [docs/api-contract.md](docs/api-contract.md) 中的写语义：

- `connector.write(...)` 与 `serializer.store(bean, db, offset)` 是**整块覆盖**：未映射字段、空洞、同字节内其他 bit 都会被写为零。若只想改单个点位，请使用下文的“先读后写/单通道写入”方式。
- 写失败**无回滚**：PLC 拒绝某一片写入时抛出 `S7Exception`，消息中的 `confirmedWrittenBytes` 表示 PLC 已确认写入的字节数，此前各片保持已写状态。
- 任何传输层失败（`IOException`）或协议违规都会使连接进入终态，需要新建连接重试。

```java
// 整块覆盖示例：先读出 DB 块，修改后再整块写回，避免盲写未知字节
final byte[] dbBytes = connector.read(DaveArea.DB, 1, 10, 0); // 先读
dbBytes[0] = 0x01;                                            // 只改需要的字节
connector.write(DaveArea.DB, 1, 0, dbBytes);                  // 整块写回，覆盖 0..9 共 10 字节
```

### 单通道写入与多通道读取

适合少量控制通道写入、大量状态通道回读这类场景。单通道写入会先读取该通道所在字节范围并合并写回，避免覆盖同一字节内的其他 bit；多通道读取会按区域、DB 和偏移自动规划为较少的 PLC 读取请求。

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

            final List<?> values = serializer.dispensePoints(statusPoints);
            System.out.println(values);
        }
    }
}
```

## 测试与验证

```bash
mvn test
```

默认测试使用本地 loopback 或内存对象验证协议编解码、PDU 窗口拆分、批量读取规划和序列化行为，不需要 PLC，也不会发起任何写入请求。

如需连接真实/仿真 PLC，实机集成测试（`*IT`）通过 opt-in profile 显式启用：

```bash
# 只读验证：仅需 plc.host
mvn -Pplc-live-it verify -Dplc.host=192.168.0.10 -Dplc.port=102 -Dplc.rack=0 -Dplc.slot=2

# 写入验证：还必须显式授权并声明允许写入的字节范围白名单
mvn -Pplc-live-it verify -Dplc.host=192.168.0.10 -Dplc.allowWrites=true -Dplc.allow.ranges=DB1:0-63
```

安全规则：`plc.host` 本身不构成写授权；写测试在建立连接之前校验 `plc.allowWrites=true` 与范围白名单（如 `DB1:0-63,M:0-1023`），范围不覆盖即拒绝执行。详见 [docs/testing.md](docs/testing.md)。

## 项目结构

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

## 许可证与来源

本仓库采用分文件混合许可（2026-09-17 结案，决策记录见 [docs/provenance.md](docs/provenance.md) 第 5 节）：

- 除下列文件外的全部源码为 **Apache License 2.0**（本项目原创 + 源自上游 [s7connector](https://github.com/s7connector/s7connector) 的 Apache-2.0 部分）；
- `src/main/java/io/github/maidamai/s7connector/impl/nodave/` 下 7 个文件为 **LGPL-2.0-or-later**（源自 libnodave，Thomas Hergenhahn 2005，经 s7connector Java 移植继承，头部原样保留）。

这不是"任选其一"的双许可：使用者不能将整个制品按纯 Apache-2.0 使用。当某次分发需要单一整体许可时，
可依 LGPL "or any later version" 条款升级为 LGPL-3.0-or-later（Apache-2.0 代码可单向并入 LGPL-3.0 作品）。
发布制品（主 JAR 与 sources JAR）的 `META-INF/` 携带两份许可全文、`NOTICE` 与 `THIRD_PARTY_NOTICES.md`。
逐文件溯源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 [docs/provenance.md](docs/provenance.md)。

本项目基于 [s7connector](https://github.com/s7connector/s7connector) 演进；仓库中保留 `NOTICE` 和 `LICENSE_LIBNODAVE.txt` 用于上游来源说明。

## 贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。涉及行为的改动请同步更新 [docs/api-contract.md](docs/api-contract.md) 与 [docs/migration.md](docs/migration.md)。

## 免责声明

PLC 通信会直接影响现场设备状态。请先在仿真环境、测试 PLC 或离线 DB 中验证读写逻辑，再连接生产设备。写入操作应由业务系统自行做好权限控制、范围校验和操作审计。
