# smart-s7-connector 代码溯源与许可证冲突报告（T09）

日期：2026-09-16 · 分支：oss-hardening · 状态：**已结案（2026-09-17，维护者确认，见第 5 节决策记录）**

本报告为审计任务书 T09 的产出，纯事实梳理与风险分析，**不构成法律意见**；最终授权范围已由维护者于 2026-09-17 确认（见第 5 节决策记录）。

## 1. 背景

- 仓库根 `LICENSE`（Apache-2.0 全文）与 `pom.xml` `<licenses>` 均声明 Apache-2.0。
- `src/main/java/io/github/maidamai/s7connector/impl/nodave/` 下多个文件保留 libnodave 头部，声明 "GNU Library General Public License ... either version 2, or (at your option) any later version"（LGPL-2.0-or-later），版权 Thomas Hergenhahn 2005。
- `LICENSE_LIBNODAVE.txt`（800 字节）只是声明摘要，指向 libnodave 源码树中的 `COPYING` 文件，**并非许可全文**；本仓库无该全文。本次已补齐：`LICENSES/LGPL-2.0.txt`。
- 本项目基于 s7connector 演进（包名 `com.github.s7connector` → `io.github.maidamai.s7connector`），s7connector 基于 libnodave 的 Java 移植。

## 2. 上游事实核查（已查证，附 URL）

比对基准:上游 `s7connector/s7connector` master @ `fcc7662bd47a914619aa6b095925661cf5b920b6`
(该分支最新提交,2025-08-24;2026-09-17 查证时无更新)。本仓库内容锚点:初始导入提交
`00ecbbeca93336632016f91f4934ebb8f1f480a2`——第 3 节的来源归属以这两个固定提交为准,不随上游滚动 master 变化。

| 事实 | 证据 URL | 结果 |
|---|---|---|
| libnodave 许可证 | https://sourceforge.net/projects/libnodave/ | SourceForge 标注 "GNU Library or Lesser General Public License version 2.0 (LGPLv2)"；项目由 Thomas Hergenhahn 维护（捐赠邮箱 thomas.hergenhahn@web.de）。与源文件头部 "either version 2, or any later version" 一致，即 LGPL-2.0-or-later。 |
| s7connector 许可证 | https://raw.githubusercontent.com/s7connector/s7connector/fcc7662bd47a914619aa6b095925661cf5b920b6/LICENSE.txt | Apache License 2.0 全文。 |
| s7connector pom 声明 | https://raw.githubusercontent.com/s7connector/s7connector/fcc7662bd47a914619aa6b095925661cf5b920b6/pom.xml | `<name>The Apache Software License, Version 2.0</name>`。 |
| 上游 nodave 包现状 | https://raw.githubusercontent.com/s7connector/s7connector/fcc7662bd47a914619aa6b095925661cf5b920b6/src/main/java/com/github/s7connector/impl/nodave/S7Connection.java （包路径经 GitHub tree API 确认为 `src/main/java/com/github/s7connector/impl/nodave/`，注意任务书中的 org/s7connector 路径 404，实际为 com/github/s7connector） | 上游文件头部同样原样保留 libnodave (C) Thomas Hergenhahn 2005 及 LGPL-2.0+ 声明——即上游 s7connector 虽整体声明 Apache-2.0，其 nodave 文件同样带 LGPL 头，冲突并非本项目引入。 |
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

归属细分（避免"其余全部来自上游"的笼统归属）：
- **源自 s7connector（Apache-2.0，比对基准 `fcc7662`）**：上述清单文件，以及 `impl/serializer/**`
  中与上游 `fcc7662` 结构一致的转换器/解析器骨架。
- **本项目原创（Apache-2.0）**：`impl/transport/**` 全部文件（Netty 传输层为上游 `fcc7662` 所无）、
  `bean/PlcS7PointVariable.java`、`impl/serializer/batch/**`、`impl/S7ReadWindowProvider.java`，
  以及历次加固轮次对既有文件的修改（以本仓库 git 历史为准）。

注意：`api/S7Type.java`、`impl/S7BaseConnection.java`、`impl/S7TCPConnection.java` 会命中 "nodave" 关键字检索，但命中的是 `DaveArea` 类引用/`@href` 注释而非 libnodave 许可头部，不属于 LGPL 头文件。

总计：`src/main/java` 的 Java 文件中，7 个带 LGPL-2.0+ 头部（LGPL-2.0-or-later），其余按上述
"源自 s7connector / 本项目原创"细分归属，许可均为 Apache-2.0。文件数随仓库演进变化，以工作区
实际清单为准（2026-09-17 复核时为 48 个）。

## 4. 冲突分析

