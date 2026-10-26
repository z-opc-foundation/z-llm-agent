# z-llm-agent-proxy

> **CLI Agent 代理服务** —— 把本机 CLI agent 暴露成 OpenAI 兼容的 streaming chat API，并向 Nacos 注册。
>
> **2026-10-26 从 `z-team/z-llm-agent/z-llm-agent-proxy` 平移为独立仓。**
> 包名 `com.zifang.z.team.agent.proxy` **原样保留**（内部包，无对外 API 承诺，改名收益不抵风险）。

## 📋 基本信息

| 项 | 值 |
|---|---|
| 形态 | **服务**（单模块 jar + shade fat jar），不是库，没有 core/starter 分层 |
| 坐标 | `io.github.yuku123:z-llm-agent-proxy`（`${revision}`，当前 `1.0.47`） |
| parent | `io.github.yuku123:z-boot-parent:1.1.2` |
| Java | Java 8 编译 / JRE 17 运行 |
| 端口 | `9098`（`--port=` 可覆盖） |
| 产出物 | `target/z-llm-agent-proxy.jar`（fat jar，主类 `com.zifang.z.team.agent.proxy.ProxyApplication`） |
| 代码量 | 27 个 main 类 + 8 个测试类 |
| 依赖 | `z-team-agent-core:1.0.46`（Central）· jackson · snakeyaml · nacos-client · z-util-parser-json |

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

## 🏗️ 项目结构

```
z-llm-agent-proxy/
├── pom.xml                          # 单模块 + shade fat jar（排除 spring/mybatis 生态）
├── _doc/001_arch/00-overview.md
├── deploy/                          # Dockerfile + compose + Makefile
└── src/
    ├── main/java/com/zifang/z/team/agent/proxy/     # HTTP 层 + 发现 + 注册 + 会话（10 类）
    ├── main/java/com/zifang/z/team/agent/wrapper/   # CLI 适配层（17 类）
    └── test/java/com/zifang/z/team/agent/wrapper/   # 8 个测试类
```

## 构建与运行

```bash
mvn -B -DskipTests package            # 产出 target/z-llm-agent-proxy.jar（约 47 MB）
java -jar target/z-llm-agent-proxy.jar                       # 默认 9098
java -jar target/z-llm-agent-proxy.jar --port=9098 --nacos.addr=127.0.0.1:8848
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
| `z-team` | **原宿主**。本仓平移后已从 `z-team/z-llm-agent/pom.xml` 移除 module |
| `z-team-agent-core` | 上游依赖（`CliAgentConfig` / `CliAgentRegistry` / `CliChatExecutor` / DTO），走 Central `1.0.46` |
| `z-agent-proxy` | **不是同一个东西**：那个是 Aider/Cursor → `kernel.agent.Agent` SPI 的纯 jar 库；本仓是 CLI → OpenAI HTTP 的服务 |
| `z-agent` / `z-bot` | 潜在消费方：把本仓当"本机 CLI 算力入口" |

## 发布

- **Central**：`io.github.yuku123:z-llm-agent-proxy`。历史上一直随 z-team 发布（Central 已有 1.0.14 ~ 1.0.46），本仓版本接着走 `1.0.47`，**不与历史断线**。
- **镜像**：`make image` → 组织中心仓库。

---

MIT License · z-opc-foundation
