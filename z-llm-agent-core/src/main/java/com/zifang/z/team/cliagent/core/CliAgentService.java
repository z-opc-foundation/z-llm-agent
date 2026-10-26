package com.zifang.z.team.cliagent.core;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.team.agent.proxy.ProxyClient;
import com.zifang.z.team.cliagent.common.CliAgentChatRequest;
import com.zifang.z.team.cliagent.common.CliAgentChatResponse;
import com.zifang.z.team.cliagent.common.CliAgentInfo;
import com.zifang.z.team.cliagent.common.CliAgentSessionRequest;
import com.zifang.z.team.cliagent.common.CliAgentSessionResponse;
import com.zifang.z.team.cliagent.common.CliAgentStatusView;
import com.zifang.z.team.cliagent.common.CliStreamEvent;
import com.zifang.z.team.cliagent.common.CliStreamEventTypes;
import com.zifang.z.team.cliagent.config.CliAgentProperties;
import com.zifang.z.team.cliagent.stream.CliStreamContext;
import com.zifang.z.team.cliagent.stream.CliStreamProcessor;
import com.zifang.z.team.cliagent.stream.CliStreamProcessorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * CLI Agent 业务门面：经内嵌 agent-proxy 调本地 CLI。
 */
public class CliAgentService {

    private static final Logger log = LoggerFactory.getLogger(CliAgentService.class);

    private final CliAgentProperties properties;
    private final ExecutorService agentExecutor;
    private final CliStreamProcessorRegistry streamProcessorRegistry;

    public CliAgentService(CliAgentProperties properties,
                           ExecutorService agentExecutor,
                           CliStreamProcessorRegistry streamProcessorRegistry) {
        this.properties = properties;
        this.agentExecutor = agentExecutor;
        this.streamProcessorRegistry = streamProcessorRegistry != null
                ? streamProcessorRegistry
                : CliStreamProcessorRegistry.createDefault();
    }

    public CliAgentStatusView status() {
        CliAgentStatusView view = new CliAgentStatusView();
        view.setProxyPort(properties.getProxyPort());
        view.setDefaultCliType(properties.getDefaultCliType());
        ProxyClient client = proxyClient();
        view.setProxyOk(client.isHealthy());
        try {
            view.setCliCount(listAgentsInternal(client).size());
        } catch (Exception e) {
            log.debug("list agents during status failed: {}", e.getMessage());
            view.setCliCount(0);
        }
        return view;
    }

    /**
     * 只读运行时配置（脱敏，供管理端展示）。
     */
    public Map<String, Object> runtimeConfig() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("enabled", properties.isEnabled());
        out.put("embedProxy", properties.isEmbedProxy());
        out.put("proxyPort", properties.getProxyPort());
        out.put("proxyBaseUrl", properties.getProxyBaseUrl());
        out.put("defaultCliType", properties.getDefaultCliType());
        out.put("sessionDir", properties.getSessionDir());
        out.put("runTimeoutSeconds", properties.getRunTimeoutSeconds());
        out.put("httpReadTimeoutSeconds", properties.getHttpReadTimeoutSeconds());

        CliAgentProperties.Worker worker = properties.getWorker();
        Map<String, Object> workerMap = new LinkedHashMap<String, Object>();
        workerMap.put("agentCore", worker.getAgentCore());
        workerMap.put("agentMax", worker.getAgentMax());
        workerMap.put("agentQueue", worker.getAgentQueue());
        out.put("worker", workerMap);

