package com.zifang.z.team.agent.proxy;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP 工具方法（静态）：读取请求体、发送 JSON、CORS 处理。
 *
 * <p>从 starter 的 HttpServerBootstrap 提取，proxy 和 starter 共用。
 * starter 后续通过薄包装委托调用这些方法。
 */
public final class HttpUtils {

    private static final Logger LOG = Logger.getLogger(HttpUtils.class.getName());

    private HttpUtils() {
    }

    /**
     * 读取请求体。
     */
    public static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = is.read(buffer)) != -1) {
            bos.write(buffer, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 发送 JSON 响应。
     */
    public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    /**
     * 发送错误 JSON。
     */
    public static void sendError(HttpExchange exchange, int status, String message) {
        try {
            String json = "{\"error\":\"" + escapeJson(message) + "\"}";
            sendJson(exchange, status, json);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to send error response", e);
        }
    }

    /**
     * 处理 CORS preflight。
     *
     * @return true 表示已处理 OPTIONS 请求，调用方应直接 return。
     */
    public static boolean handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type, X-Session-Id, X-Agent-Name, X-Agent-Id, X-CLI-Type");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
            return true;
        }
        return false;
    }

    /**
     * JSON 字符串转义。
     */
    public static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