- 根 `LICENSE`/`pom.xml` 声明 Apache-2.0 vs nodave 7 文件头部声明 LGPL-2.0-or-later。
- LGPL-2.0 关键义务（概述）：修改后的文件须继续声明其为库的修改版并保留原声明；须向接收者提供 LGPL 许可全文副本（LGPL-2.0 §1、§2）；以库为基础的衍生作品受 LGPL 约束。目前仓库内缺少 LGPL 全文副本（已由 `LICENSES/LGPL-2.0.txt` 补齐）。
- Apache-2.0 关键义务（概述）：分发时附带 Apache-2.0 全文与 NOTICE 声明；修改文件须做显著声明。
- 已知争议点：FSF 认为 Apache-2.0 与 LGPL-2.1（及更早 LGPL-2.0）**单向不兼容**——可以在同一作品中组合分发，但组合作品整体须按 LGPL 分发（FSF 许可证兼容性矩阵：https://www.gnu.org/licenses/license-list.en.html#ApacheLicence ）。即：不能简单地把整个制品声明为纯 Apache-2.0 分发。
- 本冲突继承自上游 s7connector（其 master 分支同样如此，见第 2 节核查），非本项目引入，但本项目继承了相应的合规风险。
- 再次强调：以上为事实与技术性梳理，不构成法律意见。

## 5. 决策记录（2026-09-17 结案）

维护者于 2026-09-17 确认采用**分文件混合许可**（即原 (b)+(c) 组合），不再作为发布阻断项：

**(a) 是否联系 Thomas Hergenhahn 获取再许可授权** — 决定：暂不作为前置条件。
如后续希望获得纯 Apache-2.0 制品，仍可通过 thomas.hergenhahn@web.de 或 SourceForge 渠道寻求书面再授权；
取得后重写 `impl/nodave/` 的许可声明并更新本报告即可。该选项保留为后续改进，不阻断当前发布。

**(b) 分发方式** — 决定：按文件划分。
- `src/main/java/io/github/maidamai/s7connector/impl/nodave/**`（7 个文件）：**LGPL-2.0-or-later**，
  原始版权与许可头部全部保留，任何修改版继续按 LGPL 提供。
- 其余全部源文件（本项目原创 + 源自 s7connector 的 Apache-2.0 部分）：**Apache-2.0**，继续履行
  LICENSE/NOTICE 义务。
- 关于第 4 节 FSF 单向兼容性问题：nodave 头部授权为 "version 2, **or (at your option) any later
  version**"，因此**当维护者选择以单一整体许可发布组合制品时**，可依该条款将 LGPL 部分升级为
  LGPL-3.0-or-later；Apache-2.0 代码可单向并入 LGPL-3.0 作品（FSF 兼容性矩阵）。这只是维护者侧
  可用的一条合规路径，**不是**对使用方的授权：使用方对组合制品的再分发仍受其所含各文件许可的
  约束，不能任选整体许可。

**本次实际分发路线**（区别于上述未来可选项）：
1. **源码分发** = 本 GitHub 仓库（按 ref/commit 定位，含全部许可全文与文件头）。
2. **二进制分发** = Maven 制品（main JAR + sources JAR，随件携带 LICENSE、NOTICE、
   `LICENSE_LIBNODAVE.txt`、`THIRD_PARTY_NOTICES.md` 与 `licenses/LGPL-2.0.txt`，见 (c)）。
3. **源码/二进制相互定位**：发布版本必须先打 git tag，sources JAR 从同一 tag 构建并随发布上传，
   使任一二进制都能定位到其对应源码（流程见 `docs/releasing.md`）。
4. **义务边界（非法律结论）**：LGPL-2.0 的源码提供与许可全文随附义务针对的是*分发*行为——
   仓库公开满足"源码形态的仓库自身"的提供，但**不自动等同于**"随二进制分发对应源码"的义务履行；
   该义务由上述 tag/sources JAR 流程落实。文件级修改声明：本项目对 nodave 7 文件的改动在
   第 3.1 节逐文件记录，后续对这 7 个文件的实质修改应在提交说明中保持可追溯。

**(c) 混合分发的声明与制品** — 已落实：
- `pom.xml` `<licenses>` 声明两条（Apache-2.0 + LGPL-2.0-or-later），`<comments>` 注明各自适用范围，
  并明示这不是"任选其一"的双许可。
- `NOTICE` / `THIRD_PARTY_NOTICES.md` 分别声明两部分来源与许可（NOTICE 追加文本已于 2026-09-16 写入）。
- 主 JAR 与 sources JAR 的 `META-INF/` 携带 `LICENSE`、`NOTICE`、`LICENSE_LIBNODAVE.txt`、
  `THIRD_PARTY_NOTICES.md` 与 `licenses/LGPL-2.0.txt`（构建配置见 `pom.xml` `<resources>`，
  已于 2026-09-17 实际构建核验）。
- README 中英文小节更新为结案口径，不再声明"授权范围待确认"。

## 6. 结论（已结案）

T09 就此结案：文件级来源清单见第 3 节（比对基准为第 2 节的固定上游提交，不引用滚动 master），
上游查证见第 2 节，授权范围按第 5 节决策执行，制品声明与许可全文随件分发，实际分发路线见
5(b)。许可证按维护者 2026-09-17 的决策不再作为本项目的发布阻断项；该决策与本文档均为事实
梳理与决策记录，**不构成法律意见**。正式对外分发（发布到 Maven Central 等）之前，建议由有
经验的开源合规人员按发布时点的实际组成再作一次确认——分发的具体形态（制品内容、tag 与
sources JAR 的对应）以届时实际发布物为准。
