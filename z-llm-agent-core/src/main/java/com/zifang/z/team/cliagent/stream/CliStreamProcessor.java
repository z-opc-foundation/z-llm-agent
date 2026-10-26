package com.zifang.z.team.cliagent.stream;

import com.zifang.z.team.cliagent.common.CliStreamEvent;

import java.util.List;

/**
 * 多态 SSE payload 处理器：将 agent-proxy 原始 chunk 转为统一 {@link CliStreamEvent}。
 */
public interface CliStreamProcessor {

    boolean supports(String cliType);

    /**
     * 越小越优先；专用实现应小于默认兜底。
     */
    int order();

    List<CliStreamEvent> processPayload(String rawJson, CliStreamContext ctx);
}
