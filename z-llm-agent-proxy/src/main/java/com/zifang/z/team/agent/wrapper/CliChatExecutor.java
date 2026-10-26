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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CLI 聊天执行器：优先委托 {@link CliAgentWrapperRegistry} 里的具名 wrapper
 * （claude-code / qoder / hermes / opencode），未知 type 回退通用 spawn 逻辑。
 * 结构化解析（thinking/tool）由 {@link StreamEventBridge} 统一处理。
 */
public class CliChatExecutor {

    private static final Logger LOG = Logger.getLogger(CliChatExecutor.class.getName());

    private volatile CliAgentWrapperRegistry registry;

    public CliChatExecutor() {
    }

    /**
     * 注入 wrapper 注册表。注入后，executeStream/executeSync 优先委托 registry。
     */
    public void setRegistry(CliAgentWrapperRegistry registry) {
        this.registry = registry;
        LOG.info("CliChatExecutor registry set: " + (registry != null ? "active" : "null"));
    }

    /**
     * 流式执行：优先委托 registry，回退 spawn。
     */
    public String executeStream(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut)
            throws IOException {
        // 优先委托 registry
        if (registry != null) {
            CliAgentWrapper wrapper = registry.get(config.getType());
            if (wrapper != null) {
                LOG.info("Delegating stream to " + wrapper.getClass().getSimpleName()
                        + " for type=" + config.getType());
                return wrapper.streamChat(config, request, sseOut);
            }
        }
        // 回退原 spawn 逻辑
        LOG.info("No wrapper for type=" + config.getType() + ", fallback to spawn");
        return fallbackStream(config, request, sseOut);
    }

    /**
     * 非流式执行：优先委托 registry，回退 spawn。
     */
    public String executeSync(CliAgentConfig config, ChatCompletionRequest request)
            throws IOException {
        if (registry != null) {
            CliAgentWrapper wrapper = registry.get(config.getType());
            if (wrapper != null) {
                LOG.info("Delegating sync to " + wrapper.getClass().getSimpleName()
                        + " for type=" + config.getType());
                return wrapper.syncChat(config, request);
            }
        }
        LOG.info("No wrapper for type=" + config.getType() + ", fallback to spawn");
        return fallbackSync(config, request);
    }

    // ──────────── 回退 spawn 逻辑（兼容未知 type） ────────────

    /**
     * 回退流式：spawn CLI 进程，读 stdout 逐行解析为 SSE。
     */
    private String fallbackStream(CliAgentConfig config, ChatCompletionRequest request, OutputStream sseOut)
            throws IOException {
        String prompt = buildPrompt(request);
        String completionId = "chatcmpl-cli-" + UUID.randomUUID().toString().replace("-", "");
        long created = System.currentTimeMillis() / 1000L;
        String model = config.getType();

        Process process = spawnProcess(config, prompt);
        StreamEventBridge bridge = createBridge(completionId, model, created, sseOut);
        CliProcessRegistry.register(request.getSessionId(), process);

        try {
            InputStream stdout = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                if (!bridge.processLine(line)) {
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
                String errMsg = "CLI process timeout after " + CliProcessTimeout.timeoutSeconds() + "s";
                writeSse(sseOut, ChatCompletionResponse.sseError(errMsg));
                return bridge.buildEnvelope() != null ? bridge.buildEnvelope() : bridge.getAssistantText();
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errOutput = readStderr(process);
                String errMsg = "CLI process exited with code " + exitCode
                        + (errOutput != null && !errOutput.isEmpty() ? ": " + errOutput : "");
                LOG.warning("CLI agent error: " + errMsg);
                // 用户主动停止（SIGTERM 143/强杀 137）不算错误：已有部分输出保留，无输出静默收尾
                if (CliProcessRegistry.wasRecentlyCancelled(CliProcessRegistry.pidOf(process))) {
                    LOG.info("CLI agent terminated by user cancel (code " + exitCode + "), skip error SSE");
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

    /**
     * 回退非流式：spawn CLI 进程，聚合返回。
     */
    private String fallbackSync(CliAgentConfig config, ChatCompletionRequest request)
            throws IOException {
        String prompt = buildPrompt(request);
        Process process = spawnProcess(config, prompt);
        StringBuilder fullReply = new StringBuilder();

        try {
            InputStream stdout = process.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                String delta = StreamJsonParser.extractDelta(line);
                if (delta != null && !delta.isEmpty()) {
                    fullReply.append(delta);
                }
            }

            boolean exited = process.waitFor(CliProcessTimeout.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new IOException("CLI process timeout");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && fullReply.length() == 0) {
                String errOutput = readStderr(process);
                throw new IOException("CLI process exited with code " + exitCode
                        + (errOutput != null && !errOutput.isEmpty() ? ": " + errOutput : ""));
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            process.destroyForcibly();
        }

        return fullReply.toString();
    }

    // ──────────── spawn 工具方法（回退路径用） ────────────

    /**
     * 创建 stream 事件桥（与各 wrapper 同构）：text/thinking 增量写 OpenAI chunk，
     * 工具生命周期写 z_team_event 结构化事件。
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

    /**
     * Spawn CLI 子进程。
     */
    private Process spawnProcess(CliAgentConfig config, String prompt) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();

        String resolvedCommand = resolveCommandPath(config.getCommand());
        boolean promptViaStdin = CliPromptDelivery.useStdin(prompt);
        pb.command().add(resolvedCommand);
        List<String> args = promptViaStdin
                ? CliPromptDelivery.omitPromptPlaceholder(config.getArgs())
                : config.resolveArgs(prompt);
        for (int i = 0; i < args.size(); i++) {
            pb.command().add(args.get(i));
        }

        // 增强 PATH
        String currentPath = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
        String home = System.getProperty("user.home");
        List<String> extraPathList = new ArrayList<String>();
        extraPathList.add(home + "/.local/bin");
        extraPathList.add("/usr/local/bin");
        extraPathList.add("/opt/homebrew/bin");
        extraPathList.add("/opt/homebrew/sbin");
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

        if (config.getWorkingDir() != null && !config.getWorkingDir().isEmpty()) {
            String dir = config.getWorkingDir().replace("~", System.getProperty("user.home"));
            File workDir = new File(dir);
            if (!workDir.exists()) {
                workDir.mkdirs();
            }
            pb.directory(workDir);
        }

        CliPromptDelivery.configureProcessInput(pb, promptViaStdin);
        pb.redirectErrorStream(false);
        LOG.info("Spawning CLI agent (fallback): command=" + resolvedCommand
                + " args=" + args.size() + " type=" + config.getType()
                + (promptViaStdin ? " prompt=stdin" : ""));

        pb.command(CliCommandLocator.wrapForLaunch(pb.command()));
        Process process = pb.start();
        if (promptViaStdin) {
            CliPromptDelivery.writePromptToStdin(process, prompt);
        }
        return process;
    }

    /**
     * 将命令名解析为绝对路径。
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
            LOG.info("Resolved CLI command '" + command + "' -> " + located);
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
                LOG.info("Resolved CLI command '" + command + "' -> " + file.getAbsolutePath());
                return file.getAbsolutePath();
            }
        }

        LOG.warning("Could not resolve CLI command '" + command + "' in common bin directories, using as-is");
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

    /**
     * 读取 stderr 输出。
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
