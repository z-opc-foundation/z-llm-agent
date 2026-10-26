# z-llm-agent-proxy 架构总览

> 2026-10-26 从 `z-team/z-llm-agent/z-llm-agent-proxy` 平移成独立仓时整理。

## 1. 它解决什么问题

本机的 CLI agent（Claude Code、Qoder、Hermes、Opencode、Minimax）各有各的调用形状：
子进程、各自的参数、各自的流式输出格式。上层平台（z-agent / z-team / z-bot）不想为每家
写一份调度代码，也不想直接管进程。

本仓把它们收敛成**一个 OpenAI 兼容的 HTTP 口**：任何会说 OpenAI 协议的客户端，
`POST /v1/chat/completions` 就能用上本机算力。

## 2. 两个包，两层职责

```
proxy/    （10 类）  HTTP 面 + 发现 + 注册 + 会话     ← "对外"
wrapper/  （17 类）  CLI 适配 + 进程 + 流式解析       ← "对内"
```

```
客户端 ──HTTP──▶ ProxyHttpServer
                   ├─ /v1/cli-agents        ProxyCliListHandler ──▶ CliDiscoveryService
                   ├─ /v1/session/open      ProxySessionHandler ──▶ SessionRegistry
                   ├─ /v1/chat/completions  ProxyChatHandler    ──▶ CliChatExecutor
                   └─ /health
                                                    │
                                          CliAgentWrapperRegistry
                                                    │
                        ┌───────────┬───────────┬───┴───────┬────────────┐
                   ClaudeCode    Qoder       Hermes      Opencode      Minimax
                        └───────────┴───────────┴───────────┴────────────┘
                              CliProcessRegistry / Timeout / PromptDelivery
                                        │
                                  StreamJsonParser → StreamEventBridge → SSE
```

**关键点：`proxy` 不认识任何一家 CLI 的参数**。所有厂商差异锁在 `wrapper/<X>ProcessWrapper`
里，新增一家 = 加一个 Wrapper + 在 Registry 注册，不改 HTTP 层。

## 3. 为什么是 fat jar 而不是 Spring 服务

它是个**本机进程**，不是被装配的库：

- 零 Spring 依赖（HTTP 用 JDK 自带 `com.sun.net.httpserver`）；
- 日志用 `java.util.logging`，不引 log4j2/slf4j 绑定；
- shade 的过滤器**排掉 `org/springframework/**` 和 mybatis 生态**（见 pom 注释 FEATURE054）：
  `z-team-agent-core` 会传递带进 spring-boot-starter，如果照单全收打进 fat jar，
  下游把它当依赖引入时会读到旧版类，与下游自己的 mybatis/spring 冲突。

## 4. 平移时改了什么

| 项 | 改前 | 改后 |
|---|---|---|
| parent | `z-llm-agent`（`relativePath ../pom.xml`） | `z-boot-parent:1.1.2`（`relativePath` 留空，在 repo1） |
| 版本 | 继承 z-team 的 `${revision}`（1.0.47） | 本仓自持 `${revision}`，起点 `1.0.47`（接 Central 上的 1.0.46） |
| `z-team-agent-core` | 无版本（父 DM 管） | **显式 `1.0.46`**（z-boot-parent 不管这条线） |
| 包名 | `com.zifang.z.team.agent.proxy` | **不变** |
| 业务代码 | — | **一行未改** |
| enforcer 日志规则 | z-team 父链有 | **本仓不加**：规则是给 Spring 服务的，本仓不需要，且会误杀传递依赖 |

## 5. 待定项

| 项 | 状态 |
|---|---|
| `CliAgentRegistry.loadFromFile` 空文件兜底 | 未做（见 README 已知问题 1） |
| 启动 15–18 秒的发现耗时 | 未优化 |
| 是否进 z-opc 主壳 | 不需要 —— 它是本机侧进程，不是服务端模块 |
| 健康检查接入 z-observer | 未定（`/health` 已存在，指标未埋） |
