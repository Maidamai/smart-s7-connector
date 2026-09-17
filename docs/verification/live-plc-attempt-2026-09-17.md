# 实机 PLC 只读/授权写验证尝试记录（2026-09-17）

> 注：2026-09-17 第三轮审计后，本文中的原始内网地址已替换为占位符；原始排错细节保留在维护者本地非公开记录中。

日期：2026-09-17 · 执行环境：维护者开发机（Windows 10，物理网卡 <HOST>/24）

## 结论（先说结果）

**本轮未获得任何实机验证证据。** 局域网内发现两个 TCP/102 开放候选，但在本组
连接参数（rack/slot/超时）与本环境下均未能完成 iso-on-tcp（COTP）连接握手。
Tier 2（只读）与 Tier 3（授权写）
**未执行成功**，[docs/compatibility.md](../compatibility.md) 全部实机行维持
"未验证 / Not verified" 不变——失败或无响应的尝试不构成验证证据。

## 1. 设备发现（方法与结果）

- 方法：对本机所在物理网段 <SUBNET>/24 做 TCP/102（ISO-on-TCP/S7 惯用端口）单端口
  定向探测（每主机 0.4s 超时）。本机其余 <VNIC-1>.x/<VNIC-2>.x 为虚拟网卡、<PROXY-FAKEIP>.x 为
  代理 fake-IP 段，均已排除，未探测。
- 结果：两个候选开放 TCP/102——`DEVICE-A` 与 `DEVICE-B`。

## 2. 连接尝试（全部失败，保留原始参数）

所有尝试均通过本仓库 Tier 2 测试类 `S1500SimulatedPlcIT.readsConfiguredDbPointsFromSimulatedS1500`
（连接 + 只读 DB1 0..7，不发送任何写请求）：

| # | 目标 | rack/slot | 超时 | 结果 |
|---|---|---|---|---|
| 1 | DEVICE-A | 0/2 | 3000ms | TCP 建立；COTP 连接请求（22 字节）发出后无应答，`readTimeout` |
| 2 | DEVICE-B | 0/2 | 5000ms | TCP 建立；同样 `readTimeout`，无 COTP 应答 |
| 3 | DEVICE-A | 0/1 | 10000ms | TCP 建立；约 5.3s 后传输层 `read` 失败（对端无有效 ISO 响应） |
| 4 | DEVICE-B | 0/1 | 10000ms | TCP 建立；约 5.3s 后传输层 `read` 失败 |

失败点一致：`S7TCPConnection.setupSocket` → `TCPConnection.connectPLC` →
`sendISOPacket` 的 ISO 连接请求阶段（尚未进入 S7 PDU 层，未触及 PLC 数据）。
判定：两个端点均开放 TCP/102，但在本组连接参数（rack/slot/超时）与本环境下
均未能完成 iso-on-tcp（COTP）握手。握手失败不能证明端点不是 S7 设备——
端点身份、访问策略、连接参数均未被排除；同样不能证明整个网段没有 S7 设备。

复现命令（对任一候选）：

```bash
./mvnw -B -ntp -Pplc-live-it verify -Dplc.host=DEVICE-A -Dplc.port=102 \
    -Dplc.rack=0 -Dplc.slot=2 -Dplc.timeoutMillis=3000
```

## 3. 硬件可用后如何补齐（精确步骤）

1. **Tier 2 只读**（仅需主机地址，绝不发送写请求）：

   ```bash
   ./mvnw -B -ntp -Pplc-live-it verify -Dplc.host=<PLC_IP> -Dplc.rack=0 -Dplc.slot=2
   ```

   通过后：将 [docs/compatibility.md](../compatibility.md) 对应行的 Connection/Read
   列改为已验证并链接本目录下的报告，附设备型号与日志。

2. **Tier 3 授权写**（必须由维护者显式指定**隔离测试设备**与**安全字节范围**，
   严禁对生产控制链执行）：

   ```bash
   ./mvnw -B -ntp -Pplc-live-it verify -Dplc.host=<PLC_IP> \
       -Dplc.allowWrites=true -Dplc.allow.ranges=DB1:0-7
   ```

   写测试会先读原值、写测试值、读回校验、最后还原原值；还原不撤销设备可能已
   执行的动作（见 docs/testing.md 安全须知）。

## 4. 诚实性声明

- 本记录中的尝试均为**失败**记录，不作为任何"实机已验证"的依据；
- loopback（Tier 1）证据与实机验证的边界见 [docs/compatibility.md](../compatibility.md)
  与 [docs/testing.md](../testing.md)，本报告不改变任何状态单元格。
