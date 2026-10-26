package com.zifang.z.team.agent.wrapper;

/**
 * CLI 子进程 waitFor 超时（毫秒），供 {@link ClaudeCodeWrapper}、{@link CliChatExecutor} 等共用。
 * <p>
 * 默认 600s；bench 内嵌 proxy 启动时由 {@code EmbeddedProxyBootstrap} 注入
 * {@code bench.agent.run-timeout-seconds}；standalone 可通过 env
 * {@code FT_AGENT_CLI_TIMEOUT_SECONDS} 或 system property {@code ft.agent.cli-timeout-seconds} 覆盖。
 */
public final class CliProcessTimeout {

    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static volatile long timeoutMs = DEFAULT_TIMEOUT_SECONDS * 1000L;

    static {
        initFromEnvironment();
    }

    private CliProcessTimeout() {
    }

    public static long timeoutMs() {
        return timeoutMs;
    }

    public static int timeoutSeconds() {
        return (int) Math.max(1L, timeoutMs / 1000L);
    }

    public static void setTimeoutSeconds(int seconds) {
        if (seconds <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_SECONDS * 1000L;
            return;
        }
        timeoutMs = seconds * 1000L;
    }

    private static void initFromEnvironment() {
        String raw = System.getProperty("ft.agent.cli-timeout-seconds");
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getenv("FT_AGENT_CLI_TIMEOUT_SECONDS");
        }
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        try {
            setTimeoutSeconds(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            // keep default
        }
    }
}
