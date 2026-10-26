package com.zifang.z.team.agent.wrapper;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
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
 * Qoder CLI（qodercli）wrapper：subprocess + stream-json，接口风格与
 * {@link ClaudeCodeWrapper} 同构（qodercli 的 -p/--session-id/--output-format
 * 参数与 claude CLI 一致），但有三点差异：
 *
 * <ul>
 *   <li>认证：qodercli 用自身登录态，模型由 Qoder 账号配置，
 *       无需注入 ANTHROPIC_* 环境变量</li>
 *   <li>默认注入 {@code --include-partial-messages} 走真流式（新版支持，
 *       与 claude 同构的 stream_event 增量；旧版不识别该参数时启动即败，自动去掉
 *       重试一次回退伪流式）</li>
 *   <li>默认注入 {@code --model GLM-5.3-Flash}（轻量快速档；模型名大小写敏感，
 *       须与 {@code --list-models} 显示一致，小写会报 not available 回退 auto）</li>
 *   <li>{@code --permission-mode} 取值为下划线风格（bypass_permissions）</li>
 * </ul>
 */
public class QoderProcessWrapper implements CliAgentWrapper {

    private static final Logger LOG = Logger.getLogger(QoderProcessWrapper.class.getName());

    /** 无人值守：无 TTY 时工具审批会卡住，跳过权限确认（qodercli 用下划线风格取值）。 */
    static final String UNATTENDED_PERMISSION_MODE = "bypass_permissions";

    /**
     * 默认模型：GLM-5.3-Flash（轻量快速档）。不注入则走账号默认 auto（按任务自动
     * 选型，耗时不可控）。模型名大小写敏感：须与 --list-models 显示完全一致，
     * 小写 glm-5.3-flash 会报 not available 并静默回退 auto。
     */
    static final String DEFAULT_MODEL = "GLM-5.3-Flash";

    /**
     * Session 上下文：标记已创建的 session ID，首次请求用 --session-id 创建，
     * 后续用 --resume 恢复（one-shot 进程模型，靠 CLI 自身持久化会话）。
     */
    private static final ConcurrentMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();

    @Override
    public boolean supports(String type) {
        return "qoder".equals(type);
    }

    @Override
    public String streamChat(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut)
            throws IOException {
        return streamChatInternal(config, request, sseOut, true);
    }

