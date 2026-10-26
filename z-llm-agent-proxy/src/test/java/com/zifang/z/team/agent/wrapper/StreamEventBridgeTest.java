package com.zifang.z.team.agent.wrapper;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StreamEventBridge 解析验证：thinking/text 真流式、工具生命周期、
 * tool_result 回传、assistant 完整块伪流式兜底与去重、blocks envelope。
 */
class StreamEventBridgeTest {

    /** 收集回调的桩。 */
    private static class RecordingListener implements StreamEventBridge.Listener {
        final List<String> texts = new ArrayList<String>();
        final List<String> reasonings = new ArrayList<String>();
        final List<JsonObject> events = new ArrayList<JsonObject>();

        @Override
        public void onTextDelta(String delta) {
            texts.add(delta);
        }

        @Override
        public void onReasoningDelta(String delta) {
            reasonings.add(delta);
        }

        @Override
        public void onFtEvent(JsonObject event) {
            events.add(event);
        }

        String joinedText() {
            return String.join("", texts);
        }

        String joinedReasoning() {
            return String.join("", reasonings);
        }
    }

    @Test
    void streamEvent_textAndThinkingDeltas() {
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);

        bridge.processLine("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"先分析\"}}}");
        bridge.processLine("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}}");
        bridge.finalFlush();

