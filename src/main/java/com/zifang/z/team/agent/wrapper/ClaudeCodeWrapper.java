package com.zifang.z.team.agent.wrapper;

import com.zifang.util.json.model.JsonObject;
import com.zifang.z.team.agent.dto.ChatCompletionRequest;
import com.zifang.z.team.agent.dto.ChatCompletionResponse;
import com.zifang.z.team.agent.runtime.CliAgentConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Claude Code CLI wrapper：subprocess + stream-json + Anthropic 兼容端点（minimax / 百炼 bailian）。
 *
 * <p>端点与模型从 ~/.hermes/config.yaml 加载（{@link HermesClaudeConfigLoader}），
 * env 注入 {@code ANTHROPIC_BASE_URL} + {@code ANTHROPIC_API_KEY}，无需 {@code claude /login}。
 *
 * <p>关键：{@code --include-partial-messages} 产生 {@code stream_event} 事件（非 content_block_delta），
 * 由 {@link StreamJsonParser} 的 stream_event 分支解析。
 */
public class ClaudeCodeWrapper implements CliAgentWrapper {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeWrapper.class.getName());

    /** 无人值守 -p 无 TTY，Write/Bash 会卡在审批；跳过工具权限确认。 */
    static final String UNATTENDED_PERMISSION_MODE = "bypassPermissions";

    /**
     * Session 上下文（内部实现，对外透明）。
     * 标记已创建的 session ID，首次请求用 --session-id 创建，后续用 --resume 恢复。
     */
    private static final ConcurrentMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();

    @Override
    public boolean supports(String type) {
        return "claude-code".equals(type);
    }

    @Override
    public String streamChat(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut)
            throws IOException {
        String prompt = buildPrompt(request);
        String completionId = "chatcmpl-cli-" + UUID.randomUUID().toString().replace("-", "");
        long created = System.currentTimeMillis() / 1000L;
        String model = config.getType();

        Process process = spawnProcess(config, prompt, request.getSessionId(), SpawnMode.STREAM);
        StreamEventBridge bridge = createBridge(completionId, model, created, sseOut);
        CliProcessRegistry.register(request.getSessionId(), process);

        try {
            InputStream stdout = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                if (!bridge.processLine(line)) {
                    // 非 stream-json 行：回退旧提取逻辑，由 bridge 统一推送
                    String delta = StreamJsonParser.extractDelta(line);
                    if (delta != null && !delta.isEmpty()) {
                        bridge.injectTextDelta(delta);
                    }
                }
            }
            bridge.finalFlush();

            boolean exited = process.waitFor(CliProcessTimeout.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                String errMsg = "Claude Code process timeout after " + CliProcessTimeout.timeoutSeconds() + "s";
                writeSse(sseOut, ChatCompletionResponse.sseError(errMsg));
                return bridge.buildEnvelope() != null ? bridge.buildEnvelope() : bridge.getAssistantText();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errOutput = readStderr(process);
                String errMsg = "Claude Code exited with code " + exitCode
                        + (errOutput != null && !errOutput.isEmpty() ? ": " + errOutput : "");
                LOG.warning("Claude Code error: " + errMsg);
                // 用户主动停止（SIGTERM 143/强杀 137）不算错误：已有部分输出保留，无输出静默收尾
                if (CliProcessRegistry.wasRecentlyCancelled(CliProcessRegistry.pidOf(process))) {
                    LOG.info("Claude Code terminated by user cancel (code " + exitCode + "), skip error SSE");
                } else if (bridge.getAssistantText().isEmpty()) {
                    writeSse(sseOut, ChatCompletionResponse.sseError(errMsg));
                }
            }

            // SSE 末帧：envelope 非空时透传（totalMs/usage/stopped），流式侧即时结算免等重放
            String finalEnv = bridge.buildEnvelope();
            if (finalEnv != null) {
                writeSse(sseOut, "data: {\"z_team_final\":"
                        + CliProcessRegistry.finalizeEnvelope(finalEnv, bridge.getAssistantText(),
                                CliProcessRegistry.wasRecentlyCancelled(CliProcessRegistry.pidOf(process)))
                        + "}\n\n");
            }

            writeSse(sseOut, ChatCompletionResponse.sseDone());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeSse(sseOut, ChatCompletionResponse.sseError("Interrupted"));
        } finally {
            CliProcessRegistry.unregister(request.getSessionId(), process);
            process.destroyForcibly();
        }

        String envelope = bridge.buildEnvelope();
        return CliProcessRegistry.finalizeEnvelope(envelope, bridge.getAssistantText(),
                CliProcessRegistry.wasRecentlyCancelled(CliProcessRegistry.pidOf(process)));
    }

    @Override
    public String syncChat(CliAgentConfig config, ChatCompletionRequest request)
            throws IOException {
        String prompt = buildPrompt(request);
        Process process = spawnProcess(config, prompt, request.getSessionId(), SpawnMode.SYNC_JSON);
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        try {
            Thread stderrDrainer = startStderrDrainer(process, stderr);
            InputStream stdoutStream = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stdoutStream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.append(line).append('\n');
            }

            boolean exited = process.waitFor(CliProcessTimeout.timeoutMs(), TimeUnit.MILLISECONDS);
            stderrDrainer.join(2000);
            if (!exited) {
                process.destroyForcibly();
                throw new IOException("Claude Code process timeout");
            }

            int exitCode = process.exitValue();
            String content = StreamJsonParser.parseJsonOutput(stdout.toString());
            if (content != null && !content.isEmpty()) {
                return content;
            }

            if (exitCode != 0) {
                String detail = StreamJsonParser.extractResultError(stdout.toString());
                if (detail == null || detail.isEmpty()) {
                    detail = stderr.toString().trim();
                }
                throw new IOException(buildExitError(exitCode, detail));
            }

            return content == null ? "" : content;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            process.destroyForcibly();
        }
    }

    private enum SpawnMode {
        STREAM,
        SYNC_JSON
    }

    // ──────────── 流式事件桥 ────────────

    /**
     * 创建 stream 事件桥：text/thinking 增量写 OpenAI chunk，
     * 工具生命周期写 z_team_event 结构化事件（前端渲染思考过程与工具调用）。
     */
    private static StreamEventBridge createBridge(String completionId, String model, long created, OutputStream sseOut) {
        return new StreamEventBridge(new StreamEventBridge.Listener() {
            @Override
            public void onTextDelta(String delta) {
                writeSse(sseOut, ChatCompletionResponse.sseChunk(completionId, model, created, delta));
            }

            @Override
            public void onReasoningDelta(String delta) {
                writeSse(sseOut, ChatCompletionResponse.sseReasoningChunk(completionId, model, created, delta));
            }

            @Override
            public void onFtEvent(JsonObject event) {
                writeSse(sseOut, ChatCompletionResponse.sseEvent(completionId, model, created, event));
            }
        });
    }

    private static void writeSse(OutputStream sseOut, String chunk) {
        try {
            sseOut.write(chunk.getBytes(StandardCharsets.UTF_8));
            sseOut.flush();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "SSE write error", e);
        }
    }

    // ──────────── 内部方法 ────────────

    /**
     * Spawn claude code 子进程，注入 Anthropic 兼容端点环境变量（minimax / bailian）。
     *
     * <p>关键增强（相对 config.defaultArgs）：
     * <ul>
     *   <li>注入 {@code --model}（来自 hermes config，如果 config 未指定）</li>
     *   <li>确保 {@code --include-partial-messages}（真流式必须，产生 stream_event）</li>
     *   <li>确保 {@code --permission-mode bypassPermissions}（无 TTY 时 Write/Bash 不卡审批）</li>
     *   <li>注入 {@code ANTHROPIC_BASE_URL} + {@code ANTHROPIC_API_KEY}（无需 /login）</li>
     * </ul>
     */
    private Process spawnProcess(CliAgentConfig config, String prompt, String sessionId, SpawnMode mode)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder();

        String resolvedCommand = resolveCommandPath(config.getCommand());
        List<String> command = new ArrayList<String>();
        command.add(resolvedCommand);

        ClaudeAnthropicConfig anthropicCfg = HermesClaudeConfigLoader.load();
        String modelName = anthropicCfg.hasApiKey() ? anthropicCfg.getModel()
                : HermesClaudeConfigLoader.MINIMAX_DEFAULT_MODEL;

        boolean promptViaStdin = CliPromptDelivery.useStdin(prompt);
        if (mode == SpawnMode.SYNC_JSON) {
            command.add("-p");
            if (!promptViaStdin) {
                command.add(prompt);
            }
            command.add("--output-format");
            command.add("json");
            command.add("--model");
            command.add(modelName);
        } else {
            // 流式：stream-json + include-partial-messages
            List<String> baseArgs = promptViaStdin
                    ? CliPromptDelivery.omitPromptPlaceholder(config.getArgs())
                    : config.resolveArgs(prompt);
            boolean hasModel = false;
            boolean hasIncludePartial = false;
            for (String arg : baseArgs) {
                command.add(arg);
                if ("--model".equals(arg)) {
                    hasModel = true;
                }
                if ("--include-partial-messages".equals(arg)) {
                    hasIncludePartial = true;
                }
            }
            if (!hasModel) {
                command.add("--model");
                command.add(modelName);
            }
            if (!hasIncludePartial) {
                command.add("--include-partial-messages");
            }
        }

        ensureUnattendedPermissionMode(command);

        // 注入 session ID（claude-code one-shot 进程模型：每次 spawn 新进程，靠磁盘持久化会话）
        // 首次用 --session-id 创建，后续用 --resume 从磁盘恢复；claude 对已存在的
        // session-id 会报 "already in use"，agent 重启后内存标记丢失需靠磁盘判断切 --resume
        if (sessionId != null && !sessionId.isEmpty()) {
            String claudeSessionId = toClaudeSessionUuid(sessionId);
            boolean known = activeSessions.putIfAbsent(claudeSessionId, Boolean.TRUE) != null;
            if (!known && !claudeSessionExistsOnDisk(claudeSessionId)) {
                command.add("--session-id");
                command.add(claudeSessionId);
                LOG.info("Injected --session-id=" + claudeSessionId + " (new session, local=" + sessionId + ")");
            } else {
                command.add("--resume");
                command.add(claudeSessionId);
                LOG.info("Injected --resume=" + claudeSessionId + " (resuming session, local=" + sessionId + ")");
            }
        }

        pb.command(CliCommandLocator.wrapForLaunch(command));
        CliPromptDelivery.configureProcessInput(pb, promptViaStdin);

        // 注入 Anthropic 兼容端点环境变量
        Map<String, String> env = pb.environment();
        if (anthropicCfg.hasApiKey()) {
            env.put("ANTHROPIC_BASE_URL", anthropicCfg.getBaseUrl());
            env.put("ANTHROPIC_API_KEY", anthropicCfg.getApiKey());
            env.put("ANTHROPIC_AUTH_TOKEN", anthropicCfg.getApiKey());
            env.put("ANTHROPIC_MODEL", modelName);
            LOG.info("Injected provider=" + anthropicCfg.getProviderName()
                    + " ANTHROPIC_BASE_URL=" + anthropicCfg.getBaseUrl()
                    + " model=" + anthropicCfg.getModel()
                    + " ANTHROPIC_API_KEY=" + anthropicCfg.getApiKey()
                            .substring(0, Math.min(15, anthropicCfg.getApiKey().length())) + "...");
        } else {
            LOG.warning("Anthropic API key not found in ~/.hermes/config.yaml, claude code may fail without /login");
        }

        // root 下 bypassPermissions 会被 Claude 拒绝；容器/CI 场景用 IS_SANDBOX 跳过 root 检查
        ensureRootSandboxEnv(env);

        // 增强 PATH（GUI 应用启动的 JAR 进程 PATH 不含用户 bin 目录）
        enhancePath(pb);

        // 工作目录
        if (config.getWorkingDir() != null && !config.getWorkingDir().isEmpty()) {
            String dir = expandHomePath(config.getWorkingDir());
            File workDir = new File(dir);
            if (!workDir.exists()) {
                workDir.mkdirs();
            }
            pb.directory(workDir);
        }

        pb.redirectErrorStream(false);
        LOG.info("Spawning Claude Code: command=" + command
                + (promptViaStdin ? " prompt=stdin" : ""));

        Process process = pb.start();
        if (promptViaStdin) {
            CliPromptDelivery.writePromptToStdin(process, prompt);
        }
        return process;
    }

    /**
     * 本地 session ID（local-xxx）→ claude CLI 要求的 UUID 会话 ID。
     *
     * <p>claude 的 {@code --session-id}/{@code --resume} 只接受 UUID 格式；用
     * {@link UUID#nameUUIDFromBytes} 做确定性映射，agent 重启后同一本地会话仍映射到
     * 同一 UUID，磁盘会话（~/.claude/projects）可继续 resume。
     */
    static String toClaudeSessionUuid(String localSessionId) {
        return UUID.nameUUIDFromBytes(
                ("claude-code-session:" + localSessionId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * claude 会话按 UUID 持久化在 ~/.claude/projects/&lt;project&gt;/&lt;uuid&gt;.jsonl；
     * agent 重启后 {@link #activeSessions} 内存标记丢失，靠磁盘判断会话是否已创建，
     * 避免 {@code --session-id} 撞上 "already in use"。
     */
    static boolean claudeSessionExistsOnDisk(String claudeSessionUuid) {
        Path projectsDir = Paths.get(resolveEffectiveHome(), ".claude", "projects");
        if (!Files.isDirectory(projectsDir)) {
            return false;
        }
        String fileName = claudeSessionUuid + ".jsonl";
        File[] projects = projectsDir.toFile().listFiles();
        if (projects == null) {
            return false;
        }
        for (File project : projects) {
            if (project.isDirectory() && new File(project, fileName).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 无 TTY 时 Claude Code 的 Write/Bash 会等权限确认。已有
     * {@code --permission-mode} 或 {@code --dangerously-skip-permissions} 则不重复注入。
     */
    static void ensureUnattendedPermissionMode(List<String> command) {
        if (command == null) {
            return;
        }
        for (String arg : command) {
            if ("--permission-mode".equals(arg) || "--dangerously-skip-permissions".equals(arg)) {
                return;
            }
        }
        command.add("--permission-mode");
        command.add(UNATTENDED_PERMISSION_MODE);
    }

    /**
     * Claude Code 在 uid=0 时拒绝 bypassPermissions，除非 {@code IS_SANDBOX=1} 或
     * {@code CLAUDE_CODE_BUBBLEWRAP} 已设置（容器/CI 官方 escape hatch）。
     */
    static void ensureRootSandboxEnv(Map<String, String> env) {
        if (env == null || !isRunningAsRoot()) {
            return;
        }
        if (!"1".equals(env.get("IS_SANDBOX"))
                && (env.get("CLAUDE_CODE_BUBBLEWRAP") == null
                || env.get("CLAUDE_CODE_BUBBLEWRAP").trim().isEmpty())) {
            env.put("IS_SANDBOX", "1");
            LOG.info("Running as root: injected IS_SANDBOX=1 for Claude Code bypassPermissions");
        }
    }

    static boolean isRunningAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    static String resolveEffectiveHome() {
        String homeEnv = System.getenv("HOME");
        if (homeEnv != null && !homeEnv.trim().isEmpty()) {
            return homeEnv.trim();
        }
        return System.getProperty("user.home");
    }

    static String expandHomePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String home = resolveEffectiveHome();
        if (path.startsWith("~/")) {
            return home + path.substring(1);
        }
        if ("~".equals(path)) {
            return home;
        }
        return path.replace("~", home);
    }

    /**
     * 增强 PATH 环境变量，确保子进程能找到 nvm 管理的 node bin 等用户工具。
     */
    private void enhancePath(ProcessBuilder pb) {
        String currentPath = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
        String home = System.getProperty("user.home");
        List<String> extraPathList = new ArrayList<String>();
        extraPathList.add(home + "/.local/bin");
        extraPathList.add("/usr/local/bin");
        extraPathList.add("/opt/homebrew/bin");
        extraPathList.add("/opt/homebrew/sbin");
        // nvm 管理的 node bin 目录（claude 是 npm 全局工具）
        File nvmNodeDir = new File(home + "/.nvm/versions/node");
        if (nvmNodeDir.isDirectory()) {
            File[] versions = nvmNodeDir.listFiles();
            if (versions != null) {
                for (File v : versions) {
                    extraPathList.add(v.getAbsolutePath() + "/bin");
                }
            }
        }
        StringBuilder enhancedPath = new StringBuilder(currentPath);
        for (String extra : extraPathList) {
            if (!currentPath.contains(extra)) {
                enhancedPath.insert(0, extra + ":");
            }
        }
        pb.environment().put("PATH", enhancedPath.toString());
    }

    /**
     * 将命令名解析为绝对路径。macOS GUI 进程 PATH 精简，需在常见 bin 目录查找。
     */
    private String resolveCommandPath(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        if (command.startsWith("/")) {
            return command;
        }

        // Windows：PATH（含 %APPDATA%\npm）里按 .exe/.cmd/.bat 定位裸命令名
        String located = CliCommandLocator.locate(command);
        if (located != null) {
            LOG.info("Resolved claude command '" + command + "' -> " + located);
            return located;
        }
        String home = System.getProperty("user.home");
        List<String> searchPaths = new ArrayList<String>();
        searchPaths.add(home + "/.local/bin");
        searchPaths.add("/usr/local/bin");
        searchPaths.add("/opt/homebrew/bin");
        searchPaths.add("/usr/bin");
        searchPaths.add("/bin");
        File nvmNodeDir = new File(home + "/.nvm/versions/node");
        if (nvmNodeDir.isDirectory()) {
            File[] versions = nvmNodeDir.listFiles();
            if (versions != null) {
                for (File v : versions) {
                    searchPaths.add(v.getAbsolutePath() + "/bin");
                }
            }
        }

        for (String dir : searchPaths) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute()) {
                LOG.info("Resolved claude command '" + command + "' -> " + file.getAbsolutePath());
                return file.getAbsolutePath();
            }
        }

        LOG.warning("Could not resolve claude command '" + command + "' in common bin directories, using as-is");
        return command;
    }

    /**
     * 从 messages 数组构建 prompt 字符串。
     */
    private String buildPrompt(ChatCompletionRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatCompletionRequest.ChatMessage msg : request.getMessages()) {
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            String role = msg.getRole();
            if (role == null) {
                role = "user";
            }
            if ("system".equalsIgnoreCase(role)) {
                sb.append("[System Instructions]\n").append(msg.getContent()).append("\n\n");
            } else if ("assistant".equalsIgnoreCase(role)) {
                sb.append("[Assistant]\n").append(msg.getContent()).append("\n\n");
            } else {
                sb.append("[User]\n").append(msg.getContent()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private static String buildExitError(int exitCode, String detail) {
        if (detail != null && !detail.isEmpty()) {
            return "Claude Code exited with code " + exitCode + ": " + detail;
        }
        return "Claude Code exited with code " + exitCode;
    }

    private static Thread startStderrDrainer(final Process process, final StringBuilder stderr) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }, "claude-stderr-drainer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 读取 stderr 输出（用于错误诊断）。
     */
    private String readStderr(Process process) {
        try {
            InputStream stderr = process.getErrorStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "";
        }
    }
}
