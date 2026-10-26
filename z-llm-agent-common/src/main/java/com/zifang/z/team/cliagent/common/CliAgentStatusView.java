package com.zifang.z.team.cliagent.common;

/**
 * CLI Agent 服务健康状态。
 */
public class CliAgentStatusView {

    private boolean proxyOk;
    private int cliCount;
    private int proxyPort;
    private String defaultCliType;

    public boolean isProxyOk() {
        return proxyOk;
    }

    public void setProxyOk(boolean proxyOk) {
        this.proxyOk = proxyOk;
    }

    public int getCliCount() {
        return cliCount;
    }

    public void setCliCount(int cliCount) {
        this.cliCount = cliCount;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public String getDefaultCliType() {
        return defaultCliType;
    }

    public void setDefaultCliType(String defaultCliType) {
        this.defaultCliType = defaultCliType;
    }
}
