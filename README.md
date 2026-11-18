# z-llm-agent

> **CLI Agent 服务化（一个产品，一个仓）** —— 把本机 CLI agent（Claude Code / Hermes / Qoder / Opencode / Minimax）
> 暴露成 OpenAI 兼容的可对话 agent：proxy 进程管"接进来"，core/client/starter 管"服务化出去"。
>
> **2026-10-26 收敛为独立仓**：`z-team/z-llm-agent`（4 模块）+ 当日短暂独立的 `z-llm-agent-proxy` 合并为本 reactor。
> 上游血缘：feature-team 的 `future-llm-agent`（五模块一一对应）。包名 `com.zifang.z.team.*` 原样保留。

## 📋 基本信息

| 项 | 值 |
|---|---|
| 形态 | **库 + 服务混合 reactor**（5 模块；proxy 是可运行服务，其余是库件） |
| 坐标 | `io.github.yuku123:z-llm-agent`（`${revision}`，当前 `1.0.47`；Central 历史随 z-team 发到 1.0.46） |
| parent | `io.github.yuku123:z-boot-parent:1.2.4` |
| Java | Java 8 编译 / JRE 17 运行 |
| 兄弟仓钉版 | `z-team-agent-core:1.0.46` · `z-llm-msghub-client:1.0.46` · `z-util-{core,parser-json,dsl-kernel}:1.2.4`（reactor DM 统一钉） |

## 🎯 能力清单（逐条对得上代码）

| 能力 | 状态 | 代码位置 |
|---|---|---|
| 进程启动 + `--port` / `--nacos.addr` 解析 | ✅ | `proxy/ProxyApplication.java` |
| HTTP 服务（JDK `com.sun.net.httpserver`，非 Spring） | ✅ | `proxy/ProxyHttpServer.java` |
| `POST /v1/chat/completions` OpenAI 兼容（SSE 流式 + 非流式） | ✅ | `proxy/ProxyChatHandler.java` |
| `GET /v1/cli-agents` 列出本机可用 CLI | ✅ | `proxy/ProxyCliListHandler.java` |
| `POST /v1/session/open` 会话路由 | ✅ | `proxy/ProxySessionHandler.java` + `proxy/SessionRegistry.java` |
| `GET /health` | ✅ | `proxy/ProxyHttpServer.java` |
| 本机 CLI 自动发现 | ✅ | `proxy/CliDiscoveryService.java` |
| Claude Code 适配 | ✅ | `wrapper/ClaudeCodeWrapper.java` + `ClaudeAnthropicConfig.java` |
| Qoder 适配 | ✅ | `wrapper/QoderProcessWrapper.java` |
| Hermes 适配 | ✅ | `wrapper/HermesProcessWrapper.java` + `HermesClaudeConfigLoader.java` |
| Opencode 适配 | ✅ | `wrapper/OpencodeProcessWrapper.java` |
| Minimax 配置加载 | ✅ | `wrapper/MinimaxConfigLoader.java` |
| 进程复用 / 超时 / 提示投递 | ✅ | `CliProcessRegistry` · `CliProcessTimeout` · `CliPromptDelivery` |
| 流式事件解析（JSONL → SSE） | ✅ | `StreamJsonParser` · `StreamEventBridge` |
| Nacos 服务注册 | ✅ | `proxy/NacosRegistryService.java` |
| 单元测试 | ✅ 8 个测试类 | `src/test/java/.../wrapper/` |

**实测（2026-10-26，本机）**：

```
java -jar target/z-llm-agent-proxy.jar --port=19098
curl http://127.0.0.1:19098/health  →  {"status":"ok"}
```

## 🏗️ 项目结构（5 模块）

