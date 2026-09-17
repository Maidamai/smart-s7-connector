# 1.0.0-rc.1 候选包干净消费者验证报告

日期：2026-09-17
执行环境：Windows 10 (amd64)，Git Bash

## 1. 候选来源

| 项 | 值 |
|---|---|
| 源 commit | `21abe17802654b788a15d37c733e9be6098eb2bc`（`21abe17`，"fix: address 2nd audit round (R1-R4)"，master） |
| 取源方式 | `git worktree add <TMP>/s7-rc-src HEAD`（独立 worktree，不触碰主仓库工作树） |
| 版本设置 | `./mvnw -B -ntp versions:set -DnewVersion=1.0.0-rc.1 -DgenerateBackupPoms=false`（versions 插件 2.22.0，仅改 worktree 中 pom.xml） |
| 构建命令 | `./mvnw -B -ntp -Prelease -Dgpg.skip=true -Dmaven.repo.local=<TMP>/m2-repo clean install`（完整跑测试，未 skipTests） |
| 构建结果 | BUILD SUCCESS；主项目测试 `Tests run: 132, Failures: 0, Errors: 0, Skipped: 0`；耗时 01:26 |
| JDK | 17.0.19（vendor: Microsoft） |
| Maven | 3.9.11（./mvnw wrapper） |
| `<TMP>` | `C:\Users\Administrator\AppData\Local\Temp\s7rc1rUTtyY`（验证结束后已删除） |

## 2. 候选制品（worktree `target/` 下断言存在，安装至 `<TMP>/m2-repo`）

| 制品 | sha256 | 大小 |
|---|---|---|
| smart-s7-connector-1.0.0-rc.1.jar | `b7bf06e0f088fea3aba336c97cc408a7814d9515371c7d1ad2c7222be3e085f7` | 107,880 字节 |
| smart-s7-connector-1.0.0-rc.1-sources.jar | `c9dea24be9a026cdb4411be89ca24779792b50aba35c10bb2552b24202b47d39` | 86,402 字节 |
| smart-s7-connector-1.0.0-rc.1-javadoc.jar | `31c16a4b325b7f274f75f91c0d9f8397ad18290401a3fa6f827066a81a79992a` | 404,032 字节 |

javadoc jar 内容抽查：根部含 `index.html`、`overview-tree.html`、`allclasses-index.html`，并含 `io/github/maidamai/s7connector/api/` 等包路径条目，非空壳。

## 3. 许可资源断言（审计第六节）

对主 jar 与 sources jar 分别以 `jar tf` 断言条目**精确存在**（每条目恰好出现 1 次）：

| META-INF 条目 | 主 jar | sources jar |
|---|---|---|
| `META-INF/LICENSE` | ✓（1/1） | ✓（1/1） |
| `META-INF/NOTICE` | ✓（1/1） | ✓（1/1） |
| `META-INF/LICENSE_LIBNODAVE.txt` | ✓（1/1） | ✓（1/1） |
| `META-INF/THIRD_PARTY_NOTICES.md` | ✓（1/1） | ✓（1/1） |
| `META-INF/licenses/LGPL-2.0.txt` | ✓（1/1） | ✓（1/1） |

合计 10/10 通过。

内容断言：自主 jar 解包 `META-INF/licenses/LGPL-2.0.txt`，文件 25,750 字节（非空），首行为 "GNU LIBRARY GENERAL PUBLIC LICENSE / Version 2, June 1991"，全文含字符串 "GNU LIBRARY GENERAL PUBLIC LICENSE" 共 2 处，与 LGPL-2.0 文本一致。

## 4. 干净消费者工程

- 全新目录 `<TMP>/s7-consumer`（与主仓库源码零关联，不从主仓库解析任何东西）。
- 坐标：`com.example:s7-consumer:1.0.0`。
- 依赖：
  - 编译期：`io.github.maidamai:smart-s7-connector:1.0.0-rc.1`（候选包，仅从隔离本地仓库 `<TMP>/m2-repo` 解析）；
  - 测试期：`org.junit.jupiter:junit-jupiter:5.14.2`（与主项目同版本）；surefire 3.5.4（与主项目同版本）；`maven.compiler.release=17`（本机 JDK 17.0.19 兼容值）。
- 运行命令：`mvn -B -ntp -Dmaven.repo.local=<TMP>/m2-repo test`（Maven 3.9.11 wrapper 分发版；候选包及其传递依赖全部命中隔离仓库，junit/surefire 等消费者侧插件与依赖从 Central 拉取属正常）。
- 约束遵守：测试只编译期引用 `api`、`api.factory`、`exception` 公开包，无任何反射、无 `impl` 内部类引用。

## 5. 消费者测试结果

结果：**Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 —— 全绿。**

