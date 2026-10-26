package com.zifang.z.team.cliagent.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI Agent 结构化流式事件（SSE data 负载）。
 */
public class CliStreamEvent {

    private String type;
    private String cliType;
    private String sessionId;
    private String delta;
    private String phase;
    private String id;
    private String name;
    private String server;
    private String skill;
    private Object input;
    private String content;
    private Boolean ok;
    private String message;
    private Map<String, Object> extra;

    public static CliStreamEvent ofType(String type) {
        CliStreamEvent event = new CliStreamEvent();
        event.setType(type);
        return event;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCliType() {
        return cliType;
    }

    public void setCliType(String cliType) {
        this.cliType = cliType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getDelta() {
        return delta;
    }

    public void setDelta(String delta) {
        this.delta = delta;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Boolean getOk() {
        return ok;
    }

    public void setOk(Boolean ok) {
        this.ok = ok;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    public CliStreamEvent withExtra(String key, Object value) {
        if (key == null || value == null) {
            return this;
        }
        if (extra == null) {
            extra = new LinkedHashMap<String, Object>();
        }
        extra.put(key, value);
        return this;
    }
}
