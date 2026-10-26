package com.zifang.z.team.cliagent.client;

/**
 * CLI Agent 客户端工厂。
 *
 * <pre>{@code
 * CliAgentClient client = CliAgentClientFactory.remote("http://127.0.0.1:8092");
 * }</pre>
 */
public final class CliAgentClientFactory {

    private CliAgentClientFactory() {
    }

    /**
     * 创建远端 HTTP 客户端。
     *
     * @param baseUrl cli-agent-starter 的 base URL
     */
    public static CliAgentClient remote(String baseUrl) {
        return new RemoteCliAgentClient(baseUrl);
    }
}
