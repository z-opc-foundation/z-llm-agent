package com.zifang.z.team.agent.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonObject;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * POST /v1/session/open — 创建会话，返回 session ID。
 *
 * <p>请求体（可选）：
 * <pre>{@code
 * {"cliType": "claude-code"}
 * }</pre>
 *
 * <p>响应体：
 * <pre>{@code
 * {"sessionId": "uuid", "cliType": "claude-code"}
 * }</pre>
 *
 * <p>指定 cliType 后，后续 {@code POST /v1/chat/completions} 请求只需带 {@code X-Session-Id} 头，
 * proxy 自动路由到对应 CLI wrapper，无需每次携带 {@code X-CLI-Type} 头。
 *
 * <p>未指定 cliType 时为无类型会话（向后兼容），chat 请求仍可通过 header 路由。
 *
 * <p>各 CLI wrapper 内部自行管理会话上下文，对调用方完全透明。
 */
public class ProxySessionHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(ProxySessionHandler.class.getName());

    private final SessionRegistry sessionRegistry;

    public ProxySessionHandler(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpUtils.handleCors(exchange)) {
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendError(exchange, 405, "Method not allowed: " + exchange.getRequestMethod());
            return;
        }

        // 解析请求体（可选）
        String cliType = null;
        String agentId = null;
        String body = HttpUtils.readBody(exchange);
        if (body != null && !body.isEmpty()) {
            try {
                JsonObject json = JsonUtil.parseObject(body);
                if (json != null) {
                    cliType = json.getString("cliType");
                    agentId = json.getString("agentId");
                }
            } catch (Exception e) {
                LOG.warning("Failed to parse session open body: " + e.getMessage());
            }
        }

        // 创建会话并绑定 CLI 类型
        String sessionId = sessionRegistry.openSession(cliType, agentId);

        JsonObject resp = new JsonObject();
        resp.put("sessionId", sessionId);
        if (cliType != null) {
            resp.put("cliType", cliType);
        }
        HttpUtils.sendJson(exchange, 200, JsonUtil.toJson(resp));
    }
}
