package com.zifang.z.team.agent.proxy;


import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;
import com.zifang.z.team.agent.runtime.CliAgentConfig;
import com.zifang.z.team.agent.runtime.CliAgentRegistry;

import java.io.IOException;
import java.util.List;

/**
 * GET /v1/cli-agents — 返回本地已发现的 CLI 列表。
 *
 * <p>实时扫描 PATH 发现 claude/hermes/opencode，合并注册表中的已注册 agent 信息，
 * 返回每个 CLI 的 type、command、path、available、registered、agent_id。
 */
public class ProxyCliListHandler implements HttpHandler {

    private final CliAgentRegistry cliRegistry;

    public ProxyCliListHandler(CliAgentRegistry cliRegistry) {
        this.cliRegistry = cliRegistry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (HttpUtils.handleCors(exchange)) {
            return;
        }

        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method)) {
            HttpUtils.sendError(exchange, 405, "Method not allowed: " + method);
            return;
        }

        // 实时扫描本地 CLI
        List<CliDiscoveryService.DiscoveredCli> discovered = CliDiscoveryService.discover();

        // 构建 JSON 响应
        JsonArray agents = new JsonArray();
        for (CliDiscoveryService.DiscoveredCli cli : discovered) {
            JsonObject obj = new JsonObject();
            obj.put("type", cli.type);
            obj.put("command", cli.command);
            obj.put("path", cli.resolvedPath);
            obj.put("available", cli.available);

            // 查注册表中是否有对应的 agent
            CliAgentConfig registered = findByType(cli.type);
            if (registered != null) {
                obj.put("registered", true);
                obj.put("agent_id", registered.getId());
            } else {
                obj.put("registered", false);
                obj.put("agent_id", (String) null);
            }

            agents.add(obj);
        }

        JsonObject result = new JsonObject();
        result.put("cli_agents", agents);

        HttpUtils.sendJson(exchange, 200, JsonUtil.toJson(result));
    }

    /**
     * 在注册表中按 type 查找已注册的 CliAgentConfig。
     */
    private CliAgentConfig findByType(String type) {
        for (CliAgentConfig config : cliRegistry.listConfigs()) {
            if (type.equals(config.getType())) {
                return config;
            }
        }
        return null;
    }
}