    /**
     * stream 主流程。{@code partial} 控制是否注入 {@code --include-partial-messages}：
     * 真流式让首字延迟从"全文生成完"提前到首个增量；旧版 CLI 启动即拒时自动去掉重试。
     */
    private String streamChatInternal(CliAgentConfig config, ChatCompletionRequest request,
                                      OutputStream sseOut, boolean partial) throws IOException {
        String prompt = buildPrompt(request, isResumingSession(request.getSessionId()));
        String completionId = "chatcmpl-cli-" + UUID.randomUUID().toString().replace("-", "");
        long created = System.currentTimeMillis() / 1000L;
        String model = config.getType();

        Process process = spawnProcess(config, prompt, request.getSessionId(), SpawnMode.STREAM, partial);
        StreamEventBridge bridge = createBridge(completionId, model, created, sseOut);
        CliProcessRegistry.register(request.getSessionId(), process);

        StringBuilder stderrBuf = new StringBuilder();
        Thread stderrDrainer = startStderrDrainer(process, stderrBuf);

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
            // 整个流无增量（qodercli 无 --include-partial-messages）时，
            // 由 bridge 用暂存的 assistant 完整块按行伪流式补发（thinking/text/tool 均覆盖）
            bridge.finalFlush();

            boolean exited = process.waitFor(CliProcessTimeout.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                String errMsg = "Qoder CLI process timeout after " + CliProcessTimeout.timeoutSeconds() + "s";
                writeSse(sseOut, ChatCompletionResponse.sseError(errMsg));
                return bridge.buildEnvelope() != null ? bridge.buildEnvelope() : bridge.getAssistantText();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                stderrDrainer.join(2000);
                String errOutput = stderrBuf.length() > 0 ? stderrBuf.toString().trim() : readStderr(process);
                String errMsg = "Qoder CLI exited with code " + exitCode
                        + (errOutput != null && !errOutput.isEmpty() ? ": " + errOutput : "");
                LOG.warning("Qoder CLI error: " + errMsg);
                // 用户主动停止（SIGTERM 143/强杀 137）不算错误：已有部分输出保留，无输出静默收尾
                if (CliProcessRegistry.wasRecentlyCancelled(CliProcessRegistry.pidOf(process))) {
                    LOG.info("Qoder CLI terminated by user cancel (code " + exitCode + "), skip error SSE");
                } else if (partial && bridge.getAssistantText().isEmpty() && isUnknownFlagError(errOutput)) {
                    // 旧版 CLI 不认识 --include-partial-messages：去掉重试一次（伪流式兜底）
                    LOG.warning("qodercli rejected --include-partial-messages, retrying without it");
                    return streamChatInternal(config, request, sseOut, false);
                } else if (bridge.getAssistantText().isEmpty()) {
                    // 可识别的版本不兼容错误（配置 schema/模型名）翻译成人话，原始 stderr 只留日志
                    String userMsg = translateCliError(errOutput);
                    writeSse(sseOut, ChatCompletionResponse.sseError(
                            userMsg != null ? userMsg : errMsg));
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
        String prompt = buildPrompt(request, isResumingSession(request.getSessionId()));
        Process process = spawnProcess(config, prompt, request.getSessionId(), SpawnMode.SYNC_JSON, false);
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
                throw new IOException("Qoder CLI process timeout");
            }

            int exitCode = process.exitValue();
            String content = StreamJsonParser.parseJsonOutput(stdout.toString());
            if (content == null || content.isEmpty()) {
                // result 行提取不到时兜底 assistant 块（qodercli json 输出格式差异）
                content = extractLastAssistantText(stdout.toString());
            }
            if (content != null && !content.isEmpty()) {
                return content;
            }

            if (exitCode != 0) {
                String detail = StreamJsonParser.extractResultError(stdout.toString());
                if (detail == null || detail.isEmpty()) {
                    detail = stderr.toString().trim();
                }
                String errMsg = "Qoder CLI exited with code " + exitCode
                        + (detail != null && !detail.isEmpty() ? ": " + detail : "");
                // 可识别的版本不兼容错误翻译成人话；原始报错写日志备查
                String userMsg = translateCliError(detail);
                if (userMsg != null) {
                    LOG.warning("Qoder CLI error (translated): " + errMsg);
                }
                throw new IOException(userMsg != null ? userMsg : errMsg);
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
     * Spawn qodercli 子进程。
     *
     * <p>qodercli 与 claude CLI 参数同构但细节有差异：
     * <ul>
     *   <li>{@code partial} 为 true 时注入 {@code --include-partial-messages}（stream 模式真流式）</li>
     *   <li>默认注入 {@code --model GLM-5.3-Flash}，已有 --model/-m 不覆盖</li>
     *   <li>{@code --permission-mode} 取值为 {@code bypass_permissions}（下划线）</li>
     *   <li>不注入 ANTHROPIC_* 环境（认证走 qodercli 自身登录态）</li>
     * </ul>
     */
    private Process spawnProcess(CliAgentConfig config, String prompt, String sessionId, SpawnMode mode,
                                 boolean partial) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();

        String resolvedCommand = resolveCommandPath(config.getCommand());
        List<String> command = new ArrayList<String>();
        command.add(resolvedCommand);
        boolean promptViaStdin = CliPromptDelivery.useStdin(prompt);
        command.add("-p");
        if (!promptViaStdin) {
            command.add(prompt);
        }
        command.add("--output-format");
        command.add(mode == SpawnMode.SYNC_JSON ? "json" : "stream-json");
        if (mode == SpawnMode.STREAM && partial) {
            command.add("--include-partial-messages");
        }

        ensureUnattendedPermissionMode(command);
        ensureDefaultModel(command);

        // 注入 session ID（与 claude 相同的 one-shot 进程模型：靠 CLI 持久化会话）
        if (sessionId != null && !sessionId.isEmpty()) {
            String qoderSessionId = toQoderSessionUuid(sessionId);
            boolean known = activeSessions.putIfAbsent(qoderSessionId, Boolean.TRUE) != null;
            if (!known && !qoderSessionExistsOnDisk(qoderSessionId)) {
                command.add("--session-id");
                command.add(qoderSessionId);
                LOG.info("Injected --session-id=" + qoderSessionId + " (new session, local=" + sessionId + ")");
            } else {
                command.add("--resume");
                command.add(qoderSessionId);
                LOG.info("Injected --resume=" + qoderSessionId + " (resuming session, local=" + sessionId + ")");
            }
        }

        pb.command(CliCommandLocator.wrapForLaunch(command));
        CliPromptDelivery.configureProcessInput(pb, promptViaStdin);

        // qodercli 无浏览器环境（session 过期时避免卡在浏览器登录流程）
        Map<String, String> env = pb.environment();
        env.put("NO_BROWSER", "1");

        // 从 Qoder IDE 终端启动桌面端时会继承 SDK 环境变量，qodercli 会误判为
        // SDK 子进程并要求 --input-format/--output-format stream-json（sdk_invalid_args）
        env.remove("QODER_AGENT_SDK_ENTRYPOINT");
        env.remove("QODER_CLI");
        env.remove("QODERCLI_RUNTIME_PACKAGING");

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
        LOG.info("Spawning Qoder CLI: command=" + command
                + (promptViaStdin ? " prompt=stdin" : ""));

        Process process = pb.start();
        if (promptViaStdin) {
            CliPromptDelivery.writePromptToStdin(process, prompt);
        }
        return process;
    }

    /**
     * 本地 session ID（local-xxx）→ qodercli 会话 ID。
     *
     * <p>用 {@link UUID#nameUUIDFromBytes} 做确定性映射（与 ClaudeCodeWrapper
     * 相同策略），agent 重启后同一本地会话仍映射到同一 ID，可继续 resume。
     * 前缀区分命名空间，避免与 claude 会话 UUID 撞车。
     */
    static String toQoderSessionUuid(String localSessionId) {
        return UUID.nameUUIDFromBytes(
                ("qoder-session:" + localSessionId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * qodercli 会话按 UUID 持久化在 ~/.qoder/projects/&lt;workdir&gt;/&lt;uuid&gt;.jsonl
     * （与 claude 的 ~/.claude/projects 结构同构）；agent 重启后 {@link #activeSessions}
     * 内存标记丢失，靠磁盘判断会话是否已创建，避免 {@code --session-id} 撞上
     * "already in use"。
     */
    static boolean qoderSessionExistsOnDisk(String qoderSessionUuid) {
        Path projectsDir = Paths.get(resolveEffectiveHome(), ".qoder", "projects");
        if (!Files.isDirectory(projectsDir)) {
            return false;
        }
        String fileName = qoderSessionUuid + ".jsonl";
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
     * 判定本次请求是否为 resume 已有会话（与 spawnProcess 的 --session-id/--resume
     * 决策同一规则）。resume 时 CLI 会话已含历史，prompt 只需最新一条消息。
     */
    private boolean isResumingSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        String uuid = toQoderSessionUuid(sessionId);
        return activeSessions.containsKey(uuid) || qoderSessionExistsOnDisk(uuid);
    }

    /**
     * 旧版 CLI 不认识 --include-partial-messages 的启动期报错特征
     * （触发去掉该参数重试一次的回退路径）。
     */
    static boolean isUnknownFlagError(String errOutput) {
        if (errOutput == null || errOutput.isEmpty()) {
            return false;
        }
        String s = errOutput.toLowerCase();
        return s.contains("include-partial-messages")
                || s.contains("unknown option")
                || s.contains("unrecognized option")
                || s.contains("unknown argument")
                || s.contains("unexpected argument");
    }

    /**
     * 提取 stream-json 行的 type 字段（非 JSON 或无 type 返回 null）。
     * 用于在增量提取前过滤 system/result 行，避免其 stdout/stderr/output
     * 字段被误提取为 delta。
     */
    static String peekLineType(String line) {
        if (line == null || line.trim().isEmpty() || !line.trim().startsWith("{")) {
            return null;
        }
        try {
            JsonObject json = JsonUtil.parseObject(line);
            return json != null ? json.getString("type") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从一行 stream-json 提取 assistant 完整块文本（message.content[].text）。
     *
     * <p>qodercli 无 --include-partial-messages，stream-json 可能只有 assistant
     * 完整块（无 stream_event 增量），此时它是唯一文本来源。
     */
    static String extractAssistantBlockText(String line) {
        if (line == null || line.trim().isEmpty() || !line.trim().startsWith("{")) {
            return null;
        }
        try {
            JsonObject json = JsonUtil.parseObject(line);
            if (json == null || !"assistant".equals(json.getString("type"))) {
                return null;
            }
            JsonObject message = Jsons.optObject(json, "message");
            if (message == null) {
                return null;
            }
            JsonArray content = Jsons.optArray(message, "content");
            if (content == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.size(); i++) {
                Object item = content.get(i);
                JsonObject block = item instanceof JsonObject ? (JsonObject) item : null;
                if (block != null && "text".equals(block.getString("type"))) {
                    String text = block.getString("text");
                    if (text != null) {
                        sb.append(text);
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从多行输出中提取最后一个 assistant 块文本（sync json 兜底）。 */
    static String extractLastAssistantText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String[] lines = raw.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String text = extractAssistantBlockText(lines[i].trim());
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    /**
     * 无 TTY 时 qodercli 的工具执行会等权限确认。已有 {@code --permission-mode}
     * 或 {@code --dangerously-skip-permissions} 则不重复注入。
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
     * 注入默认模型。已有 {@code --model}/{@code -m} 则不覆盖（尊重用户显式配置）。
     */
    static void ensureDefaultModel(List<String> command) {
        if (command == null) {
            return;
        }
        for (String arg : command) {
            if ("--model".equals(arg) || "-m".equals(arg)) {
                return;
            }
        }
        command.add("--model");
        command.add(DEFAULT_MODEL);
    }

    // ──────────── 错误翻译 ────────────

    /**
     * 将 qoder CLI 启动期配置校验错误翻译成一句可执行的人话提示。
     *
     * <p>旧版 CLI 与两样新东西不兼容（实测 exit 42）：
     * <ul>
     *   <li>新版 Qoder IDE 写入 ~/.qoder/settings.json 的 mcpServers.authType
     *       字段，旧版 strict schema 校验不认识 → "Invalid configuration …
     *       Unrecognized key(s)"，启动即拒</li>
     *   <li>默认注入的 {@code --model GLM-5.3-Flash} 不在旧版模型键列表
     *       → "Invalid model …"</li>
     * </ul>
     *
     * <p>命中特征时返回升级指引（前端整段替换原始 stderr）；未命中返回 null，
     * 调用方维持原始透传，原始报错始终由调用方写入后端日志备查。
     */
    static String translateCliError(String errOutput) {
        if (errOutput == null || errOutput.isEmpty()) {
            return null;
        }
        boolean configError = errOutput.contains("Invalid configuration")
                || errOutput.contains("Unrecognized key(s)");
        boolean modelError = errOutput.contains("Invalid model \"")
                || errOutput.contains("Available model keys");
        if (!configError && !modelError) {
            return null;
        }
        String cause;
        if (configError && modelError) {
            cause = "无法解析新版 Qoder IDE 写出的 ~/.qoder/settings.json，且注入的模型名不被识别";
        } else if (configError) {
            cause = "无法解析新版 Qoder IDE 写出的 ~/.qoder/settings.json";
        } else {
            cause = "注入的模型名不被当前版本识别";
        }
        return "qoder CLI 版本过旧（" + cause + "），"
                + "请执行 npm install -g @qoder-ai/qodercli@latest 升级后重试";
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
     * 增强 PATH 环境变量：qodercli 是 npm 全局工具（nvm node bin / hermes node bin）。
     */
    private void enhancePath(ProcessBuilder pb) {
        String currentPath = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
        String home = System.getProperty("user.home");
        List<String> extraPathList = new ArrayList<String>();
        extraPathList.add(home + "/.local/bin");
        extraPathList.add("/usr/local/bin");
        extraPathList.add("/opt/homebrew/bin");
        extraPathList.add("/opt/homebrew/sbin");
        extraPathList.add(home + "/.hermes/node/bin");
        // nvm 管理的 node bin 目录（qodercli 是 npm 全局工具）
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
            LOG.info("Resolved qoder command '" + command + "' -> " + located);
            return located;
        }
        String home = System.getProperty("user.home");
        List<String> searchPaths = new ArrayList<String>();
        searchPaths.add(home + "/.local/bin");
        searchPaths.add("/usr/local/bin");
        searchPaths.add("/opt/homebrew/bin");
        searchPaths.add("/usr/bin");
        searchPaths.add("/bin");
        searchPaths.add(home + "/.hermes/node/bin");
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
                LOG.info("Resolved qoder command '" + command + "' -> " + file.getAbsolutePath());
                return file.getAbsolutePath();
            }
        }

        LOG.log(Level.WARNING, "Could not resolve qoder command '" + command + "' in common bin directories, using as-is");
        return command;
    }

    /**
     * 将 messages 数组构建为 prompt。
     *
     * <p>{@code resuming} 为 true 时只发最新一条非 assistant 消息（历史已在 CLI
     * 会话内，整段重发会让模型看到重复上下文并拉长首字延迟）；新会话单条 user
     * 消息发原文（不注入 [User] 标签，避免标签永久留在会话历史里）；多条消息
     * 拼接并加角色标记（新会话唯一的上下文来源）。
     */
    private String buildPrompt(ChatCompletionRequest request, boolean resuming) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        List<ChatCompletionRequest.ChatMessage> msgs = new ArrayList<ChatCompletionRequest.ChatMessage>();
        for (ChatCompletionRequest.ChatMessage msg : request.getMessages()) {
            if (msg == null || msg.getContent() == null || msg.getContent().isEmpty()) {
                continue;
            }
            msgs.add(msg);
        }
        if (msgs.isEmpty()) {
            return "";
        }
        if (resuming) {
            for (int i = msgs.size() - 1; i >= 0; i--) {
                ChatCompletionRequest.ChatMessage msg = msgs.get(i);
                String role = msg.getRole();
                if (role == null) {
                    role = "user";
                }
                if (!"assistant".equalsIgnoreCase(role)) {
                    return msg.getContent();
                }
            }
            return "";
        }
        if (msgs.size() == 1) {
            String role = msgs.get(0).getRole();
            if (role == null || "user".equalsIgnoreCase(role)) {
                return msgs.get(0).getContent();
            }
        }
        StringBuilder sb = new StringBuilder();
        for (ChatCompletionRequest.ChatMessage msg : msgs) {
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
        }, "qoder-stderr-drainer");
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
