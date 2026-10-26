package com.zifang.z.team.agent.wrapper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 从 hermes 配置（~/.hermes/config.yaml）加载 Claude Code 的 Anthropic 兼容端点配置。
 *
 * <p>通过 {@code claude_code.provider} 选择 provider（minimax / bailian / dashscope），
 * 从 {@code custom_providers} 对应段读取 api_key，base_url 与 model 可省略（使用内置默认）。
 */
public final class HermesClaudeConfigLoader {

    private static final Logger LOG = Logger.getLogger(HermesClaudeConfigLoader.class.getName());

    static final String DEFAULT_PROVIDER = "minimax";

    static final String MINIMAX_BASE_URL = "https://api.minimaxi.com/anthropic";
    static final String MINIMAX_DEFAULT_MODEL = "MiniMax-M3";

    static final String BAILIAN_PAYG_BASE_URL = "https://dashscope.aliyuncs.com/apps/anthropic";
    static final String BAILIAN_DEFAULT_MODEL = "qwen3.7-plus";

    /** bench Pod 以 root 运行但 NAS 挂载在 /home/admin/.hermes 时的回退路径 */
    static final Path BENCH_HERMES_CONFIG = Paths.get("/home/admin/.hermes/config.yaml");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private static volatile ClaudeAnthropicConfig cached;

    private HermesClaudeConfigLoader() {
    }

    /**
     * 从默认路径 ~/.hermes/config.yaml 加载（带缓存）。
     */
    public static ClaudeAnthropicConfig load() {
        if (cached != null) {
            return cached;
        }
        synchronized (HermesClaudeConfigLoader.class) {
            if (cached != null) {
                return cached;
            }
            Path configPath = resolveExistingConfigPath();
            if (configPath == null) {
                cached = ClaudeAnthropicConfig.empty();
                return cached;
            }
            cached = loadFrom(configPath);
            return cached;
        }
    }

    /**
     * 按优先级查找 hermes 配置文件（Pod 常以 root 运行，配置挂载在 /home/admin/.hermes）。
     */
    static List<Path> configPathCandidates() {
        List<Path> candidates = new ArrayList<Path>();
        String homeEnv = System.getenv("HOME");
        if (homeEnv != null && !homeEnv.trim().isEmpty()) {
            candidates.add(Paths.get(homeEnv.trim(), ".hermes", "config.yaml"));
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.trim().isEmpty()) {
            Path userHomePath = Paths.get(userHome.trim(), ".hermes", "config.yaml");
            if (!candidates.contains(userHomePath)) {
                candidates.add(userHomePath);
            }
        }
        if (!candidates.contains(BENCH_HERMES_CONFIG)) {
            candidates.add(BENCH_HERMES_CONFIG);
        }
        return Collections.unmodifiableList(candidates);
    }

    private static Path resolveExistingConfigPath() {
        for (Path path : configPathCandidates()) {
            if (Files.exists(path)) {
                LOG.info("Using hermes config: " + path);
                return path;
            }
        }
        LOG.warning("hermes config not found, tried: " + configPathCandidates());
        return null;
    }

    /**
     * 从指定路径加载（不缓存，供单测使用）。
     */
    static ClaudeAnthropicConfig loadFrom(Path configPath) {
        if (configPath == null || !Files.exists(configPath)) {
            return ClaudeAnthropicConfig.empty();
        }
        try {
            RootConfig root = YAML.readValue(configPath.toFile(), RootConfig.class);
            String providerName = resolveProviderName(root);
            ProviderSection provider = findProvider(root, providerName);
            if (provider == null) {
                LOG.warning("Provider not found in custom_providers: " + providerName
                        + " (config=" + configPath + ")");
                return ClaudeAnthropicConfig.empty();
            }
            String apiKey = trimToNull(provider.apiKey);
            if (apiKey == null) {
                LOG.warning("api_key missing for provider " + providerName + " in " + configPath);
                return ClaudeAnthropicConfig.empty();
            }
            String baseUrl = resolveBaseUrl(providerName, provider.baseUrl);
            String model = resolveModel(providerName, provider.model);
            LOG.info("Loaded Claude Anthropic config: provider=" + providerName
                    + " base_url=" + baseUrl + " model=" + model
                    + " api_key=" + apiKey.substring(0, Math.min(15, apiKey.length())) + "...");
            return new ClaudeAnthropicConfig(providerName, apiKey, baseUrl, model);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to load Claude config from " + configPath, e);
            return ClaudeAnthropicConfig.empty();
        }
    }

    /**
     * 清除缓存（供单测使用）。
     */
    static void clearCache() {
        cached = null;
    }

    private static String resolveProviderName(RootConfig root) {
        if (root != null && root.claudeCode != null && root.claudeCode.provider != null) {
            String p = root.claudeCode.provider.trim();
            if (!p.isEmpty()) {
                return normalizeProviderName(p);
            }
        }
        return DEFAULT_PROVIDER;
    }

    static String normalizeProviderName(String name) {
        if (name == null) {
            return DEFAULT_PROVIDER;
        }
        String lower = name.trim().toLowerCase();
        if ("dashscope".equals(lower)) {
            return "bailian";
        }
        return lower;
    }

    private static ProviderSection findProvider(RootConfig root, String providerName) {
        if (root == null || root.customProviders == null) {
            return null;
        }
        for (ProviderSection p : root.customProviders) {
            if (p != null && p.name != null && providerName.equals(normalizeProviderName(p.name))) {
                return p;
            }
        }
        return null;
    }

    static String resolveBaseUrl(String providerName, String configured) {
        String trimmed = trimToNull(configured);
        if (trimmed != null) {
            return toAnthropicCompatibleUrl(trimmed);
        }
        if ("bailian".equals(providerName)) {
            return BAILIAN_PAYG_BASE_URL;
        }
        return MINIMAX_BASE_URL;
    }

    /**
     * custom_providers 里配置的 base_url 是 hermes 用的 OpenAI chat_completions 端点
     * （minimax 为 https://api.minimaxi.com/v1），而 claude CLI 走 Anthropic /v1/messages
     * 协议，需替换为 Anthropic 兼容端点，否则报 404 模型不存在。
     */
    static String toAnthropicCompatibleUrl(String baseUrl) {
        if (baseUrl.startsWith("https://api.minimaxi.com/") && !baseUrl.endsWith("/anthropic")) {
            return MINIMAX_BASE_URL;
        }
        return baseUrl;
    }

    static String resolveModel(String providerName, String configured) {
        String trimmed = trimToNull(configured);
        if (trimmed != null) {
            return trimmed;
        }
        if ("bailian".equals(providerName)) {
            return BAILIAN_DEFAULT_MODEL;
        }
        return MINIMAX_DEFAULT_MODEL;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RootConfig {
        @JsonProperty("claude_code")
        public ClaudeCodeSection claudeCode;
        @JsonProperty("custom_providers")
        public List<ProviderSection> customProviders;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ClaudeCodeSection {
        public String provider;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ProviderSection {
        public String name;
        @JsonProperty("api_key")
        public String apiKey;
        @JsonProperty("base_url")
        public String baseUrl;
        public String model;
    }
}
