package com.zifang.z.team.cliagent.api;

import jakarta.servlet.http.HttpServletResponse;

/**
 * SSE 流式响应头：禁用 nginx 缓冲。
 */
public final class CliAgentStreamHeaders {

    private CliAgentStreamHeaders() {
    }

    public static void apply(HttpServletResponse response) {
        if (response == null) {
            return;
        }
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
    }
}