        assertEquals("先分析", listener.joinedReasoning());
        assertEquals("你好", listener.joinedText());
        assertTrue(listener.events.isEmpty());
        // blocks 非空（thinking+text）→ envelope 存在
        assertNotNull(bridge.buildEnvelope());
    }

    @Test
    void toolLifecycle_startInputStopResult() {
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);

        bridge.processLine("{\"type\":\"stream_event\",\"index\":1,\"event\":{\"type\":\"content_block_start\","
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Bash\",\"input\":{}}}}");
        bridge.processLine("{\"type\":\"stream_event\",\"index\":1,\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"ls -la\\\"\"}}}");
        bridge.processLine("{\"type\":\"stream_event\",\"index\":1,\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"}\"}}}");
        bridge.processLine("{\"type\":\"stream_event\",\"index\":1,\"event\":{\"type\":\"content_block_stop\"}}");
        bridge.processLine("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"toolu_1\",\"content\":[{\"type\":\"text\",\"text\":\"file list\"}]}]}}");
        bridge.finalFlush();

        assertEquals(3, listener.events.size());
        JsonObject call = listener.events.get(0);
        assertEquals("tool_call", call.getString("kind"));
        assertEquals("Bash", call.getString("name"));
        JsonObject end = listener.events.get(1);
        assertEquals("tool_end", end.getString("kind"));
        assertEquals("ls -la", end.getJsonObject("input").getString("command"));
        JsonObject result = listener.events.get(2);
        assertEquals("tool_result", result.getString("kind"));
        assertEquals("file list", result.getString("content"));

        // envelope：tool_call block 带补齐后的 input，tool_result 落块
        JsonObject envelope = JsonUtil.parseObject(bridge.buildEnvelope());
        JsonArray blocks = envelope.getJsonArray("z_team_blocks");
        assertEquals(2, blocks.size());
        assertEquals("tool_call", blocks.getJsonObject(0).getString("type"));
        assertEquals("ls -la", blocks.getJsonObject(0).getJsonObject("input").getString("command"));
        assertEquals("tool_result", blocks.getJsonObject(1).getString("type"));
        assertEquals("file list", blocks.getJsonObject(1).getString("content"));

        // 纯文本为空（无 text delta）
        assertEquals("", bridge.getAssistantText());
    }

    @Test
    void assistantBlock_pseudoStreamFallback() {
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);

        // qodercli 无 --include-partial-messages：只有 assistant 完整块
        bridge.processLine("{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"想想\\n再想想\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"Read\",\"input\":{\"path\":\"a.md\"}},"
                + "{\"type\":\"text\",\"text\":\"第一行\\n第二行\"}]}}");
        bridge.finalFlush();

        assertEquals("想想\n再想想", listener.joinedReasoning());
        assertEquals("第一行\n第二行", listener.joinedText());
        assertEquals(2, listener.events.size());
        assertEquals("tool_call", listener.events.get(0).getString("kind"));
        assertEquals("tool_end", listener.events.get(1).getString("kind"));
        assertEquals("a.md", listener.events.get(1).getJsonObject("input").getString("path"));

        JsonObject envelope = JsonUtil.parseObject(bridge.buildEnvelope());
        JsonArray blocks = envelope.getJsonArray("z_team_blocks");
        assertEquals(3, blocks.size());
        assertEquals("thinking", blocks.getJsonObject(0).getString("type"));
        assertEquals("tool_call", blocks.getJsonObject(1).getString("type"));
        assertEquals("text", blocks.getJsonObject(2).getString("type"));
        assertEquals("第一行\n第二行", bridge.getAssistantText());
    }

    @Test
    void assistantBlock_noDuplicateAfterRealStream() {
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);

        // 真流式增量 + assistant 完整镜像 → text/thinking 不重发
        bridge.processLine("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}}");
        bridge.processLine("{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"你好\"}]}}");
        bridge.processLine("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"你好\"}");
        bridge.finalFlush();

        assertEquals(1, listener.texts.size());
        assertEquals("你好", listener.joinedText());
    }

    @Test
    void systemAndResultLinesIgnored() {
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);

        assertTrue(bridge.processLine("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"x\"}"));
        assertTrue(bridge.processLine("{\"type\":\"result\",\"result\":\"镜像文本\"}"));
        bridge.finalFlush();

        assertTrue(listener.texts.isEmpty());
        assertTrue(listener.reasonings.isEmpty());
    }

    @Test
    void toolResult_truncatedAndPlainString() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            big.append("0123456789");
        }
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);
        bridge.processLine("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"toolu_3\",\"content\":\"" + big + "\"}]}}");

        JsonObject result = listener.events.get(0);
        String content = result.getString("content");
        assertTrue(content.length() < big.length());
        assertTrue(content.contains("截断"));
    }

    @Test
    void nonJsonLine_returnsFalse() {
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);
        assertTrue(!bridge.processLine("plain text line"));
    }

    @Test
    void resultLine_usageAndDurationToEnvelope() {
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);
        bridge.processLine("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"答案\"}}}");
        bridge.processLine("{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"镜像\"," 
                + "\"duration_ms\":1234,\"usage\":{\"input_tokens\":120,\"output_tokens\":34}}");
        bridge.finalFlush();

        JsonObject envelope = JsonUtil.parseObject(bridge.buildEnvelope());
        assertEquals(1234L, envelope.getLong("totalMs").longValue());
        JsonObject usage = envelope.getJsonObject("usage");
        assertNotNull(usage);
        assertEquals(120L, usage.getLong("inputTokens").longValue());
        assertEquals(34L, usage.getLong("outputTokens").longValue());
    }

    @Test
    void resultLine_withoutMeta_envelopeStillHasLocalTotalMs() {
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);
        bridge.processLine("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\"," 
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}}");
        bridge.processLine("{\"type\":\"result\",\"subtype\":\"success\"}");
        bridge.finalFlush();

        JsonObject envelope = JsonUtil.parseObject(bridge.buildEnvelope());
        assertTrue(envelope.getLong("totalMs") >= 0);
        assertTrue(!envelope.containsKey("usage"));
    }

    @Test
    void blocks_durationMsPersisted() {
        RecordingListener listener = new RecordingListener();
        StreamEventBridge bridge = new StreamEventBridge(listener);

        bridge.processLine("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\"," 
                + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"思考\"}}}");
        bridge.processLine("{\"type\":\"stream_event\",\"index\":1,\"event\":{\"type\":\"content_block_start\"," 
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"Bash\",\"input\":{}}}}");
        bridge.processLine("{\"type\":\"stream_event\",\"index\":1,\"event\":{\"type\":\"content_block_stop\"}}");
        bridge.processLine("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\"," 
                + "\"tool_use_id\":\"toolu_9\",\"content\":\"ok\"}]}}");
        bridge.finalFlush();

        JsonObject envelope = JsonUtil.parseObject(bridge.buildEnvelope());
        JsonArray blocks = envelope.getJsonArray("z_team_blocks");
        JsonObject thinking = blocks.getJsonObject(0);
        assertEquals("thinking", thinking.getString("type"));
        assertNotNull(thinking.getLong("durationMs"));
        JsonObject toolCall = blocks.getJsonObject(1);
        assertEquals("tool_call", toolCall.getString("type"));
        assertNotNull(toolCall.getLong("durationMs"));
        JsonObject toolResult = blocks.getJsonObject(2);
        assertEquals("tool_result", toolResult.getString("type"));
        assertNotNull(toolResult.getLong("durationMs"));
    }
}
