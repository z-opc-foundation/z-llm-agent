package com.zifang.z.team.cliagent.core;

import com.zifang.z.team.cliagent.common.CliAgentChatRequest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析 {@link CliAgentChatRequest} 的 workingDir / workingDirs：
 * 单路径 cwd 为路径本身；多路径要求共享同一父目录，cwd 为父目录并在 systemPrompt 中列出各仓库。
 */
public final class CliWorkingDirResolver {

    private CliWorkingDirResolver() {
    }

    public static final class Resolution {
        private final String effectiveWorkingDir;
        private final String systemPromptForMessage;

        public Resolution(String effectiveWorkingDir, String systemPromptForMessage) {
            this.effectiveWorkingDir = effectiveWorkingDir;
            this.systemPromptForMessage = systemPromptForMessage;
        }

        public String getEffectiveWorkingDir() {
            return effectiveWorkingDir;
        }

        public String getSystemPromptForMessage() {
            return systemPromptForMessage;
        }
    }

    public static Resolution resolve(CliAgentChatRequest request) {
        List<String> paths = collectPaths(request);
        if (paths.isEmpty()) {
            String sys = request != null ? request.getSystemPrompt() : null;
            return new Resolution(null, blankToNull(sys));
        }
        List<String> normalized = normalize(paths);
        String basePrompt = request != null ? request.getSystemPrompt() : null;
        if (normalized.size() == 1) {
            return new Resolution(normalized.get(0), blankToNull(basePrompt));
        }
        String commonParent = requireSameParent(normalized);
        String merged = mergeSystemPrompt(basePrompt, buildMultiRepoBlock(normalized, commonParent));
        return new Resolution(commonParent, merged);
    }

    static List<String> collectPaths(CliAgentChatRequest request) {
        List<String> out = new ArrayList<String>();
        if (request == null) {
            return out;
        }
        if (request.getWorkingDirs() != null) {
            for (String p : request.getWorkingDirs()) {
                if (p != null && !p.trim().isEmpty()) {
                    out.add(p);
                }
            }
        }
        if (out.isEmpty() && request.getWorkingDir() != null && !request.getWorkingDir().trim().isEmpty()) {
            out.add(request.getWorkingDir());
        }
        return out;
    }

    static List<String> normalize(List<String> paths) {
        Set<String> unique = new LinkedHashSet<String>();
        for (String p : paths) {
            if (p == null || p.trim().isEmpty()) {
                throw new IllegalArgumentException("workingDirs 含空路径");
            }
            String n = normalizeOne(p.trim());
            unique.add(n);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("workingDirs 为空");
        }
        return new ArrayList<String>(unique);
    }

    private static String normalizeOne(String path) {
        Path p = Paths.get(path).normalize();
        String s = p.toString();
        if (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    static String requireSameParent(List<String> normalized) {
        Path firstParent = Paths.get(normalized.get(0)).getParent();
        if (firstParent == null) {
            throw new IllegalArgumentException("无法解析路径父目录: " + normalized.get(0));
        }
        String common = normalizeOne(firstParent.toString());
        for (int i = 1; i < normalized.size(); i++) {
            Path parent = Paths.get(normalized.get(i)).getParent();
            if (parent == null) {
                throw new IllegalArgumentException("无法解析路径父目录: " + normalized.get(i));
            }
            String parentStr = normalizeOne(parent.toString());
            if (!common.equals(parentStr)) {
                throw new IllegalArgumentException(
                        "workingDirs 必须共享同一父目录，当前不一致: "
                                + common + " vs " + parentStr);
            }
        }
        return common;
    }

    static String buildMultiRepoBlock(List<String> normalized, String commonParent) {
        StringBuilder sb = new StringBuilder();
        sb.append("【多仓库工作区】CLI 当前工作目录（cwd）已设为共享父目录: ").append(commonParent).append('\n');
        sb.append("本次关注仓库（绝对路径，只读分析，跨仓请用绝对路径读文件）:\n");
        for (String p : normalized) {
            sb.append("- ").append(p).append('\n');
        }
        return sb.toString().trim();
    }

    private static String mergeSystemPrompt(String base, String block) {
        if (block == null || block.isEmpty()) {
            return blankToNull(base);
        }
        if (base == null || base.trim().isEmpty()) {
            return block;
        }
        return base.trim() + "\n\n" + block;
    }

    private static String blankToNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return s;
    }
}
