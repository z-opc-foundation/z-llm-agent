package com.zifang.z.team.agent.wrapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QoderProcessWrapper#translateCliError(String)} 验证：识别旧版 CLI
 * 的配置 schema / 模型名校验错误并翻译成一句升级指引，未识别的错误返回
 * null 由调用方维持原始透传。
 */
class QoderProcessWrapperTest {

    /** 2026-09 同事机器（旧版 CLI + 新版 IDE 写出的 settings.json）真实报错样本，exit 42。 */
    private static final String REAL_MIXED_STDERR =
            "Invalid configuration in /Users/kongxuan/.qoder/settings.json: Error in: "
                    + "mcpServers.canva Unrecognized key(s) in object: 'authType' Error in: "
                    + "mcpServers.linear Unrecognized key(s) in object: 'authType' ...and 2 more errors. "
                    + "Invalid model \"GLM-5.3-Flash\". Available model keys: - auto - ultimate - "
                    + "performance - efficient - lite - qmodel_latest - qmodel - dmodel - dfmodel - "
                    + "gm51model - kmodel - mmodel Use /model to see available models.";

    @Test
    void translatesMixedConfigAndModelError() {
        String translated = QoderProcessWrapper.translateCliError(REAL_MIXED_STDERR);
        assertNotNull(translated);
        assertTrue(translated.contains("qoder CLI 版本过旧"));
        assertTrue(translated.contains("npm install -g @qoder-ai/qodercli@latest"));
        // 人话整段替换原始 stderr，不再把 mcpServers 细节甩给用户
        assertFalse(translated.contains("mcpServers"));
        assertFalse(translated.contains("\n"));
    }

    @Test
    void translatesConfigOnlyError() {
        String translated = QoderProcessWrapper.translateCliError(
                "Invalid configuration in /Users/a/.qoder/settings.json: Error in: "
                        + "mcpServers.notion Unrecognized key(s) in object: 'authType'");
        assertNotNull(translated);
        assertTrue(translated.contains("版本过旧"));
        assertTrue(translated.contains("npm install -g @qoder-ai/qodercli@latest"));
    }

    @Test
    void translatesModelOnlyError() {
        String translated = QoderProcessWrapper.translateCliError(
                "Invalid model \"GLM-5.3-Flash\". Available model keys: - auto - lite");
        assertNotNull(translated);
        assertTrue(translated.contains("版本过旧"));
        assertTrue(translated.contains("npm install -g @qoder-ai/qodercli@latest"));
    }

    @Test
    void passesThroughUnrecognizedErrors() {
        assertNull(QoderProcessWrapper.translateCliError(null));
        assertNull(QoderProcessWrapper.translateCliError(""));
        assertNull(QoderProcessWrapper.translateCliError("env: node: No such file or directory"));
        assertNull(QoderProcessWrapper.translateCliError("Not logged in · Please run /login"));
        assertNull(QoderProcessWrapper.translateCliError(
                "Qoder CLI process timeout after 1800s"));
    }
}
