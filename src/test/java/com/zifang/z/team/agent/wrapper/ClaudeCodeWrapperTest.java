package com.zifang.z.team.agent.wrapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeCodeWrapperTest {

    @Test
    void ensureUnattendedPermissionMode_appendsWhenMissing() {
        List<String> command = new ArrayList<String>(Arrays.asList(
                "claude", "-p", "hi", "--output-format", "json"));
        ClaudeCodeWrapper.ensureUnattendedPermissionMode(command);
        assertEquals(Arrays.asList(
                "claude", "-p", "hi", "--output-format", "json",
                "--permission-mode", ClaudeCodeWrapper.UNATTENDED_PERMISSION_MODE),
                command);
    }

    @Test
    void ensureUnattendedPermissionMode_skipsWhenPermissionModePresent() {
        List<String> command = new ArrayList<String>(Arrays.asList(
                "claude", "--permission-mode", "acceptEdits", "-p", "hi"));
        ClaudeCodeWrapper.ensureUnattendedPermissionMode(command);
        assertEquals(Arrays.asList(
                "claude", "--permission-mode", "acceptEdits", "-p", "hi"),
                command);
    }

    @Test
    void ensureUnattendedPermissionMode_skipsWhenDangerouslySkipPresent() {
        List<String> command = new ArrayList<String>(Arrays.asList(
                "claude", "--dangerously-skip-permissions", "-p", "hi"));
        ClaudeCodeWrapper.ensureUnattendedPermissionMode(command);
        assertEquals(Arrays.asList(
                "claude", "--dangerously-skip-permissions", "-p", "hi"),
                command);
    }

    @Test
    void ensureRootSandboxEnv_injectsWhenRootAndMissing() {
        if (!ClaudeCodeWrapper.isRunningAsRoot()) {
            return;
        }
        Map<String, String> env = new HashMap<String, String>();
        ClaudeCodeWrapper.ensureRootSandboxEnv(env);
        assertEquals("1", env.get("IS_SANDBOX"));
    }

    @Test
    void ensureRootSandboxEnv_skipsWhenAlreadySet() {
        if (!ClaudeCodeWrapper.isRunningAsRoot()) {
            return;
        }
        Map<String, String> env = new HashMap<String, String>();
        env.put("IS_SANDBOX", "1");
        ClaudeCodeWrapper.ensureRootSandboxEnv(env);
        assertEquals("1", env.get("IS_SANDBOX"));
    }

    @Test
    void expandHomePath_expandsTildePrefix() {
        String expanded = ClaudeCodeWrapper.expandHomePath("~/.z_team/agents/claude-code");
        assertFalse(expanded.startsWith("~/"));
        assertTrue(expanded.endsWith("/.z_team/agents/claude-code"));
    }
}
