package com.zifang.z.team.cliagent.stream;

import com.zifang.z.team.cliagent.common.CliStreamEvent;
import com.zifang.z.team.cliagent.common.CliStreamEventTypes;

import java.util.Map;

/**
 * Qoder CLI stream-json 专用分类（与 Claude Code 同构，MCP/Skill 规则复用）。
 */
public class QoderStreamProcessor extends ClaudeCodeStreamProcessor {

    @Override
    public boolean supports(String cliType) {
        return "qoder".equals(cliType);
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    protected CliStreamEvent classifyTool(Map<String, Object> eventPayload, CliStreamContext ctx) {
        return super.classifyTool(eventPayload, ctx);
    }
}
