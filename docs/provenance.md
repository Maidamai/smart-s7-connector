# smart-s7-connector 代码溯源与许可证冲突报告（T09）

日期：2026-09-16 · 分支：oss-hardening · 状态：**未结案**

本报告为审计任务书 T09 的产出，纯事实梳理与风险分析，**不构成法律意见**；最终授权范围由维护者确认。

## 1. 背景

- 仓库根 `LICENSE`（Apache-2.0 全文）与 `pom.xml` `<licenses>` 均声明 Apache-2.0。
- `src/main/java/io/github/maidamai/s7connector/impl/nodave/` 下多个文件保留 libnodave 头部，声明 "GNU Library General Public License ... either version 2, or (at your option) any later version"（LGPL-2.0-or-later），版权 Thomas Hergenhahn 2005。
- `LICENSE_LIBNODAVE.txt`（800 字节）只是声明摘要，指向 libnodave 源码树中的 `COPYING` 文件，**并非许可全文**；本仓库无该全文。本次已补齐：`LICENSES/LGPL-2.0.txt`。
- 本项目基于 s7connector 演进（包名 `com.github.s7connector` → `io.github.maidamai.s7connector`），s7connector 基于 libnodave 的 Java 移植。

## 2. 上游事实核查（已查证，附 URL）

| 事实 | 证据 URL | 结果 |
|---|---|---|
| libnodave 许可证 | https://sourceforge.net/projects/libnodave/ | SourceForge 标注 "GNU Library or Lesser General Public License version 2.0 (LGPLv2)"；项目由 Thomas Hergenhahn 维护（捐赠邮箱 thomas.hergenhahn@web.de）。与源文件头部 "either version 2, or any later version" 一致，即 LGPL-2.0-or-later。 |
| s7connector 许可证 | https://raw.githubusercontent.com/s7connector/s7connector/master/LICENSE.txt | Apache License 2.0 全文。 |
| s7connector pom 声明 | https://raw.githubusercontent.com/s7connector/s7connector/master/pom.xml | `<name>The Apache Software License, Version 2.0</name>`。 |
| 上游 nodave 包现状 | https://raw.githubusercontent.com/s7connector/s7connector/master/src/main/java/com/github/s7connector/impl/nodave/S7Connection.java （包路径经 GitHub tree API 确认为 `src/main/java/com/github/s7connector/impl/nodave/`，注意任务书中的 org/s7connector 路径 404，实际为 com/github/s7connector） | 上游文件头部同样原样保留 libnodave (C) Thomas Hergenhahn 2005 及 LGPL-2.0+ 声明——即上游 s7connector 虽整体声明 Apache-2.0，其 nodave 文件同样带 LGPL 头，冲突并非本项目引入。 |
| libnodave 官方源码头部逐字核对 | 未能直接访问 SourceForge CVS/SVN 原始 C 源（本次未取到），以上游 Java 移植文件头部为准 | 标记：未能直接核实，但多级传递一致。 |

## 3. 逐文件清单

### 3.1 带 libnodave/LGPL-2.0+ 头部的文件（7 个，均在 `src/main/java/io/github/maidamai/s7connector/impl/nodave/`）

所有 7 个文件头部均为：`Part of Libnodave, a free communication libray for Siemens S7 / (C) Thomas Hergenhahn (thomas.hergenhahn@web.de) 2005.` + LGPL-2.0-or-later 声明。

| 文件 | 来源判断 | 本项目改动概述 |
|---|---|---|
| `impl/nodave/Nodave.java` | 直接源自 libnodave（经 s7connector Java 移植） | 包名重命名；常量/工具类沿用 |
| `impl/nodave/PDU.java` | 同上 | 包名重命名；PDU 组包/解包逻辑沿用，错误处理增强 |
| `impl/nodave/PLCinterface.java` | 同上 | 包名重命名；接口定义沿用 |
| `impl/nodave/Result.java` | 同上（`@author Thomas Hergenhahn`） | 包名重命名；结果码解释沿用 |
| `impl/nodave/ResultSet.java` | 同上（`@author Thomas Hergenhahn`） | 包名重命名 |
| `impl/nodave/S7Connection.java` | 同上 | 包名重命名；协议状态机沿用，超时/重试等健壮性改动 |
| `impl/nodave/TCPConnection.java` | 同上 | 包名重命名；传输层接入本项目 `S7Transport` 抽象，超时处理增强 |

### 3.2 不带 libnodave 头部、疑似源自 s7connector 的文件（`@author Thomas Rudin` 标注，18 个）

以下文件以 `@author Thomas Rudin` 标注或属于其编写的包结构，源自 s7connector（Apache-2.0，查证 URL 见第 2 节）：

