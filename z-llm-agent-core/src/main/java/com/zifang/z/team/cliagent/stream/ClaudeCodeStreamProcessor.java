package com.zifang.z.team.cliagent.stream;

import com.zifang.z.team.cliagent.common.CliStreamEvent;
import com.zifang.z.team.cliagent.common.CliStreamEventTypes;

import java.util.Map;

/**
 * Claude Code stream-json 专用分类：MCP / Skill 工具名归一。
 */
public class ClaudeCodeStreamProcessor extends AbstractProxyCompatStreamProcessor {

    @Override
    public boolean supports(String cliType) {
        return "claude-code".equals(cliType);
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    protected CliStreamEvent classifyTool(Map<String, Object> eventPayload, CliStreamContext ctx) {
        CliStreamEvent base = super.classifyTool(eventPayload, ctx);
        if (base == null) {
            return null;
        }
        String name = base.getName();
        McpToolInfo mcp = parseMcpToolName(name);
        if (mcp != null) {
            CliStreamEvent event = copyToolEvent(base, CliStreamEventTypes.MCP);
            event.setServer(mcp.getServer());
            event.setName(mcp.getTool());
            return event;
        }
        if (isSkillToolName(name)) {
            CliStreamEvent event = copyToolEvent(base, CliStreamEventTypes.SKILL);
            String skill = extractSkillName(base.getInput());
            if (skill != null) {
                event.setSkill(skill);
            }
            return event;
        }
        return base;
    }
}
