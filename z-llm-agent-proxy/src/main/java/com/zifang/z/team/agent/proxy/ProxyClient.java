package com.zifang.z.team.agent.proxy;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Proxy 客户端：封装 openSession + chat 完整调用流程，方便调用方使用。
 *
 * <p>典型用法：
 * <pre>{@code
 * ProxyClient client = new ProxyClient("http://127.0.0.1:9098");
 *
 * // 1. 创建会话（绑定 CLI 类型，后续自动路由）
 * String sessionId = client.openSession("claude-code");
 *
 * // 2. 流式对话（逐 token 回调）
 * client.chatStream(sessionId, "你好，介绍一下自己", delta -> {
 *     System.out.print(delta);
 * });
 *
 * // 3. 非流式对话（聚合返回）
 * String reply = client.chat(sessionId, "我刚才问了什么？");
 * System.out.println(reply);
 * }</pre>
 *
 * <p>调用方只需关注三步：open → chat → chat，无需关心 CLI 类型路由、
 * session header 传递、SSE 解析等细节。
 */
public class ProxyClient {

    private static final Logger LOG = Logger.getLogger(ProxyClient.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10 * 60_000;

    private final String baseUrl;

    /**
     * @param baseUrl proxy 地址，如 {@code http://127.0.0.1:9098}
     */
    public ProxyClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    // ──────────── Session ────────────

    /**
     * 创建会话并绑定 CLI 类型。
     *
     * @param cliType CLI 类型（claude-code / opencode / hermes），可为 null（无类型会话）
     * @return sessionId
     */
    public String openSession(String cliType) throws IOException {
        return openSession(cliType, null);
    }

    /**
     * 创建会话并绑定 CLI 类型和 agent ID。
     *
     * @param cliType CLI 类型，可为 null
     * @param agentId 已注册的 agent ID，可为 null
     * @return sessionId
     */
    public String openSession(String cliType, String agentId) throws IOException {
        JsonObject body = new JsonObject();
        if (cliType != null && !cliType.isEmpty()) {
            body.put("cliType", cliType);
        }
        if (agentId != null && !agentId.isEmpty()) {
            body.put("agentId", agentId);
        }

        String resp = doPost("/v1/session/open", JsonUtil.toJson(body), null);
        JsonObject json = JsonUtil.parseObject(resp);
        String sessionId = json.getString("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IOException("openSession: no sessionId in response: " + resp);
        }
        return sessionId;
    }

    // ──────────── Chat ────────────

    /**
     * 非流式对话：发送消息，聚合完整回复返回。
     *
     * @param sessionId openSession 返回的会话 ID
     * @param message   用户消息
     * @return 完整回复文本
     */
    public String chat(String sessionId, String message) throws IOException {
        String resp = doChat(sessionId, message, false);
        JsonObject json = JsonUtil.parseObject(resp);
        JsonArray choices = json.getJsonArray("choices");
        if (choices != null && !choices.isEmpty()) {
            JsonObject choice = choices.getJsonObject(0);
            JsonObject msg = choice.getJsonObject("message");
            if (msg != null) {
                return msg.getString("content");
            }
        }
        throw new IOException("chat: unexpected response: " + resp);
    }

    /**
     * 流式对话：发送消息，逐 token 回调。
     *
     * @param sessionId openSession 返回的会话 ID
     * @param message   用户消息
     * @param onDelta   每收到一个 token delta 时的回调
     */
    public void chatStream(String sessionId, String message, Consumer<String> onDelta) throws IOException {
        HttpURLConnection conn = openChatConnection(sessionId, message, true);
        try {
            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String payload = line.substring(6).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                String delta = extractDelta(payload);
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 流式对话：发送消息，聚合完整回复返回（内部逐 token 接收，外部看到的是完整文本）。
     *
     * @param sessionId openSession 返回的会话 ID
     * @param message   用户消息
     * @return 完整回复文本
     */
    public String chatStreamCollect(String sessionId, String message) throws IOException {
        StringBuilder sb = new StringBuilder();
        chatStream(sessionId, message, sb::append);
        return sb.toString();
    }

    // ──────────── CLI 列表 ────────────

    /**
     * 查询可用的 CLI 列表。
     *
     * @return CLI 信息列表
     */
    public List<CliAgentInfo> listCliAgents() throws IOException {
        String resp = doGet("/v1/cli-agents");
        JsonObject json = JsonUtil.parseObject(resp);
        JsonArray agents = json.getJsonArray("cli_agents");
        List<CliAgentInfo> result = new ArrayList<>();
        if (agents != null) {
            for (int i = 0; i < agents.size(); i++) {
                JsonObject obj = agents.getJsonObject(i);
                CliAgentInfo info = new CliAgentInfo();
                info.type = obj.getString("type");
                info.command = obj.getString("command");
                info.path = obj.getString("path");
                info.available = obj.getBoolean("available");
                info.registered = obj.getBoolean("registered");
                info.agentId = obj.getString("agent_id");
                result.add(info);
            }
        }
        return result;
    }

    // ──────────── 健康检查 ────────────

    /**
     * 健康检查。
     *
     * @return true 如果 proxy 正常运行
     */
    public boolean isHealthy() {
        try {
            String resp = doGet("/health");
            return resp != null && resp.contains("\"ok\"");
        } catch (IOException e) {
            return false;
        }
    }

    // ──────────── 内部方法 ────────────

    private String doChat(String sessionId, String message, boolean stream) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.put("role", "user");
        msg.put("content", message);
        messages.add(msg);
        body.put("messages", messages);
        body.put("stream", stream);

        return doPost("/v1/chat/completions", JsonUtil.toJson(body), sessionId);
    }

    private HttpURLConnection openChatConnection(String sessionId, String message, boolean stream) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.put("role", "user");
        msg.put("content", message);
        messages.add(msg);
        body.put("messages", messages);
        body.put("stream", stream);

        URL url = new URL(baseUrl + "/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (sessionId != null && !sessionId.isEmpty()) {
            conn.setRequestProperty("X-Session-Id", sessionId);
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        byte[] bodyBytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
            os.flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String errBody = readStream(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("chat returned HTTP " + code
                    + (errBody != null && !errBody.isEmpty() ? ": " + errBody : ""));
        }

        return conn;
    }

    private String doPost(String path, String jsonBody, String sessionId) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (sessionId != null && !sessionId.isEmpty()) {
            conn.setRequestProperty("X-Session-Id", sessionId);
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String errBody = readStream(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("POST " + path + " returned HTTP " + code
                    + (errBody != null && !errBody.isEmpty() ? ": " + errBody : ""));
        }

        String resp = readStream(conn.getInputStream());
        conn.disconnect();
        return resp;
    }

    private String doGet(String path) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);

        int code = conn.getResponseCode();
        if (code != 200) {
            String errBody = readStream(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("GET " + path + " returned HTTP " + code
                    + (errBody != null && !errBody.isEmpty() ? ": " + errBody : ""));
        }

        String resp = readStream(conn.getInputStream());
        conn.disconnect();
        return resp;
    }

    private String extractDelta(String ssePayload) {
        try {
            JsonObject json = JsonUtil.parseObject(ssePayload);
            JsonArray choices = json.getJsonArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JsonObject choice = choices.getJsonObject(0);
                JsonObject delta = choice.getJsonObject("delta");
                if (delta != null) {
                    return delta.getString("content");
                }
            }
        } catch (Exception e) {
            // ignore parse errors
        }
        return null;
    }

    private String readStream(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    // ──────────── DTO ────────────

    /**
     * CLI Agent 信息。
     */
    public static class CliAgentInfo {
        public String type;
        public String command;
        public String path;
        public boolean available;
        public boolean registered;
        public String agentId;

        @Override
        public String toString() {
            return "CliAgentInfo{type='" + type + "', available=" + available
                    + ", registered=" + registered + ", agentId='" + agentId + "'}";
        }
    }
}