        CliAgentProperties.Repos repos = properties.getRepos();
        Map<String, Object> reposMap = new LinkedHashMap<String, Object>();
        reposMap.put("enabled", repos.isEnabled());
        reposMap.put("root", repos.getRoot());
        reposMap.put("resolvedRoot", repos.resolveRoot());
        out.put("repos", reposMap);
        return out;
    }

    public List<CliAgentInfo> listAgents() throws Exception {
        return listAgentsInternal(proxyClient());
    }

    public CliAgentSessionResponse openSession(CliAgentSessionRequest request) throws Exception {
        String cliType = request != null && request.getCliType() != null
                ? request.getCliType() : properties.getDefaultCliType();
        String agentId = request != null ? request.getAgentId() : null;
        String sessionId = proxyClient().openSession(cliType, agentId);
        CliAgentSessionResponse response = new CliAgentSessionResponse();
        response.setSessionId(sessionId);
        response.setCliType(cliType);
        response.setAgentId(agentId);
        return response;
    }

    public CliAgentChatResponse chat(CliAgentChatRequest request) {
        int timeoutSec = properties.getRunTimeoutSeconds();
        Future<CliAgentChatResponse> future = agentExecutor.submit(new Callable<CliAgentChatResponse>() {
            @Override
            public CliAgentChatResponse call() {
                return doChat(request);
            }
        });
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            CliAgentChatResponse response = new CliAgentChatResponse();
            response.setOk(false);
            response.setError("cli agent chat timeout after " + timeoutSec + "s");
            return response;
        } catch (Exception e) {
            CliAgentChatResponse response = new CliAgentChatResponse();
            response.setOk(false);
            response.setError(e.toString());
            return response;
        }
    }

    /**
     * 结构化流式对话：在当前线程执行，由调用方提交到有界线程池。
     */
    public void chatStream(CliAgentChatRequest request, Consumer<CliStreamEvent> onEvent) throws Exception {
        doChatStream(request, onEvent);
    }

    private CliAgentChatResponse doChat(CliAgentChatRequest request) {
        CliAgentChatResponse response = new CliAgentChatResponse();
        long start = System.currentTimeMillis();
        String cliType = resolveCliType(request);
        response.setCliType(cliType);
        if (request != null && request.getSessionId() != null) {
            response.setSessionId(request.getSessionId());
        }
        try {
            String content = postChatCompletion(request, cliType, false, null, null);
            response.setOk(content != null);
            response.setContent(content);
        } catch (Exception e) {
            response.setOk(false);
            response.setError(e.toString());
        }
        response.setElapsedMs(System.currentTimeMillis() - start);
        return response;
    }

    private void doChatStream(CliAgentChatRequest request, Consumer<CliStreamEvent> onEvent) throws Exception {
        String cliType = resolveCliType(request);
        String sessionId = request != null ? request.getSessionId() : null;
        CliStreamContext ctx = new CliStreamContext(cliType, sessionId);
        postChatCompletion(request, cliType, true, ctx, onEvent);
        emitDone(ctx, onEvent);
    }

    private String resolveCliType(CliAgentChatRequest request) {
        if (request != null && request.getCliType() != null && !request.getCliType().trim().isEmpty()) {
            return request.getCliType().trim();
        }
        return properties.getDefaultCliType();
    }

    private String postChatCompletion(CliAgentChatRequest request,
                                      String cliType,
                                      boolean stream,
                                      CliStreamContext streamCtx,
                                      Consumer<CliStreamEvent> onEvent) throws Exception {
        String base = properties.getProxyBaseUrl();
        int readTimeoutMs = properties.getHttpReadTimeoutSeconds() * 1000;
        // workingDir / workingDirs 统一解析：单路径即路径本身；多路径共享父目录时
        // cwd 设为父目录并把各仓库清单并入 system prompt
        CliWorkingDirResolver.Resolution workingDirResolution = CliWorkingDirResolver.resolve(request);
        String effectiveWorkingDir = workingDirResolution.getEffectiveWorkingDir();
        String systemPrompt = workingDirResolution.getSystemPromptForMessage();
        URL url = new URL(base.replaceAll("/+$", "") + "/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-CLI-Type", cliType);
        if (request != null && request.getSessionId() != null && !request.getSessionId().isEmpty()) {
            conn.setRequestProperty("X-Session-Id", request.getSessionId());
        }
        if (request != null && request.getAgentId() != null && !request.getAgentId().isEmpty()) {
            conn.setRequestProperty("X-Agent-Id", request.getAgentId());
        }
        if (effectiveWorkingDir != null && !effectiveWorkingDir.isEmpty()) {
            conn.setRequestProperty("X-Working-Dir", effectiveWorkingDir);
        }

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (request != null && request.getModel() != null) {
            body.put("model", request.getModel());
        }
        if (request != null && request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request != null && request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        body.put("stream", stream);
        body.put("cli_type", cliType);
        if (effectiveWorkingDir != null && !effectiveWorkingDir.isEmpty()) {
            body.put("working_dir", effectiveWorkingDir);
        }

        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Map<String, String> sys = new LinkedHashMap<String, String>();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
        }
        Map<String, String> user = new LinkedHashMap<String, String>();
        user.put("role", "user");
        user.put("content", request != null && request.getPrompt() != null ? request.getPrompt() : "");
        messages.add(user);
        body.put("messages", messages);

        byte[] bytes = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        if (stream) {
            if (code < 200 || code >= 300) {
                String text = readStream(conn.getErrorStream());
                conn.disconnect();
                emitError(streamCtx, onEvent, "HTTP " + code + " " + text);
                throw new RuntimeException("HTTP " + code + " " + text);
            }
            readSseStream(conn.getInputStream(), streamCtx, onEvent);
            conn.disconnect();
            return null;
        }

        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = readStream(is);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + " " + text);
        }
        // extractContent 下游要 instanceof List / (Map) 强转，嵌套容器必须摊平成 JDK 集合
        Object plain = JsonUtil.toPlainValue(JsonUtil.parse(text));
        if (!(plain instanceof Map)) {
            throw new RuntimeException("Unexpected chat completion response shape: " + text);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) plain;
        return extractContent(resp);
    }

    private void readSseStream(InputStream is,
                               CliStreamContext ctx,
                               Consumer<CliStreamEvent> onEvent) throws Exception {
        CliStreamProcessor processor = streamProcessorRegistry.resolve(ctx.getCliType());
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
            List<CliStreamEvent> events = processor.processPayload(payload, ctx);
            emitEvents(onEvent, events);
        }
        reader.close();
    }

    private void emitDone(CliStreamContext ctx, Consumer<CliStreamEvent> onEvent) {
        CliStreamEvent done = CliStreamEvent.ofType(CliStreamEventTypes.DONE);
        done.setCliType(ctx.getCliType());
        done.setSessionId(ctx.getSessionId());
        done.setOk(Boolean.TRUE);
        done.setContent(ctx.getAccumulatedContent());
        if (ctx.getFinalEnvelope() != null) {
            done.withExtra("finalEnvelope", ctx.getFinalEnvelope());
        }
        emitEvent(onEvent, done);
    }

    private static void emitError(CliStreamContext ctx,
                                    Consumer<CliStreamEvent> onEvent,
                                    String message) {
        CliStreamEvent error = CliStreamEvent.ofType(CliStreamEventTypes.ERROR);
        if (ctx != null) {
            error.setCliType(ctx.getCliType());
            error.setSessionId(ctx.getSessionId());
        }
        error.setMessage(message);
        emitEvent(onEvent, error);
    }

    private static void emitEvents(Consumer<CliStreamEvent> onEvent, List<CliStreamEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (CliStreamEvent event : events) {
            emitEvent(onEvent, event);
        }
    }

    private static void emitEvent(Consumer<CliStreamEvent> onEvent, CliStreamEvent event) {
        if (onEvent != null && event != null) {
            onEvent.accept(event);
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(Map<String, Object> resp) {
        Object choices = resp.get("choices");
        if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) choices).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        Object msg = ((Map<String, Object>) first).get("message");
        if (msg instanceof Map) {
            Object c = ((Map<String, Object>) msg).get("content");
            return c == null ? null : String.valueOf(c);
        }
        return null;
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private List<CliAgentInfo> listAgentsInternal(ProxyClient client) throws Exception {
        List<ProxyClient.CliAgentInfo> raw = client.listCliAgents();
        List<CliAgentInfo> result = new ArrayList<CliAgentInfo>();
        if (raw == null) {
            return result;
        }
        for (ProxyClient.CliAgentInfo item : raw) {
            CliAgentInfo info = new CliAgentInfo();
            info.setType(item.type);
            info.setCommand(item.command);
            info.setPath(item.path);
            info.setAvailable(item.available);
            info.setRegistered(item.registered);
            info.setAgentId(item.agentId);
            result.add(info);
        }
        return result;
    }

    private ProxyClient proxyClient() {
        return new ProxyClient(properties.getProxyBaseUrl());
    }
}
