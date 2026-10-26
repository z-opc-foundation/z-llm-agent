package com.zifang.z.team.agent.wrapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermesClaudeConfigLoaderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        HermesClaudeConfigLoader.clearCache();
    }

    @Test
    void legacyMinimaxOnly_usesDefaultProviderAndDefaults() throws Exception {
        Path config = writeConfig(
                "custom_providers:\n"
                        + "  - name: minimax\n"
                        + "    api_key: mm-key-123\n");

        ClaudeAnthropicConfig cfg = HermesClaudeConfigLoader.loadFrom(config);

        assertTrue(cfg.hasApiKey());
        assertEquals("minimax", cfg.getProviderName());
        assertEquals("mm-key-123", cfg.getApiKey());
        assertEquals(HermesClaudeConfigLoader.MINIMAX_BASE_URL, cfg.getBaseUrl());
        assertEquals(HermesClaudeConfigLoader.MINIMAX_DEFAULT_MODEL, cfg.getModel());
    }

    @Test
    void bailianProvider_usesConfiguredValues() throws Exception {
        Path config = writeConfig(
                "claude_code:\n"
                        + "  provider: bailian\n"
                        + "custom_providers:\n"
                        + "  - name: bailian\n"
                        + "    api_key: sk-test-key\n"
                        + "    base_url: https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic\n"
                        + "    model: qwen3.8-max\n");

        ClaudeAnthropicConfig cfg = HermesClaudeConfigLoader.loadFrom(config);

        assertTrue(cfg.hasApiKey());
        assertEquals("bailian", cfg.getProviderName());
        assertEquals("sk-test-key", cfg.getApiKey());
        assertEquals("https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic", cfg.getBaseUrl());
        assertEquals("qwen3.8-max", cfg.getModel());
    }

    @Test
    void bailianProvider_omittedBaseUrlAndModel_usesPaygDefaults() throws Exception {
        Path config = writeConfig(
                "claude_code:\n"
                        + "  provider: bailian\n"
                        + "custom_providers:\n"
                        + "  - name: bailian\n"
                        + "    api_key: sk-test-key\n");

        ClaudeAnthropicConfig cfg = HermesClaudeConfigLoader.loadFrom(config);

        assertEquals(HermesClaudeConfigLoader.BAILIAN_PAYG_BASE_URL, cfg.getBaseUrl());
        assertEquals(HermesClaudeConfigLoader.BAILIAN_DEFAULT_MODEL, cfg.getModel());
    }

    @Test
    void dashscopeProviderAlias_mapsToBailian() throws Exception {
        Path config = writeConfig(
                "claude_code:\n"
                        + "  provider: dashscope\n"
                        + "custom_providers:\n"
                        + "  - name: dashscope\n"
                        + "    api_key: sk-alias\n");

        ClaudeAnthropicConfig cfg = HermesClaudeConfigLoader.loadFrom(config);

        assertEquals("bailian", cfg.getProviderName());
        assertEquals("sk-alias", cfg.getApiKey());
    }

    @Test
    void missingProvider_returnsEmpty() throws Exception {
        Path config = writeConfig(
                "claude_code:\n"
                        + "  provider: bailian\n"
                        + "custom_providers:\n"
                        + "  - name: minimax\n"
                        + "    api_key: mm-only\n");

        ClaudeAnthropicConfig cfg = HermesClaudeConfigLoader.loadFrom(config);

        assertFalse(cfg.hasApiKey());
    }

    @Test
    void missingApiKey_returnsEmpty() throws Exception {
        Path config = writeConfig(
                "claude_code:\n"
                        + "  provider: bailian\n"
                        + "custom_providers:\n"
                        + "  - name: bailian\n");

        ClaudeAnthropicConfig cfg = HermesClaudeConfigLoader.loadFrom(config);

        assertFalse(cfg.hasApiKey());
    }

    @Test
    void configPathCandidates_includesBenchAdminFallback() {
        List<Path> paths = HermesClaudeConfigLoader.configPathCandidates();
        assertTrue(paths.contains(HermesClaudeConfigLoader.BENCH_HERMES_CONFIG));
    }

    @Test
    void missingConfigFile_returnsEmpty() {
        Path missing = tempDir.resolve("no-such-config.yaml");
        ClaudeAnthropicConfig cfg = HermesClaudeConfigLoader.loadFrom(missing);
        assertFalse(cfg.hasApiKey());
    }

    private Path writeConfig(String yaml) throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.write(config, yaml.getBytes());
        return config;
    }
}
