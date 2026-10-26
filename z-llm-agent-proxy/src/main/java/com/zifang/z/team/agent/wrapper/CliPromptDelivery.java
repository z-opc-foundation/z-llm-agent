package com.zifang.z.team.agent.wrapper;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 大 prompt 经 stdin 传给 {@code claude -p}，避免 argv 超过 Linux ARG_MAX（Argument list too long）。
 *
 * <p>Claude Code / qodercli 在 {@code -p} 后省略 prompt 参数时，从 stdin 读取用户输入。
 */
public final class CliPromptDelivery {

    private static final Logger LOG = Logger.getLogger(CliPromptDelivery.class.getName());

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    /**
     * 保守阈值：为 model/session/permission 等 argv 留余量（典型 ARG_MAX 128KiB–2MiB）。
     */
    public static final int MAX_PROMPT_ARG_BYTES = 32_000;

    private CliPromptDelivery() {
    }

    public static boolean useStdin(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return false;
        }
        // Windows：prompt 走 cmd.exe /c 转发时多行/非 ASCII argv 会丢失，且命令行
        // 硬限 32767 字符，统一走 stdin（claude/qodercli 在 -p 无参数时读 stdin）
        if (WINDOWS) {
            return true;
        }
        return prompt.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_ARG_BYTES;
    }

    public static void configureProcessInput(ProcessBuilder pb, boolean viaStdin) {
        if (viaStdin) {
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            return;
        }
        File devNull = new File("/dev/null");
        if (devNull.exists()) {
            pb.redirectInput(ProcessBuilder.Redirect.from(devNull));
        }
    }

    public static void writePromptToStdin(Process process, String prompt) throws IOException {
        if (prompt == null) {
            prompt = "";
        }
        OutputStream os = process.getOutputStream();
        try {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } finally {
            os.close();
        }
    }

    /**
     * 从参数模板中移除独立的 {@code {prompt}} 占位符（保留 {@code -p}，由 stdin 提供正文）。
     */
    public static List<String> omitPromptPlaceholder(List<String> templateArgs) {
        List<String> out = new ArrayList<String>();
        if (templateArgs == null) {
            return out;
        }
        for (int i = 0; i < templateArgs.size(); i++) {
            String arg = templateArgs.get(i);
            if ("{prompt}".equals(arg)) {
                continue;
            }
            if (arg.contains("{prompt}")) {
                out.add(arg.replace("{prompt}", ""));
            } else {
                out.add(arg);
            }
        }
        return out;
    }
}
