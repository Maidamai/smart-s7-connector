# rc.2 发布附件失效关闭验证记录（2026-09-17）

日期：2026-09-17 · 验证对象：GitHub Pre-release **v1.0.0-rc.2**

## 结论（先说结果）

**v1.0.0-rc.2 的实际发布附件已通过失效关闭（fail-closed）验证全链路。** 权威运行是 GitHub Actions 的 `Verify published release` 工作流（run 35194508600，由 `release: published` 事件自动触发，Linux runner，对**实际上传的字节**执行）：四工件 SHA256 校验、四项 GPG 签名按钉住主指纹验证、签名 POM 坐标核对、许可资源检查、隔离仓库安装、公共 API 消费者成功路径测试，全部 PASS（`RESULT: ALL STEPS PASSED`）。这是本仓库第一次有候选版本在发布后即完成"实际附件级"验证闭环。

## 发布链（全部从确定提交出发）

| 步骤 | 事实 |
|---|---|
| 确定提交 | `808501c`（chore(release): prepare 1.0.0-rc.2），master CI 六项检查全绿（build 8/17/21、release-dry-run、consumer-contract、verifier-tests 30/30） |
| 构建 | 同提交本地 `mvn -B -ntp -Prelease clean verify`：141/141 测试通过，GPG 签名 4 文件（3 JAR + POM） |
| tag | `v1.0.0-rc.2` → 808501c，本地 `git tag -v` 为 Good signature（签名子键 C6CFE70EFED02B81F0BC875C749E8EB8D07F3D41，主键 AF570E8790461C3809463B247E4B165E97DBB3DB） |
| Release | Pre-release，10 资产：main/sources/javadoc JAR、POM、各 `.asc`、四条目 `SHA256SUMS`、`release-signing-key.asc`（装甲公钥）。以草稿上传全部资产后再发布，避免 `published` 事件早于资产上传的竞态 |
| 发布后验证 | 工作流 run 35194508600 completed/success（25s），关键行见下 |

## 工作流验证输出（摘录，run 35194508600）

```text
[PASS] all four artifact checksums (including the POM)
[PASS] signature smart-s7-connector-1.0.0-rc.2.jar;      signing=C6CFE70EFED02B81F0BC875C749E8EB8D07F3D41 primary=AF570E8790461C3809463B247E4B165E97DBB3DB
[PASS] signature smart-s7-connector-1.0.0-rc.2.pom;      signing=… primary=AF570E…B3DB
[PASS] signature smart-s7-connector-1.0.0-rc.2-sources.jar; signing=… primary=AF570E…B3DB
[PASS] signature smart-s7-connector-1.0.0-rc.2-javadoc.jar; signing=… primary=AF570E…B3DB
[PASS] exact POM GAV, main/sources license resources and Javadoc index
[PASS] verified JAR and POM installed into a fresh isolated repository
[PASS] consumer success-path tests; RESULT: ALL STEPS PASSED
```

注意签名细节：本版工件由**签名子键** C6CF…3D41 签出，验证器经 VALIDSIG 第 11 字段回溯到钉住的**主指纹** AF57…B3DB 后放行——这正是 PR #6 子键验收路径设计的目标场景，首次在真实发布上生效。

## 制品摘要（SHA256SUMS）

```text
8eff88d91c8789d7494cb7a5fad33e17d118354bfe6edfaad706ec75844de4ea *smart-s7-connector-1.0.0-rc.2.jar
17c41e0b983b3ff2e9b2df9c4414c2da381fb223a0f8b2d5d295da7003568f9f *smart-s7-connector-1.0.0-rc.2.pom
edfb3814db347887b8a90ef1184b01e38886492beabd2a19fa7a9418bebaccd1 *smart-s7-connector-1.0.0-rc.2-sources.jar
88f3cdf13207e77483f48c5488557137baae1a569e7a819a59ea5e484a1f8701 *smart-s7-connector-1.0.0-rc.2-javadoc.jar
```

## 本机运行记录（如实区分）

- **发布前**：四工件 `.asc` 在本机默认钥匙环下逐一 `gpg --verify` 均为 `Good signature`；资产包在发布前即完成本地预验。
- **发布后本机全链路**：`PYTHON=python S7_VERSION=1.0.0-rc.2 bash consumer-smoke/verify-release.sh` 在**公钥导入**一步失败（退出码 1，其前四工件校验和 PASS）。根因已定位并复现：密钥实际导入成功（`public key "Caijiangtao" imported`），但 MSYS gpg 在 Windows CreateProcess 语境下事后拉起 `gpg-agent` 失败（`exit status 2`）污染了 gpg 退出码——本机（Windows Git Bash + Windows Python + MSYS gpg）环境限制，与制品和验证逻辑无关；同一脚本由 GitHub Linux runner 完整通过。本机完整复验可随时改用工作流（workflow_dispatch）或原生 Windows GnuPG（`GPG=<原生 gpg.exe>`）。

## 与 rc.1 的关系 / 未验证项

- `v1.0.0-rc.1` 保持原样发布：它含 C1 缺陷且无签名 POM，在新门下**按设计失败**（第三轮已实测记录：docs/verification/consumer-smoke-rc1-2026-09-17.md）。本版（rc.2）含 C1/C2 修复，消费者 primitive 数组 round-trip 在实际下载制品上转为通过。
- 仍未验证：Maven Central 发布（无凭据）、实机 PLC（等指定设备）、真实采用证据。公钥已随 Release 资产公开（`release-signing-key.asc`），keyserver 上传与 GitHub 账号邮箱关联（消除 tag `verified=false/no_user` 状态）为可选后续。