| 模块 | 职责 | 产物 |
|---|---|---|
| `z-llm-agent-common` | DTO、错误码（零运行时依赖） | 库件 |
| `z-llm-agent-proxy` | 本地 CLI 代理：自动发现 + OpenAI 兼容流式 API + Nacos 注册 | **fat jar 服务**（主类 `proxy.ProxyApplication`） |
| `z-llm-agent-core` | CliAgentService 门面、proxy 生命周期、代码仓磁盘运维 | 库件 |
| `z-llm-agent-client` | HTTP SDK（`CliAgentClient`） | 库件 |
| `z-llm-agent-starter` | 可运行 Spring Boot 进程 | `*-boot.jar`（主类 `cliagent.CliAgentApplication`） |

```
z-llm-agent/
├── pom.xml                  # reactor：parent z-boot-parent:1.2.4，flatten 常开
├── _doc/  deploy/           # 架构文档与 proxy 的 Dockerfile/compose/Makefile
├── z-llm-agent-proxy/src/   # proxy/（HTTP 层 10 类）+ wrapper/（CLI 适配 17 类）+ 8 测试类
└── z-llm-agent-{common,core,client,starter}/
```

## 构建与运行

```bash
mvn -B -DskipTests package            # 五模块全绿，proxy 产出 fat jar（约 47 MB），starter 产出 *-boot.jar
java -jar z-llm-agent-proxy/target/z-llm-agent-proxy.jar --port=9098 --nacos.addr=127.0.0.1:8848
curl http://127.0.0.1:9098/health
```

## ⚠️ 已知问题（平移时发现，非本次引入）

1. **`CliAgentRegistry.loadFromFile` 启动期抛 `ParseException: Rule 'json' failed to match`** ——
   读本机 CLI 注册文件时内容为空/非 JSON 触发。服务不因此退出（`/health` 仍 ok），但 CLI 列表可能为空。
   建议单独开卡修：要么给空文件兜底，要么把注册文件纳入 `_doc` 说明。
2. **启动约 15–18 秒** —— `CliDiscoveryService` 扫本机 CLI 耗时。基线如此，未优化。

## 与其它仓的关系

| 仓 | 关系 |
|---|---|
| `z-team` | **原宿主**。2026-10-26 起整个家族（含 4 个库模块）迁出，z-team 改为消费 Central 坐标（DM 钉 1.0.46） |
| `z-team-agent-core` | 上游依赖（`CliAgentConfig` / `CliAgentRegistry` / `CliChatExecutor` / DTO），走 Central `1.0.46` |
| `z-agent-proxy` | **不是同一个东西**：那个是 Aider/Cursor → `kernel.agent.Agent` SPI 的纯 jar 库；本仓是 CLI → OpenAI HTTP 的服务 |
| `z-agent` / `z-bot` | 潜在消费方：把本仓当"本机 CLI 算力入口" |

## 发布

- **Central**：`io.github.yuku123:z-llm-agent-*` 六坐标（含 reactor 根 pom）。历史上一直随 z-team 发布（Central 已有 1.0.14 ~ 1.0.46），本仓首发 **1.0.47（2026-10-26）**，不与历史断线。
- **发布包只含库件**：proxy 的 shaded fat jar（44MB）与 starter 的 `-boot.jar`（126MB）**不上 Central**（central profile 里 skip 了 shade/repackage）——fat jar 是部署产物不是消费坐标，本地 `mvn package` 随时复现。起因：本机上行 0.13MB/s，带 fat jar 的 155MB bundle 上传必被 Cloudflare 掐断（Broken pipe）。
- **本机代理坑**：`central-publishing-maven-plugin` 的 HTTP 客户端走本机代理（127.0.0.1:57681）上传必 Broken pipe（bundle 小到 963K 也断）；构建/签名/staging 都正常。`deploy_maven_center.sh` 已内置兜底：bundle 生成后改用 curl 直传 Portal API（实测 3 秒 201）。
- **发布命令**：`bash _doc/003_script/deploy_maven_center.sh publish`（凭证在仓内 `.env`，GPG keyring 在仓内 `.gnupg`，与 z-util 同一把私钥）。
- **镜像**：`make image` → 组织中心仓库。

---

MIT License · z-opc-foundation
