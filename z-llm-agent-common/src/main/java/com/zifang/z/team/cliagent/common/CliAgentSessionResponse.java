package com.zifang.z.team.cliagent.common;

/**
 * 创建 CLI Agent 会话响应。
 */
public class CliAgentSessionResponse {

    private String sessionId;
    private String cliType;
    private String agentId;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCliType() {
        return cliType;
    }

    public void setCliType(String cliType) {
        this.cliType = cliType;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }
}
