package com.zifang.z.team.cliagent.stream;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.team.cliagent.common.CliStreamEvent;
import com.zifang.z.team.cliagent.common.CliStreamEventPhases;
import com.zifang.z.team.cliagent.common.CliStreamEventTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 agent-proxy OpenAI 兼容 SSE payload：content / reasoning_content / z_team_event / z_team_final。
 */
public abstract class AbstractProxyCompatStreamProcessor implements CliStreamProcessor {

    @Override
    public List<CliStreamEvent> processPayload(String rawJson, CliStreamContext ctx) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            // processRoot 下游要 instanceof List / instanceof Map 判定，嵌套容器必须摊平成 JDK 集合
            Object plain = JsonUtil.toPlainValue(JsonUtil.parse(rawJson));
            if (!(plain instanceof Map)) {
                return Collections.emptyList();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) plain;
            return processRoot(root, ctx);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    protected List<CliStreamEvent> processRoot(Map<String, Object> root, CliStreamContext ctx) {
        List<CliStreamEvent> out = new ArrayList<CliStreamEvent>();

        Object error = root.get("error");
        if (error != null) {
            out.add(buildError(ctx, String.valueOf(error)));
            return out;
        }

        Object finalPayload = root.get("z_team_final");
        if (finalPayload != null) {
            ctx.setFinalEnvelope(finalPayload);
            return out;
        }

        Object eventPayload = root.get("z_team_event");
        if (eventPayload instanceof Map) {
            @SuppressWarnings("unchecked")
            CliStreamEvent classified = classifyTool((Map<String, Object>) eventPayload, ctx);
            if (classified != null) {
                out.add(classified);
            }
            return out;
        }

        Object choices = root.get("choices");
        if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) {
            return out;
        }
        Object first = ((List<?>) choices).get(0);
        if (!(first instanceof Map)) {
            return out;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> choice = (Map<String, Object>) first;
        Object deltaObj = choice.get("delta");
        if (!(deltaObj instanceof Map)) {
            return out;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) deltaObj;

        Object content = delta.get("content");
        if (content != null) {
            String text = String.valueOf(content);
            if (!text.isEmpty()) {
                ctx.appendContent(text);
                out.add(buildDeltaEvent(CliStreamEventTypes.TEXT, ctx, text));
            }
        }

        Object reasoning = delta.get("reasoning_content");
        if (reasoning != null) {
            String text = String.valueOf(reasoning);
            if (!text.isEmpty()) {
                out.add(buildDeltaEvent(CliStreamEventTypes.THINKING, ctx, text));
            }
        }

        return out;
    }

    /**
     * 将 z_team_event 分类为 tool / mcp / skill 等；子类可覆盖。
     */
    protected CliStreamEvent classifyTool(Map<String, Object> eventPayload, CliStreamContext ctx) {
        String kind = eventPayload.get("kind") != null ? String.valueOf(eventPayload.get("kind")) : null;
        String phase = mapEventKindToPhase(kind);
        if (phase == null) {
            return null;
        }

        CliStreamEvent event = CliStreamEvent.ofType(CliStreamEventTypes.TOOL);
        fillCommon(event, ctx);
        event.setPhase(phase);
        event.setId(stringOrNull(eventPayload.get("id")));
        event.setName(stringOrNull(eventPayload.get("name")));
        if (eventPayload.get("input") != null) {
            event.setInput(eventPayload.get("input"));
        }
        if (eventPayload.get("content") != null) {
            event.setContent(String.valueOf(eventPayload.get("content")));
        }
        return event;
    }

    protected static String mapEventKindToPhase(String kind) {
        if ("tool_call".equals(kind)) {
            return CliStreamEventPhases.CALL;
        }
        if ("tool_end".equals(kind)) {
            return CliStreamEventPhases.END;
        }
        if ("tool_result".equals(kind)) {
            return CliStreamEventPhases.RESULT;
        }
        return null;
    }

    protected CliStreamEvent buildDeltaEvent(String type, CliStreamContext ctx, String delta) {
        CliStreamEvent event = CliStreamEvent.ofType(type);
        fillCommon(event, ctx);
        event.setDelta(delta);
        return event;
    }

    protected CliStreamEvent buildError(CliStreamContext ctx, String message) {
        CliStreamEvent event = CliStreamEvent.ofType(CliStreamEventTypes.ERROR);
        fillCommon(event, ctx);
        event.setMessage(message);
        return event;
    }

    protected void fillCommon(CliStreamEvent event, CliStreamContext ctx) {
        event.setCliType(ctx.getCliType());
        event.setSessionId(ctx.getSessionId());
    }

    protected static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * MCP 工具名约定：{@code mcp__{server}__{tool}}。
     */
    protected static McpToolInfo parseMcpToolName(String name) {
        if (name == null || !name.startsWith("mcp__")) {
            return null;
        }
        String rest = name.substring("mcp__".length());
        int idx = rest.indexOf("__");
        if (idx <= 0 || idx >= rest.length() - 2) {
            return null;
        }
        McpToolInfo info = new McpToolInfo();
        info.server = rest.substring(0, idx);
        info.tool = rest.substring(idx + 2);
        return info;
    }

    protected static boolean isSkillToolName(String name) {
        if (name == null) {
            return false;
        }
        return "Skill".equals(name) || "skill".equalsIgnoreCase(name);
    }

    protected static String extractSkillName(Object input) {
        if (input instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) input;
            Object skill = firstNonNull(map.get("skill"), map.get("name"), map.get("skillName"));
            if (skill != null) {
                return String.valueOf(skill);
            }
        }
        return null;
    }

    protected static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    protected CliStreamEvent copyToolEvent(CliStreamEvent source, String type) {
        CliStreamEvent event = CliStreamEvent.ofType(type);
        event.setCliType(source.getCliType());
        event.setSessionId(source.getSessionId());
        event.setPhase(source.getPhase());
        event.setId(source.getId());
        event.setName(source.getName());
        event.setInput(source.getInput());
        event.setContent(source.getContent());
        if (source.getExtra() != null) {
            event.setExtra(new LinkedHashMap<String, Object>(source.getExtra()));
        }
        return event;
    }

    protected static final class McpToolInfo {
        private String server;
        private String tool;

        public String getServer() {
            return server;
        }

        public String getTool() {
            return tool;
        }
    }
}
