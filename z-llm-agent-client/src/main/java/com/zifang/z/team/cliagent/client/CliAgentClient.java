package com.zifang.z.team.cliagent.client;

import com.zifang.z.team.cliagent.common.CliStreamEvent;

import java.util.Map;
import java.util.function.Consumer;

/**
 * CLI Agent 服务 HTTP 客户端。
 * <p>
 * 返回值为 cli-agent 进程 {@code Result.data} 已解包的 Map 或对象字段。
 */
public interface CliAgentClient {

    Map<String, Object> status();

    Map<String, Object> runtimeConfig();

    Map<String, Object> listAgents();

    Map<String, Object> openSession(String cliType, String agentId);

    Map<String, Object> chat(Map<String, Object> body);

    void chatStream(Map<String, Object> body, Consumer<CliStreamEvent> onEvent);

    Map<String, Object> listCodeRepos(int page, int pageSize, String keyword, String detail);

    Map<String, Object> getCodeRepoDetail(String folderName);

    Map<String, Object> getCodeRepoTask(String taskId);

    Map<String, Object> cloneCodeRepo(Map<String, Object> body);

    Map<String, Object> cloneCodeRepoBatch(Map<String, Object> body);

    Map<String, Object> pullCodeRepo(String folderName);

    Map<String, Object> deleteCodeRepo(String folderName);

    void close();
}
