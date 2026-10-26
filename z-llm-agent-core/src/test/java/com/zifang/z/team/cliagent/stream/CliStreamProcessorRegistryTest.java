package com.zifang.z.team.cliagent.stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CliStreamProcessorRegistryTest {

    @Test
    public void resolveClaudeCodeUsesDedicatedProcessor() {
        CliStreamProcessorRegistry registry = CliStreamProcessorRegistry.createDefault();
        CliStreamProcessor processor = registry.resolve("claude-code");
        Assertions.assertTrue(processor instanceof ClaudeCodeStreamProcessor);
    }

    @Test
    public void resolveUnknownUsesDefault() {
        CliStreamProcessorRegistry registry = CliStreamProcessorRegistry.createDefault();
        CliStreamProcessor processor = registry.resolve("unknown-cli");
        Assertions.assertTrue(processor instanceof DefaultProxyCompatStreamProcessor);
    }

    @Test
    public void resolveOpencodeUsesDedicatedProcessor() {
        CliStreamProcessorRegistry registry = CliStreamProcessorRegistry.createDefault();
        CliStreamProcessor processor = registry.resolve("opencode");
        Assertions.assertTrue(processor instanceof OpencodeStreamProcessor);
    }
}
