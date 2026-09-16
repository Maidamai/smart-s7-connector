# Third-Party Notices

本文件列出本仓库（smart-s7-connector）包含的第三方来源代码及其原始许可证。
详细逐文件溯源分析见 [docs/provenance.md](docs/provenance.md)。

## 1. s7connector

- **作者**: Thomas Rudin 及 s7connector 贡献者
- **来源仓库**: https://github.com/s7connector/s7connector
- **原始许可证**: Apache License 2.0
  - 查证: 仓库根 `LICENSE.txt`（https://raw.githubusercontent.com/s7connector/s7connector/master/LICENSE.txt ，内容为 Apache License 2.0 全文）；
    `pom.xml` 声明 "The Apache Software License, Version 2.0"（https://raw.githubusercontent.com/s7connector/s7connector/master/pom.xml ）。
- **本仓库使用的部分**:
  - `src/main/java/io/github/maidamai/s7connector/api/**`（接口、注解、工厂、`S7Type`、`DaveArea` 等）
  - `src/main/java/io/github/maidamai/s7connector/exception/**`
  - `src/main/java/io/github/maidamai/s7connector/impl/`（`S7BaseConnection`、`S7TCPConnection`）
  - `src/main/java/io/github/maidamai/s7connector/impl/serializer/**`（Bean 解析与各类型序列化器）
  - `src/main/java/io/github/maidamai/s7connector/impl/transport/**`（传输层接口）
- **修改说明**: 本项目在 s7connector 基础上重命名 Java 包（`com.github.s7connector` → `io.github.maidamai.s7connector`），
  并增加了超时处理、错误传播、并发防护等健壮性改动，另将上游中的 nodave 模块保留于 `impl/nodave/`（见下条）。

## 2. libnodave

- **作者**: Thomas Hergenhahn (thomas.hergenhahn@web.de)，2005
- **来源项目**: http://libnodave.sourceforge.net/ （SourceForge 项目页 https://sourceforge.net/projects/libnodave/ ）
- **原始许可证**: GNU Library General Public License (LGPL) version 2.0 或（由你选择）任何更新版本（"LGPL-2.0-or-later"）
  - 查证: SourceForge 项目页标注 "GNU Library or Lesser General Public License version 2.0 (LGPLv2)"
    （https://sourceforge.net/projects/libnodave/ ）；
    本仓库及上游 s7connector 中每个 nodave 源文件头部均保留 "GNU Library General Public License ... either version 2, or (at your option) any later version" 声明。
- **本仓库使用的部分**: `src/main/java/io/github/maidamai/s7connector/impl/nodave/` 下全部 7 个文件:
  `Nodave.java`、`PDU.java`、`PLCinterface.java`、`Result.java`、`ResultSet.java`、`S7Connection.java`、`TCPConnection.java`
- **传递路径**: libnodave → s7connector（Java 移植，`com.github.s7connector.impl.nodave`，头部的 libnodave/LGPL 声明原样保留，
  查证: https://raw.githubusercontent.com/s7connector/s7connector/master/src/main/java/com/github/s7connector/impl/nodave/S7Connection.java ）→ 本仓库（包重命名 + 功能改动）。
- **修改说明**: 在 s7connector 的 Java 移植基础上继续修改，包括包名调整、超时/错误处理增强等；文件头部的 libnodave 版权与 LGPL-2.0+ 声明全部保留。
- **许可全文**: 见 [LICENSES/LGPL-2.0.txt](LICENSES/LGPL-2.0.txt)（来源 https://www.gnu.org/licenses/old-licenses/lgpl-2.0.txt ）。
  上游摘要声明另见 `LICENSE_LIBNODAVE.txt`。

## 许可证兼容性提示

本仓库根 `LICENSE` 与 `pom.xml` 目前声明 Apache-2.0，但上述 nodave 文件为 LGPL-2.0+。
二者组合分发存在已知的许可证兼容性问题，最终授权范围须由维护者确认（见 docs/provenance.md 第 4、5 节）。
本文件仅为事实性来源声明，不构成法律意见。
