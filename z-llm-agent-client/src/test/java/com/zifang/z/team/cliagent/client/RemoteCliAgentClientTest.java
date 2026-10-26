package com.zifang.z.team.cliagent.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * RemoteCliAgentClient 解包逻辑冒烟（不依赖真实服务）。
 */
public class RemoteCliAgentClientTest {

    @Test
    public void factoryCreatesClient() {
        CliAgentClient client = CliAgentClientFactory.remote("http://127.0.0.1:8092");
        Assertions.assertNotNull(client);
        client.close();
    }

    @Test
    public void remoteClientConstructsWithTrailingSlash() {
        RemoteCliAgentClient client = new RemoteCliAgentClient("http://127.0.0.1:8092/");
        Assertions.assertNotNull(client);
        client.close();
    }
}
