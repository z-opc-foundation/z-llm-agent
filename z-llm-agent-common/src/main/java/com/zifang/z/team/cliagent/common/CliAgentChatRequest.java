package com.zifang.z.team.cliagent.common;

import java.util.List;

/**
 * CLI Agent 同步对话请求。
 */
public class CliAgentChatRequest {

    private String prompt;
    private String cliType;
    private String sessionId;
    private String agentId;
    /** 多仓库绝对路径；与 workingDir 二选一，优先本字段。多路径时 cwd 设为共享父目录。 */
    private List<String> workingDirs;

    private String workingDir;
    private String model;
    private String systemPrompt;
    private Double temperature;
    private Integer maxTokens;

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
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

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public List<String> getWorkingDirs() {
        return workingDirs;
    }

    public void setWorkingDirs(List<String> workingDirs) {
        this.workingDirs = workingDirs;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }
}
