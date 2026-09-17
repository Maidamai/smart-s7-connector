# rc.1 实际发布附件消费者 smoke 复验（2026-09-17）

日期：2026-09-17 · 执行环境：维护者开发机（Windows 10，Git Bash，JDK 17.0.19，Maven 3.9.11，GnuPG 2.4.5）
工具：`consumer-smoke/verify-release.sh`（本轮新增的独立消费者工程，全部走公共 API + 内存 `S7Connector`，不接触任何 PLC/网络设备）

## 结论（先说结果）

**对 v1.0.0-rc.1 的实际发布附件完成了下载、SHA256 校验、隔离仓库安装与消费者成功路径测试。** 结果分三类：

1. **通过**：附件下载、SHA256SUMS 校验、POM 获取（tag 回退）、隔离仓库安装、4 项消费者测试中的 3 项（包装数组 Bean round-trip、多点批量读顺序、单点读改写合并语义）。
2. **消费者级复现了审计 C1**：`primitiveArraysRoundTrip` 在实际下载的 rc.1 JAR 上失败，异常栈正是审计第三轮 C1 指出的包装数组赋给 primitive 数组字段（见下文）。这不是测试脚手架的误报，而是发布的候选制品存在该缺陷的直接证据；修复随本轮 C1 修复进入下一候选版本。
3. **无法完成（证据缺口，不是通过）**：`.asc` 签名验证——维护者公钥（指纹 `AF570E8790461C3809463B247E4B165E97DBB3DB`）在 keys.openpgp.org 与 keyserver.ubuntu.com 均未取到，`gpg --recv-keys` 两处均失败（"No data"）。签名文件存在不等于签名可验证；此缺口需维护者发布公钥后闭合。

脚本总退出码：**1**（3 个步骤 FAIL：公钥获取、验签、消费者测试）。步骤明细见命令记录。

## 制品与摘要

- Release：https://github.com/Maidamai/smart-s7-connector/releases/tag/v1.0.0-rc.1（Pre-release，tag 对象指向 01a3537）
- 主 JAR：`smart-s7-connector-1.0.0-rc.1.jar`，107870 字节
  - 实测 SHA256：`cbfd10955c96125dedb0be885fb6a3c20ca9cfa851e563276987e0c433fc5e03`
  - 与发布附件 `SHA256SUMS` 中对应条目**一致**（`sha256sum -c` 输出 `OK`）
- `.asc`：833 字节（存在，**未验证**，原因见上）
- Release 未附 POM 资产：脚本按预案回退取 tag 原始 `pom.xml`（raw.githubusercontent.com/…/v1.0.0-rc.1/pom.xml，声明版本 1.0.0-rc.1，坐标核对通过）。**建议下一候选把 POM 作为 Release 资产附上。**

## 命令与退出码记录

```text
cd consumer-smoke && ./verify-release.sh
# 逐步骤 [PASS]/[FAIL] 与退出码由脚本自打印；关键行：
[PASS] downloaded smart-s7-connector-1.0.0-rc.1.jar (107870 bytes)
[PASS] downloaded smart-s7-connector-1.0.0-rc.1.jar.asc (833 bytes)
[PASS] downloaded SHA256SUMS (644 bytes)
[PASS] SHA256 of smart-s7-connector-1.0.0-rc.1.jar matches SHA256SUMS
[FAIL] signer public key …97DBB3DB not retrievable from public keyservers — .asc CANNOT be verified
[FAIL] signature verification skipped: key unavailable (this is a failure, not a pass)
[PASS] POM taken from raw Maidamai/smart-s7-connector/v1.0.0-rc.1/pom.xml (note: attach the POM as a Release asset)
[PASS] install JAR+POM into isolated repository …/consumer-smoke/isolated-repo (exit 0)
[FAIL] consumer smoke tests (exit 1) — see downloads/smoke-run.log
passed: 6   failed: 3
RESULT: 3 STEP(S) FAILED for v1.0.0-rc.1
脚本退出码：1
```

安装与测试使用空隔离仓库 `consumer-smoke/isolated-repo/`（`-Dmaven.repo.local`，未触碰 `~/.m2`），库坐标只从该仓库解析，传递依赖（slf4j-api、netty-all）由 tag POM 提供。

## 消费者测试明细（对实际下载 JAR，Tests run: 4, Failures: 0, Errors: 1）

| 测试 | 结果 | 说明 |
|---|---|---|
| wrapperBeanRoundTripsThroughConnector | 通过 | BOOL 包装数组 + INT 标量 + BYTE 包装数组 + STRING，store→dispense 全等 |
| **primitiveArraysRoundTrip** | **失败（审计 C1）** | boolean[]/short[]/int[]/long[]/byte[] 混合 Bean，store 成功，dispense 抛 S7Exception |
| multiPointBatchReadFollowsInputOrder | 通过 | BOOL×2/INT/WORD/REAL 五点位批量读，值与顺序全对 |
| singlePointStoreMergesOnlyItsByte | 通过 | 单点写为读改写：仅目标位变化（0x55|0x08=0x5D），邻字节不动 |

primitiveArraysRoundTrip 的失败根因（来自 surefire 报告，发生在实际下载制品内）：

```text
io.github.maidamai.s7connector.exception.S7Exception: extractBytes beanClass(…PrimitiveArraysBean) …
Caused by: java.lang.IllegalArgumentException: Can not set [Z field …PrimitiveArraysBean.bits
    to [Ljava.lang.Boolean;
    at …S7SerializerImpl.extractBytes(S7SerializerImpl.java:112)
```

与第三轮审计 C1 的机理描述逐字吻合（`Array.newInstance(entry.type=Boolean.class, …)` 产生 `Boolean[]` 赋给 `boolean[]` 字段）。修复已在本轮 master 源码完成（见 C1 回归测试与 CHANGELOG），自 rc.2 起 `S7_VERSION=1.0.0-rc.2 ./verify-release.sh` 应全绿。

## 本次未验证项（如实记录）

- sources/javadoc 附件与其 `.asc`：未下载、未校验（脚本只处理消费者直接消费的主 JAR）。
- Maven Central：rc.1 未发布到 Central，本次也未验证 Central 路径。
- 实机 PLC 行为：内存 `S7Connector` 不触及传输层与设备；Tier 2/3 仍按 [live-plc-attempt-2026-09-17.md](live-plc-attempt-2026-09-17.md) 等待硬件。
- `.asc` 签名有效性：公钥不可得，未验证（见结论第 3 条）。维护者后续动作建议：将公钥上传至 keyserver（如 keys.openpgp.org）、在 docs/releasing.md 公布指纹获取途径，或将公钥文件作为 Release 资产附带。

## 与既有报告的关系

- [consumer-rc1-2026-09-17.md](consumer-rc1-2026-09-17.md)：从源码构建候选的消费者验证（类型加载/许可资源），其附录说明了源码与 tag 的对应关系。**本报告是对其缺口的补充**：证据对象从"源码构建产物"换成"Release 页实际下载附件"，并加入成功路径 round-trip。
- 本报告不宣称 rc.1 通过消费者验证：C1 在实际制品上被本 harness 捕获即为反证。
