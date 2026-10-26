package com.zifang.z.team.cliagent.stream;

import com.zifang.z.team.cliagent.common.CliStreamEvent;
import com.zifang.z.team.cliagent.common.CliStreamEventPhases;
import com.zifang.z.team.cliagent.common.CliStreamEventTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClaudeCodeStreamProcessorTest {

    private final ClaudeCodeStreamProcessor processor = new ClaudeCodeStreamProcessor();

    @Test
    public void textDelta() {
        CliStreamContext ctx = new CliStreamContext("claude-code", "s1");
        String payload = "{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}";
        List<CliStreamEvent> events = processor.processPayload(payload, ctx);
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(CliStreamEventTypes.TEXT, events.get(0).getType());
        Assertions.assertEquals("hello", events.get(0).getDelta());
        Assertions.assertEquals("hello", ctx.getAccumulatedContent());
    }

    @Test
    public void thinkingDelta() {
        CliStreamContext ctx = new CliStreamContext("claude-code", "s1");
        String payload = "{\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}";
        List<CliStreamEvent> events = processor.processPayload(payload, ctx);
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(CliStreamEventTypes.THINKING, events.get(0).getType());
        Assertions.assertEquals("think", events.get(0).getDelta());
    }

    @Test
    public void toolCall() {
        CliStreamContext ctx = new CliStreamContext("claude-code", "s1");
        String payload = "{\"choices\":[{\"delta\":{}}],"
                + "\"z_team_event\":{\"kind\":\"tool_call\",\"id\":\"toolu_1\",\"name\":\"Bash\"}}";
        List<CliStreamEvent> events = processor.processPayload(payload, ctx);
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(CliStreamEventTypes.TOOL, events.get(0).getType());
        Assertions.assertEquals(CliStreamEventPhases.CALL, events.get(0).getPhase());
        Assertions.assertEquals("Bash", events.get(0).getName());
    }

    @Test
    public void mcpToolCall() {
        CliStreamContext ctx = new CliStreamContext("claude-code", "s1");
        String payload = "{\"choices\":[{\"delta\":{}}],"
                + "\"z_team_event\":{\"kind\":\"tool_call\",\"id\":\"toolu_2\",\"name\":\"mcp__github__list\"}}";
        List<CliStreamEvent> events = processor.processPayload(payload, ctx);
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(CliStreamEventTypes.MCP, events.get(0).getType());
        Assertions.assertEquals("github", events.get(0).getServer());
        Assertions.assertEquals("list", events.get(0).getName());
    }

    @Test
    public void skillToolCall() {
        CliStreamContext ctx = new CliStreamContext("claude-code", "s1");
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("skill", "dbhub");
        String payload = "{\"choices\":[{\"delta\":{}}],"
                + "\"z_team_event\":{\"kind\":\"tool_call\",\"id\":\"toolu_3\",\"name\":\"Skill\",\"input\":"
                + "{\"skill\":\"dbhub\"}}}";
        List<CliStreamEvent> events = processor.processPayload(payload, ctx);
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(CliStreamEventTypes.SKILL, events.get(0).getType());
        Assertions.assertEquals("dbhub", events.get(0).getSkill());
    }

    @Test
    public void finalPayloadStoresEnvelope() {
        CliStreamContext ctx = new CliStreamContext("claude-code", "s1");
        String payload = "{\"z_team_final\":{\"totalMs\":100}}";
        List<CliStreamEvent> events = processor.processPayload(payload, ctx);
        Assertions.assertTrue(events.isEmpty());
        Assertions.assertNotNull(ctx.getFinalEnvelope());
    }
}
