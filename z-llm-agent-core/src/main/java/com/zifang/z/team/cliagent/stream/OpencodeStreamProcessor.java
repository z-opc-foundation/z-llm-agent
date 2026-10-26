package com.zifang.z.team.cliagent.stream;

import com.zifang.z.team.cliagent.common.CliStreamEvent;
import com.zifang.z.team.cliagent.common.CliStreamEventTypes;

import java.util.Map;

/**
 * OpenCode 专用处理器。
 *
 * <p>当前 agent-proxy wrapper 仅产出 text chunk；tool/MCP 待 wrapper 补 {@code z_team_event} 后，
 * 本类 {@link #classifyTool} 可复用 Claude 规则，无需改对外 API。
 */
public class OpencodeStreamProcessor extends ClaudeCodeStreamProcessor {

    @Override
    public boolean supports(String cliType) {
        return "opencode".equals(cliType);
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    protected CliStreamEvent classifyTool(Map<String, Object> eventPayload, CliStreamContext ctx) {
        return super.classifyTool(eventPayload, ctx);
    }
}