`PublicApiContractTest`（4 个）：

1. `publicApiClassesAreLoadableFromCandidateJar` — `S7ConnectorFactory`、`S7SerializerFactory`、`DaveArea`、`S7Type`、`S7Exception`、`S7PartialWriteException` 六个公开类型经编译期引用可从候选 jar 加载，且包名均位于公开 API 面（无 `impl` 依赖）。
2. `stringTypeByteSizeIsTwo` — `S7Type.STRING.getByteSize() == 2`。
3. `s7ExceptionIsInstantiableWithMessage` — `new S7Exception("x")` 可实例化且 `getMessage().equals("x")`。
4. `factoryRejectsNullPlcType` — `S7ConnectorFactory.buildTCPConnector(null)` 抛 `IllegalArgumentException`（javadoc 声明的基本契约）。

`UnreachableHostContractTest`（2 个，均带 `@Timeout(30)` 防挂死；目标 `192.0.2.1`，RFC 5737 TEST-NET-1 不可路由地址，`withTimeout(1500)`）：

5. `unreachableHostConnectionFailsPerContract` — 连接不可达主机必须以 `IOException`（含子类）或 `S7Exception` 之一失败（`docs/api-contract.md` Error categories 允许的两种），显式 try/catch 接受这两种、成功即测试失败；若拿到连接器实例则进一步断言失败后不可复用（再次读继续以契约方式失败）且失败路径 `close()` 不抛。实测两用例均在超时上限内以契约允许的异常失败（实现路径 `S7TCPConnection.setupSocket` 将底层连接失败包装为 `S7Exception` 抛出）。
6. `secondIndependentBuildAttemptAlsoFailsPerContract` — 第二次独立连接尝试同样按契约失败，排除偶然。

附注：消费者未绑定 SLF4J provider，库侧日志按 SLF4J 语义退化为 NOP（输出 W 级提示）。库本身不携带绑定、留给消费者选择，属正确行为，非缺陷。

## 6. 结论

在以下证据范围内：**候选包（commit `21abe17`，版本 1.0.0-rc.1）从构建、许可资源完整性到干净消费者可用性均通过验证，可作为 1.0.0 正式发布的基础。**

- 主项目 132 个测试全绿（含 loopback Tier 1 证据链）；
- 三制品（binary/sources/javadoc）齐备、可校验、许可资源 10/10 精确存在；
- 全新消费者工程仅凭 GAV 坐标 `io.github.maidamai:smart-s7-connector:1.0.0-rc.1` 从隔离本地仓库完成依赖解析，公开 API 可加载且失败契约（不可达主机）实测符合 `docs/api-contract.md`。

## 7. 未验证项（如实声明）

以下各项**未执行、未验证**，发布前须由具备条件者补齐或在发布说明中明示：

1. **真实 PLC 连接/只读/隔离写验证**：无实机环境，未执行。`docs/compatibility.md` 中全部 6 个实机行（S7-1500/1200/300/400/200 Smart/第三方软 PLC）的 Connection/Read/Write 三列均为"未验证 / Not verified"，仅本地 loopback 行（Tier 1）为连接/读已验证、写未验证。本报告**未包含任何实机证据**。
2. **Maven Central 真实解析**：候选包仅存在于隔离本地仓库 `<TMP>/m2-repo`，未发布（`io.github.maidamai` 的 Central 发布可用性、POM 元数据、校验和文件在 Central 侧的解析）均未验证。本地仓库解析成功不等于 Central 解析成功。
3. **GPG 签名**：构建以 `-Dgpg.skip=true` 干跑，未产生 `.asc` 签名，签名与发布流程须由维护者按 `docs/releasing.md` 执行。

**本报告的 loopback/模拟证据不能替代实机验证**：loopback 仿真（`LocalS1500Server`）与不可达主机失败契约只覆盖协议栈的本地路径与失败分支，不能证明与任何真实西门子 PLC 的连接、读写行为及现场工况下的表现。

## 8. 附录：与实际发布物的对应关系（2026-09-17 补记）

本报告验证的候选构建自 `21abe17`。正式发布 tag `v1.0.0-rc.1`（commit `01a3537`）相对
`21abe17` 仅增加了文档与 pom 版本号（无任何 `.java` 源码变更，可由
`git diff 21abe17 01a3537 -- src/` 为空核实）。tag 构建产物（完整测试 132/132 后以
release profile 构建、GPG 签名）已作为 GitHub Pre-release 附件发布，其校验和以该
Release 的 `SHA256SUMS` 附件为准（因 jar 条目时间戳，字节级校验和与本报告第 2 节
不同属正常现象；源码同一性以上述空 diff 为准）。当日实机尝试记录见
[live-plc-attempt-2026-09-17.md](live-plc-attempt-2026-09-17.md)。
