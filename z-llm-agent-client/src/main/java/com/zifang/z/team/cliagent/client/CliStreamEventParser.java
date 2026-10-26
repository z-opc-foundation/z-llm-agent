package com.zifang.z.team.cliagent.client;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.team.cliagent.common.CliStreamEvent;

import java.util.function.Consumer;

/**
 * 解析 cli-agent typed SSE（event + data 行）。
 */
public final class CliStreamEventParser {

    private String pendingEventName;
    private final StringBuilder pendingData = new StringBuilder();

    public void feedLine(String line, Consumer<CliStreamEvent> onEvent) {
        if (line == null) {
            return;
        }
        if (line.isEmpty()) {
            flush(onEvent);
            return;
        }
        if (line.startsWith("event:")) {
            pendingEventName = line.substring(6).trim();
            return;
        }
        if (line.startsWith("data:")) {
            if (pendingData.length() > 0) {
                pendingData.append('\n');
            }
            pendingData.append(line.substring(5).trim());
        }
    }

    public void finish(Consumer<CliStreamEvent> onEvent) {
        flush(onEvent);
    }

    private void flush(Consumer<CliStreamEvent> onEvent) {
        if (pendingData.length() == 0) {
            pendingEventName = null;
            return;
        }
        String payload = pendingData.toString();
        pendingData.setLength(0);
        if ("[DONE]".equals(payload)) {
            pendingEventName = null;
            return;
        }
        CliStreamEvent event = JsonUtil.fromJson(payload, CliStreamEvent.class);
        if (event != null && event.getType() == null && pendingEventName != null) {
            event.setType(pendingEventName);
        }
        pendingEventName = null;
        if (event != null && onEvent != null) {
            onEvent.accept(event);
        }
    }
}
