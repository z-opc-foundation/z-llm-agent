package com.zifang.z.team.cliagent.api;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.team.cliagent.common.CliAgentResult;
import com.zifang.z.team.cliagent.common.CliAgentChatRequest;
import com.zifang.z.team.cliagent.common.CliAgentChatResponse;
import com.zifang.z.team.cliagent.common.CliAgentInfo;
import com.zifang.z.team.cliagent.common.CliAgentSessionRequest;
import com.zifang.z.team.cliagent.common.CliAgentSessionResponse;
import com.zifang.z.team.cliagent.common.CliAgentStatusView;
import com.zifang.z.team.cliagent.common.CliStreamEvent;
import com.zifang.z.team.cliagent.common.CliStreamEventTypes;
import com.zifang.z.team.cliagent.core.CliAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * CLI Agent REST API。
 */
@RestController
@RequestMapping("/api/cli-agent")
public class CliAgentController {

    private static final Logger log = LoggerFactory.getLogger(CliAgentController.class);

    private final CliAgentService cliAgentService;
    private final ThreadPoolExecutor cliAgentExecutor;

    public CliAgentController(CliAgentService cliAgentService,
                              @Qualifier("cliAgentExecutor") ThreadPoolExecutor cliAgentExecutor) {
        this.cliAgentService = cliAgentService;
        this.cliAgentExecutor = cliAgentExecutor;
    }

    @GetMapping("/status")
    public CliAgentResult<CliAgentStatusView> status() {
        return CliAgentResult.success(cliAgentService.status());
    }

    @GetMapping("/config")
    public CliAgentResult<Map<String, Object>> runtimeConfig() {
        return CliAgentResult.success(cliAgentService.runtimeConfig());
    }

    @GetMapping("/agents")
    public CliAgentResult<Map<String, Object>> agents() {
        try {
            List<CliAgentInfo> agents = cliAgentService.listAgents();
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("agents", agents);
            body.put("count", agents.size());
            return CliAgentResult.success(body);
        } catch (Exception e) {
            log.warn("[CliAgent] list agents failed: {}", e.getMessage());
            return CliAgentResult.error(CliAgentResult.FAIL, e.getMessage());
        }
    }

    @PostMapping("/sessions")
    public CliAgentResult<CliAgentSessionResponse> openSession(@RequestBody(required = false) CliAgentSessionRequest request) {
        try {
            return CliAgentResult.success(cliAgentService.openSession(request));
        } catch (Exception e) {
            log.warn("[CliAgent] open session failed: {}", e.getMessage());
            return CliAgentResult.error(CliAgentResult.FAIL, e.getMessage());
        }
    }

    @PostMapping("/chat")
    public CliAgentResult<CliAgentChatResponse> chat(@RequestBody(required = false) CliAgentChatRequest request) {
        try {
            if (request == null || request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
                return CliAgentResult.error(CliAgentResult.PARAM_INVALID, "prompt is required");
            }
            return CliAgentResult.success(cliAgentService.chat(request));
        } catch (RejectedExecutionException e) {
            return CliAgentResult.error(CliAgentResult.FAIL, "cli-agent queue full");
        } catch (Exception e) {
            log.warn("[CliAgent] chat failed: {}", e.getMessage());
            return CliAgentResult.error(CliAgentResult.FAIL, e.getMessage());
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody(required = false) CliAgentChatRequest request,
                                 HttpServletResponse response) {
        CliAgentStreamHeaders.apply(response);
        SseEmitter emitter = new SseEmitter(0L);
        if (request == null || request.getPrompt() == null || request.getPrompt().trim().isEmpty()) {
            emitter.completeWithError(new IllegalArgumentException("prompt is required"));
            return emitter;
        }
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception e) {
            log.debug("[CliAgent] failed to send SSE connected comment: {}", e.getMessage());
        }
        try {
            cliAgentExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        cliAgentService.chatStream(request, new java.util.function.Consumer<CliStreamEvent>() {
                            @Override
                            public void accept(CliStreamEvent event) {
                                try {
                                    String type = event.getType() != null ? event.getType() : CliStreamEventTypes.TEXT;
                                    emitter.send(SseEmitter.event()
                                            .name(type)
                                            .data(JsonUtil.toJson(event)));
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        });
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (Exception e) {
                        log.warn("[CliAgent] stream chat failed: {}", e.getMessage());
                        try {
                            CliStreamEvent error = CliStreamEvent.ofType(CliStreamEventTypes.ERROR);
                            error.setMessage(e.getMessage());
                            emitter.send(SseEmitter.event()
                                    .name(CliStreamEventTypes.ERROR)
                                    .data(JsonUtil.toJson(error)));
                        } catch (Exception ignored) {
                            // ignore secondary send failure
                        }
                        emitter.completeWithError(e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            emitter.completeWithError(new RuntimeException("cli-agent queue full"));
        }
        return emitter;
    }
}