- `api/`：`DaveArea.java`、`S7Connector.java`、`S7Serializable.java`、`S7Serializer.java`、`S7Type.java`、`api/annotation/Array.java`、`api/annotation/Datablock.java`、`api/annotation/S7Variable.java`、`api/factory/S7ConnectorFactory.java`、`api/factory/S7SerializerFactory.java`
- `exception/S7Exception.java`
- `impl/S7BaseConnection.java`、`impl/S7TCPConnection.java`（后者头部含 `@href http://libnodave.sourceforge.net/`，为 s7connector 原创但设计参考 libnodave 协议思路，非代码移植）
- `impl/nodave/PLCinterface.java`、`impl/nodave/PDU.java`、`impl/nodave/Nodave.java`、`impl/nodave/TCPConnection.java`（兼有 Rudin 标注，归入 3.1 管理）
- `impl/serializer/parser/BeanEntry.java`
- 其余 `impl/serializer/**`、`impl/transport/**` 文件虽无明确 @author，结构与上游一致，同样按 s7connector 来源（Apache-2.0）对待。

注意：`api/S7Type.java`、`impl/S7BaseConnection.java`、`impl/S7TCPConnection.java` 会命中 "nodave" 关键字检索，但命中的是 `DaveArea` 类引用/`@href` 注释而非 libnodave 许可头部，不属于 LGPL 头文件。

总计：`src/main/java` 共 47 个 Java 文件，其中 7 个带 LGPL-2.0+ 头部，其余 40 个按 s7connector（Apache-2.0）来源处理。

## 4. 冲突分析

- 根 `LICENSE`/`pom.xml` 声明 Apache-2.0 vs nodave 7 文件头部声明 LGPL-2.0-or-later。
- LGPL-2.0 关键义务（概述）：修改后的文件须继续声明其为库的修改版并保留原声明；须向接收者提供 LGPL 许可全文副本（LGPL-2.0 §1、§2）；以库为基础的衍生作品受 LGPL 约束。目前仓库内缺少 LGPL 全文副本（已由 `LICENSES/LGPL-2.0.txt` 补齐）。
- Apache-2.0 关键义务（概述）：分发时附带 Apache-2.0 全文与 NOTICE 声明；修改文件须做显著声明。
- 已知争议点：FSF 认为 Apache-2.0 与 LGPL-2.1（及更早 LGPL-2.0）**单向不兼容**——可以在同一作品中组合分发，但组合作品整体须按 LGPL 分发（FSF 许可证兼容性矩阵：https://www.gnu.org/licenses/license-list.en.html#ApacheLicence ）。即：不能简单地把整个制品声明为纯 Apache-2.0 分发。
- 本冲突继承自上游 s7connector（其 master 分支同样如此，见第 2 节核查），非本项目引入，但本项目继承了相应的合规风险。
- 再次强调：以上为事实与技术性梳理，不构成法律意见。

## 5. 维护者待决问题清单

**(a) 是否联系 Thomas Hergenhahn 获取再许可授权 / 确认 dual-license**
- 若选是：通过 thomas.hergenhahn@web.de 或 SourceForge 渠道联系，请求以 Apache-2.0 或更宽许可再授权 nodave 移植代码；取得书面（邮件即可）确认后，更新本报告与 THIRD_PARTY_NOTICES.md，结案。
- 若选否/无回音：按 (b) 处理。

**(b) 是否将整个制品按 LGPL-2.0+ 分发**
- 若选是（最稳妥）：修改根 `LICENSE`/`pom.xml` 为 LGPL-2.0-or-later（或采用 "LGPL-2.0+ 对应 nodave 部分、其余 Apache-2.0" 的分文件声明），保留全部 LGPL 头部，随制品分发 `LICENSES/LGPL-2.0.txt`，README 标注。注意 Apache-2.0 部分义务仍须履行。
- 若选否：维持 Apache-2.0 总声明但必须完成 (a)，否则正式发布风险未消除。

**(c) 混合分发时 NOTICE / 文档写法**
- 若混合：NOTICE 中分别声明两部分来源与许可（本次已在 NOTICE 追加相应行）；发布制品同时携带 Apache-2.0 文本、LGPL-2.0 文本（LICENSES/）与 THIRD_PARTY_NOTICES.md；pom 可用多个 `<license>` 条目或改用分模块拆分。

## 6. 结论（未结案）

在维护者就第 5 节问题作出决定并落实之前，**正式发布保持暂停**（与本审计其余项结论一致）。本报告与 THIRD_PARTY_NOTICES.md、LICENSES/LGPL-2.0.txt 构成决策所需的事实基础。
