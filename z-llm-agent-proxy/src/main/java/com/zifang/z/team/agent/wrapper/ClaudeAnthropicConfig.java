package com.zifang.z.team.agent.wrapper;

/**
 * Claude Code 子进程所需的 Anthropic 兼容端点配置（从 ~/.hermes/config.yaml 加载）。
 */
public final class ClaudeAnthropicConfig {

    private static final ClaudeAnthropicConfig EMPTY = new ClaudeAnthropicConfig(null, null, null, null);

    private final String providerName;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public ClaudeAnthropicConfig(String providerName, String apiKey, String baseUrl, String model) {
        this.providerName = providerName;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public static ClaudeAnthropicConfig empty() {
        return EMPTY;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    public String getProviderName() {
        return providerName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }
}
