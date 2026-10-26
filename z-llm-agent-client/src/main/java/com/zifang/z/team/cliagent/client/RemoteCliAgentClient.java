package com.zifang.z.team.cliagent.client;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.define.TypeReference;
import com.zifang.z.team.cliagent.common.CliStreamEvent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 远端 HTTP CLI Agent 客户端实现。
 */
public class RemoteCliAgentClient implements CliAgentClient {

    private final String baseUrl;
    private final int connectTimeoutMs = 5000;
    private final int readTimeoutMs = 600000;

    public RemoteCliAgentClient(String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
    }

    @Override
    public Map<String, Object> status() {
        return httpGet("/api/cli-agent/status");
    }

    @Override
    public Map<String, Object> runtimeConfig() {
        return httpGet("/api/cli-agent/config");
    }

    @Override
    public Map<String, Object> listAgents() {
        return httpGet("/api/cli-agent/agents");
    }

    @Override
    public Map<String, Object> openSession(String cliType, String agentId) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (cliType != null && !cliType.trim().isEmpty()) {
            body.put("cliType", cliType.trim());
        }
        if (agentId != null && !agentId.trim().isEmpty()) {
            body.put("agentId", agentId.trim());
        }
        return httpPost("/api/cli-agent/sessions", JsonUtil.toJson(body));
    }

    @Override
    public Map<String, Object> chat(Map<String, Object> body) {
        return httpPost("/api/cli-agent/chat", JsonUtil.toJson(body == null ? Collections.emptyMap() : body));
    }

    @Override
    public void chatStream(Map<String, Object> body, Consumer<CliStreamEvent> onEvent) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + "/api/cli-agent/chat/stream");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Content-Type", "application/json");
            String jsonBody = JsonUtil.toJson(body == null ? Collections.emptyMap() : body);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP POST /api/cli-agent/chat/stream failed: " + code
                        + " " + readStream(is));
            }

            CliStreamEventParser parser = new CliStreamEventParser();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                parser.feedLine(line, onEvent);
            }
            parser.finish(onEvent);
            reader.close();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HTTP POST /api/cli-agent/chat/stream error", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public Map<String, Object> listCodeRepos(int page, int pageSize, String keyword, String detail) {
        StringBuilder path = new StringBuilder("/api/cli-agent/code-repos?page=");
        path.append(page <= 0 ? 1 : page);
        path.append("&page_size=").append(pageSize <= 0 ? 20 : pageSize);
        if (keyword != null && !keyword.trim().isEmpty()) {
            path.append("&keyword=").append(encodeQuery(keyword.trim()));
        }
        if (detail != null && !detail.trim().isEmpty()) {
            path.append("&detail=").append(encodeQuery(detail.trim()));
        }
        return httpGet(path.toString());
    }

    @Override
    public Map<String, Object> getCodeRepoDetail(String folderName) {
        return httpGet("/api/cli-agent/code-repos/" + encodePath(folderName));
    }

    @Override
    public Map<String, Object> getCodeRepoTask(String taskId) {
        return httpGet("/api/cli-agent/code-repos/tasks/" + encodePath(taskId));
    }

    @Override
    public Map<String, Object> cloneCodeRepo(Map<String, Object> body) {
        return httpPost("/api/cli-agent/code-repos/clone",
                JsonUtil.toJson(body == null ? Collections.emptyMap() : body));
    }

    @Override
    public Map<String, Object> cloneCodeRepoBatch(Map<String, Object> body) {
        return httpPost("/api/cli-agent/code-repos/clone/batch",
                JsonUtil.toJson(body == null ? Collections.emptyMap() : body));
    }

    @Override
    public Map<String, Object> pullCodeRepo(String folderName) {
        return httpPost("/api/cli-agent/code-repos/" + encodePath(folderName) + "/pull", null);
    }

    @Override
    public Map<String, Object> deleteCodeRepo(String folderName) {
        return httpDelete("/api/cli-agent/code-repos/" + encodePath(folderName));
    }

    @Override
    public void close() {
        // no persistent resources
    }

    private Map<String, Object> httpGet(String path) {
        try {
            URL url = new URL(baseUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return unwrapData(readResponse(conn), path);
            }
            throw new RuntimeException("HTTP GET " + path + " failed: " + code);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HTTP GET " + path + " error", e);
        }
    }

    private Map<String, Object> httpPost(String path, String body) {
        try {
            URL url = new URL(baseUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return unwrapData(readResponse(conn), path);
            }
            throw new RuntimeException("HTTP POST " + path + " failed: " + code);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HTTP POST " + path + " error", e);
        }
    }

    private Map<String, Object> httpDelete(String path) {
        try {
            URL url = new URL(baseUrl + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return unwrapData(readResponse(conn), path);
            }
            throw new RuntimeException("HTTP DELETE " + path + " failed: " + code);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HTTP DELETE " + path + " error", e);
        }
    }

    /**
     * cli-agent 进程返回 {@code Result{success,data,message}}，这里取 data。
     */
    private Map<String, Object> unwrapData(String json, String path) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> envelope = JsonUtil.fromJson(json,
                new TypeReference<Map<String, Object>>() {});
        if (envelope == null) {
            return Collections.emptyMap();
        }
        Object success = envelope.get("success");
        if (Boolean.FALSE.equals(success)) {
            Object message = envelope.get("message");
            Object detail = envelope.get("detail");
            String msg = message != null ? String.valueOf(message)
                    : detail != null ? String.valueOf(detail) : "cli-agent 服务返回失败";
            throw new RuntimeException("cli-agent " + path + " 失败: " + msg);
        }
        Object data = envelope.get("data");
        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) data;
            return map;
        }
        return Collections.emptyMap();
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        return readStream(conn.getInputStream());
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString().trim();
    }

    private static String encodePath(String segment) {
        if (segment == null) {
            return "";
        }
        try {
            return URLEncoder.encode(segment.trim(), "UTF-8");
        } catch (Exception e) {
            return segment.trim();
        }
    }

    private static String encodeQuery(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
