package com.zifang.z.team.cliagent.core;

import com.zifang.z.team.cliagent.common.CliAgentChatRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliWorkingDirResolverTest {

    @Test
    void singlePathUsesPathAsCwd() {
        CliAgentChatRequest req = new CliAgentChatRequest();
        req.setWorkingDir("/home/user/repos/zteam-order");
        CliWorkingDirResolver.Resolution r = CliWorkingDirResolver.resolve(req);
        assertEquals("/home/user/repos/zteam-order", r.getEffectiveWorkingDir());
        assertNull(r.getSystemPromptForMessage());
    }

    @Test
    void multiplePathsSameParentUsesParentAsCwd() {
        CliAgentChatRequest req = new CliAgentChatRequest();
        req.setWorkingDirs(Arrays.asList(
                "/home/user/repos/zteam-order",
                "/home/user/repos/zteam-settle"));
        CliWorkingDirResolver.Resolution r = CliWorkingDirResolver.resolve(req);
        assertEquals("/home/user/repos", r.getEffectiveWorkingDir());
        assertTrue(r.getSystemPromptForMessage().contains("zteam-order"));
        assertTrue(r.getSystemPromptForMessage().contains("zteam-settle"));
    }

    @Test
    void differentParentsRejected() {
        CliAgentChatRequest req = new CliAgentChatRequest();
        req.setWorkingDirs(Arrays.asList(
                "/home/user/repos/a",
                "/home/user/repos2/b"));
        assertThrows(IllegalArgumentException.class, () -> CliWorkingDirResolver.resolve(req));
    }

    @Test
    void mergesCustomSystemPromptWithMultiRepoBlock() {
        CliAgentChatRequest req = new CliAgentChatRequest();
        req.setSystemPrompt("你是代码分析师");
        req.setWorkingDirs(Arrays.asList(
                "/home/user/repos/a",
                "/home/user/repos/b"));
        CliWorkingDirResolver.Resolution r = CliWorkingDirResolver.resolve(req);
        assertTrue(r.getSystemPromptForMessage().startsWith("你是代码分析师"));
        assertTrue(r.getSystemPromptForMessage().contains("【多仓库工作区】"));
    }

    @Test
    void workingDirsPreferredOverWorkingDir() {
        CliAgentChatRequest req = new CliAgentChatRequest();
        req.setWorkingDir("/ignored/single");
        req.setWorkingDirs(Arrays.asList("/home/user/repos/x"));
        CliWorkingDirResolver.Resolution r = CliWorkingDirResolver.resolve(req);
        assertEquals("/home/user/repos/x", r.getEffectiveWorkingDir());
    }
}
