package com.zifang.z.team.agent.proxy;

import com.sun.net.httpserver.HttpServer;
import com.zifang.z.team.agent.runtime.CliAgentRegistry;
import com.zifang.z.team.agent.wrapper.CliChatExecutor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * 精简 JDK HttpServer：注册 proxy 路由。
 *
 * <p>路由表：
 * <ul>
 *   <li>GET  /v1/cli-agents      → {@link ProxyCliListHandler}（返回可用 CLI 列表）</li>
 *   <li>POST /v1/session/open     → {@link ProxySessionHandler}（创建会话，绑定 cliType，返回 sessionId）</li>
 *   <li>POST /v1/chat/completions → {@link ProxyChatHandler}（session 自动路由 / 流式/非流式对话）</li>
 *   <li>GET  /health              → 简单 ok 响应</li>
 *   <li>GET  /                    → 404 兜底</li>
 * </ul>
 */
public class ProxyHttpServer {

    private static final Logger LOG = Logger.getLogger(ProxyHttpServer.class.getName());

    private static HttpServer server;

    /**
     * 启动 HTTP 服务器。
     *
     * @param port            监听端口（<=0 则随机分配）
     * @param cliRegistry     CLI agent 注册表
     * @param cliExecutor     CLI 聊天执行器
     * @param sessionRegistry session 注册表（sessionId → cliType 绑定，持久化）
     * @return 实际监听端口
     */
    public static int start(int port, CliAgentRegistry cliRegistry, CliChatExecutor cliExecutor,
                            SessionRegistry sessionRegistry) {
        int bindPort = port > 0 ? port : 0;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", bindPort), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to bind HTTP server on port " + bindPort, e);
        }

        // ── 路由注册 ──
        server.createContext("/v1/cli-agents", new ProxyCliListHandler(cliRegistry));
        server.createContext("/v1/session/open", new ProxySessionHandler(sessionRegistry));
        server.createContext("/v1/chat/completions", new ProxyChatHandler(cliRegistry, cliExecutor, sessionRegistry));
        server.createContext("/health", exchange -> {
            String resp = "{\"status\":\"ok\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(resp.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        });

        // ── 兜底：404 ──
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                String resp = "{\"service\":\"z-llm-agent-proxy\",\"endpoints\":[\"/v1/cli-agents\",\"/v1/session/open\",\"/v1/chat/completions\",\"/health\"]}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(resp.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
            } else {
                String resp = "{\"error\":\"not found: " + path + "\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(404, resp.getBytes(StandardCharsets.UTF_8).length);
                exchange.getResponseBody().write(resp.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().close();
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        int actualPort = server.getAddress().getPort();
        LOG.info("HTTP server started on http://127.0.0.1:" + actualPort);
        return actualPort;
    }

    /**
     * 停止 HTTP 服务器。
     */
    public static void stop() {
        if (server != null) {
            server.stop(2);
            LOG.info("HTTP server stopped");
        }
    }
}
