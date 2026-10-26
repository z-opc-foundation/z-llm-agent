package com.zifang.z.team.cliagent.stream;

/**
 * 单轮流式处理上下文：累计正文与末帧 envelope。
 */
public class CliStreamContext {

    private final String cliType;
    private final String sessionId;
    private final StringBuilder contentAcc = new StringBuilder();
    private Object finalEnvelope;

    public CliStreamContext(String cliType, String sessionId) {
        this.cliType = cliType;
        this.sessionId = sessionId;
    }

    public String getCliType() {
        return cliType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void appendContent(String delta) {
        if (delta != null && !delta.isEmpty()) {
            contentAcc.append(delta);
        }
    }

    public String getAccumulatedContent() {
        return contentAcc.toString();
    }

    public Object getFinalEnvelope() {
        return finalEnvelope;
    }

    public void setFinalEnvelope(Object finalEnvelope) {
        this.finalEnvelope = finalEnvelope;
    }
}
