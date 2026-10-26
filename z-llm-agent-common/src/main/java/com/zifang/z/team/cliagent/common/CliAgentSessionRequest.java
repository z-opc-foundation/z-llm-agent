package com.zifang.z.team.cliagent.common;

/**
 * 创建 CLI Agent 会话请求。
 */
public class CliAgentSessionRequest {

    private String cliType;
    private String agentId;

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
