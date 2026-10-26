package com.zifang.z.team.agent.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.zifang.util.json.JsonUtil;
import com.zifang.z.team.agent.dto.ChatCompletionRequest;
import com.zifang.z.team.agent.dto.ChatCompletionResponse;
import com.zifang.z.team.agent.runtime.CliAgentConfig;
import com.zifang.z.team.agent.runtime.CliAgentRegistry;
import com.zifang.z.team.agent.wrapper.CliChatExecutor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /v1/chat/completions — OpenAI 兼容聊天端点，直连 {@link CliChatExecutor}。
 *
 * <p>多 CLI 路由（三级优先级）：
 * <ol>
 *   <li>X-Agent-Id 头 → cliRegistry.get(agentId) 精确路由</li>
 *   <li>X-CLI-Type 头 → 按 type 匹配注册表</li>
 *   <li>model 字段 → 模糊推断 type（含 "hermes" → hermes，含 "opencode" → opencode，其他 → claude-code）</li>
 * </ol>
 *
 * <p>路由后无匹配 agent → 用默认配置（CliAgentConfig.defaultCommand/defaultArgs）。
 * stream=true 时 SSE 流式输出，stream=false 时聚合返回。
 */
public class ProxyChatHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(ProxyChatHandler.class.getName());
    private static final String DEFAULT_TYPE = "claude-code";

    private final CliAgentRegistry cliRegistry;
    private final CliChatExecutor cliExecutor;
    private final SessionRegistry sessionRegistry;

    public ProxyChatHandler(CliAgentRegistry cliRegistry, CliChatExecutor cliExecutor,
                            SessionRegistry sessionRegistry) {
        this.cliRegistry = cliRegistry;
        this.cliExecutor = cliExecutor;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpUtils.handleCors(exchange)) {
            return;
        }

        String method = exchange.getRequestMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            HttpUtils.sendError(exchange, 405, "Method not allowed: " + method);
            return;
        }

        try {
            String body = HttpUtils.readBody(exchange);
            ChatCompletionRequest request = JsonUtil.fromJson(body, ChatCompletionRequest.class);

            if (request.getMessages() == null || request.getMessages().isEmpty()) {
                HttpUtils.sendError(exchange, 400, "messages is required");
                return;
            }

            // 解析或生成 session ID（支持持续对话）
            String sessionId = exchange.getRequestHeaders().getFirst("X-Session-Id");
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = UUID.randomUUID().toString();
                LOG.info("Generated new session: " + sessionId);
            }
            request.setSessionId(sessionId);

            // 路由 CLI agent 配置
            CliAgentConfig config = resolveConfig(exchange, request);
            LOG.info("Routing to CLI: type=" + config.getType() + " command=" + config.getCommand()
                    + " stream=" + request.isStream() + " session=" + sessionId);

            if (request.isStream()) {
                handleStream(exchange, config, request);
            } else {
                handleSync(exchange, config, request);
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Chat handler error: " + e.getMessage(), e);
            HttpUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * 路由优先级：session 绑定 → X-Agent-Id → X-CLI-Type → model 推断 → 默认。
     *
     * <p>session 绑定（最高优先级）：openSession 时指定了 cliType/agentId，
     * 后续请求只需带 sessionId，proxy 自动路由，无需每次携带 header。
     *
     * <p>header 路由（向后兼容）：未通过 session 绑定时，回退到 header 推断。
     */
    private CliAgentConfig resolveConfig(HttpExchange exchange, ChatCompletionRequest request) {
        String sessionId = request.getSessionId();

        // 1. session 绑定的 agentId 精确路由（最高优先级）
        if (sessionRegistry != null && sessionId != null) {
            String boundAgentId = sessionRegistry.getAgentId(sessionId);
            if (boundAgentId != null) {
                CliAgentConfig config = cliRegistry.get(boundAgentId);
                if (config != null) {
                    LOG.info("Resolved by session-bound agentId: " + boundAgentId);
                    return config;
                }
                LOG.warning("Session-bound agentId not found in registry: " + boundAgentId);
            }
        }

        // 2. session 绑定的 cliType 路由
        if (sessionRegistry != null && sessionId != null) {
            String boundType = sessionRegistry.getCliType(sessionId);
            if (boundType != null) {
                CliAgentConfig config = findByType(boundType);
                if (config != null) {
                    LOG.info("Resolved by session-bound cliType: " + boundType);
                    return config;
                }
                // 注册表无匹配，用默认配置
                LOG.info("Session-bound cliType=" + boundType + " not in registry, using default config");
                return buildDefaultConfig(boundType);
            }
        }

        // 3. X-Agent-Id 精确路由（向后兼容）
        String agentId = exchange.getRequestHeaders().getFirst("X-Agent-Id");
        if (agentId != null && !agentId.isEmpty()) {
            CliAgentConfig config = cliRegistry.get(agentId);
            if (config != null) {
                LOG.info("Resolved by X-Agent-Id: " + agentId);
                return config;
            }
            LOG.warning("X-Agent-Id not found in registry: " + agentId + ", falling through");
        }

        // 4. X-CLI-Type → model 字段推断
        String type = inferType(exchange, request);

        // 5. 在注册表中找匹配 type 的 agent
        CliAgentConfig config = findByType(type);
        if (config != null) {
            LOG.info("Resolved by type from registry: " + type);
            return config;
        }

        // 6. 注册表无匹配 → 默认配置
        LOG.info("No registered agent for type=" + type + ", using default config");
        return buildDefaultConfig(type);
    }

    /**
     * 构建默认配置（注册表无匹配时使用）。
     */
    private CliAgentConfig buildDefaultConfig(String type) {
        return new CliAgentConfig(
                "default-" + type,
                type,
                type,
                CliAgentConfig.defaultCommand(type),
                CliAgentConfig.defaultArgs(type),
                null
        );
    }

    /**
     * 推断 CLI type：X-CLI-Type 头 → model 字段。
     */
    private String inferType(HttpExchange exchange, ChatCompletionRequest request) {
        // X-CLI-Type 头直接指定
        String cliType = exchange.getRequestHeaders().getFirst("X-CLI-Type");
        if (cliType != null && !cliType.isEmpty()) {
            LOG.info("Inferred type from X-CLI-Type: " + cliType);
            return cliType;
        }

        // 从 model 字段推断
        String model = request.getModel();
        if (model != null) {
            String lower = model.toLowerCase();
            if (lower.contains("hermes")) {
                return "hermes";
            }
            if (lower.contains("opencode")) {
                return "opencode";
            }
            if (lower.contains("claude")) {
                return "claude-code";
            }
        }

        return DEFAULT_TYPE;
    }

    /**
     * 在注册表中按 type 查找。
     */
    private CliAgentConfig findByType(String type) {
        for (CliAgentConfig config : cliRegistry.listConfigs()) {
            if (type.equals(config.getType())) {
                return config;
            }
        }
        return null;
    }

    /**
     * SSE 流式输出。
     */
    private void handleStream(HttpExchange exchange, CliAgentConfig config, ChatCompletionRequest request)
            throws IOException {
        // 设置 SSE 响应头
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("X-Session-Id", request.getSessionId());
        exchange.sendResponseHeaders(200, 0); // 0 = chunked

        OutputStream sseOut = exchange.getResponseBody();
        try {
            cliExecutor.executeStream(config, request, sseOut);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Stream execution error: " + e.getMessage(), e);
            sseOut.write(ChatCompletionResponse.sseError("Execution error: " + e.getMessage())
                    .getBytes(StandardCharsets.UTF_8));
            sseOut.flush();
        } finally {
            sseOut.close();
        }
    }

    /**
     * 非流式聚合返回。
     */
    private void handleSync(HttpExchange exchange, CliAgentConfig config, ChatCompletionRequest request)
            throws IOException {
        try {
            String reply = cliExecutor.executeSync(config, request);
            String model = config.getType();
            ChatCompletionResponse response = ChatCompletionResponse.of(model, reply);
            exchange.getResponseHeaders().set("X-Session-Id", request.getSessionId());
            HttpUtils.sendJson(exchange, 200, response.toJson());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Sync execution error: " + e.getMessage(), e);
            HttpUtils.sendError(exchange, 500, "Execution error: " + e.getMessage());
        }
    }
}
