package com.zifang.z.team.cliagent.common;

/**
 * CLI Agent 同步对话响应。
 */
public class CliAgentChatResponse {

    private boolean ok;
    private String content;
    private String error;
    private long elapsedMs;
    private String cliType;
    private String sessionId;

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
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
}
