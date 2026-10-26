package com.zifang.z.team.agent.wrapper;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLI stream-json → 结构化 SSE 事件桥：从 CLI stdout 逐行解析出
 * text / thinking / tool_use / tool_result 四类内容，
 * 以回调形式推给调用方（wrapper 写 SSE），并按时间序收集 blocks
 * 用于会话持久化（前端刷新后仍可重放思考过程与工具调用）。
 *
 * <p>支持的 CLI 行格式（claude-code / qodercli stream-json 同构）：
 * <ul>
 *   <li>{@code stream_event} + {@code content_block_delta(text_delta)} → 文本增量（真流式）</li>
 *   <li>{@code stream_event} + {@code content_block_delta(thinking_delta)} → 思考增量</li>
 *   <li>{@code stream_event} + {@code content_block_start(tool_use)} → 工具开始事件</li>
 *   <li>{@code stream_event} + {@code input_json_delta} → 累积工具入参，stop 时补发 tool_end</li>
 *   <li>{@code assistant} 完整块（qodercli 无 --include-partial-messages 时唯一来源）
 *       → {@link #finalFlush()} 伪流式补发（thinking/text 按行推送，tool_use 整块补发）</li>
 *   <li>{@code user} 块的 {@code tool_result} → 工具结果事件</li>
 *   <li>{@code result} 行 → 提取 usage（token 用量）与 duration_ms（CLI 侧回合计时），
 *       envelope 顶层透传（前端历史重放展示）</li>
 * </ul>
 *
 * <p>去重规则：真流式场景下 assistant 完整块与 stream_event 增量是同一内容的镜像，
 * text/thinking 以增量为准不重发；tool_use 按 id 去重（增量 content_block_start 已发则跳过）。
 */
public final class StreamEventBridge {

    /** 工具结果持久化/回传截断上限（防止读文件类结果撑爆 transcript 与前端）。 */
    static final int MAX_RESULT_CHARS = 8000;

    /**
     * 事件回调：调用方负责把回调内容写成 SSE。
     * 回调时序即真实时间序（thinking → tool → text 交错）。
     */
    public interface Listener {
        void onTextDelta(String delta);

        void onReasoningDelta(String delta);

        void onFtEvent(JsonObject event);
    }

    private final Listener listener;

    /** 持久化 blocks（时间序）：text / thinking / tool_call / tool_result。 */
    private final List<JsonObject> blocks = new ArrayList<JsonObject>();

    /** stream_event 中 content_block index → 工具上下文（id/name/inputBuf）。 */
    private final Map<Integer, JsonObject> openTools = new LinkedHashMap<Integer, JsonObject>();

    /** 已发过 tool_call 事件的工具 id（assistant 完整块补发时去重）。 */
    private final Set<String> emittedToolIds = new LinkedHashSet<String>();

    private boolean hasStreamText = false;
    private boolean hasStreamThinking = false;

    // ── 计时与用量（envelope 持久化，历史重放展示；流式运行时由前端自计） ──
    /** 回合开始（bridge 创建即聊天请求受理时刻）。 */
    private final long turnStartMs = System.currentTimeMillis();
    /** 首个 thinking 增量时刻；null = 尚未出现思考。 */
    private Long thinkingStartMs = null;
    /** 思考已结算（正文/工具事件开始即视为思考结束）。 */
    private boolean thinkingClosed = false;
    /** toolId → 调用发起时刻（tool_result 到达时结算耗时）。 */
    private final Map<String, Long> toolStartMs = new LinkedHashMap<String, Long>();
    /** result 行的 duration_ms（CLI 侧回合计时，优先于本地计时）。 */
    private Long resultTotalMs = null;
    /** result 行解析的 token 用量 {inputTokens,outputTokens}，可能为 null。 */
    private JsonObject resultUsage = null;

    /** 最近一个 assistant 完整块（伪流式兜底数据源，finalFlush 时消费）。 */
    private JsonObject pendingAssistantContent = null;

    public StreamEventBridge(Listener listener) {
        this.listener = listener;
    }

    /**
     * 处理一行 stdout。返回是否为已识别的 stream-json 行
     * （false = 非 JSON 行，调用方可自行回退提取逻辑）。
     */
    public boolean processLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        try {
            JsonObject json = JsonUtil.parseObject(trimmed);
            String type = json.getString("type");
            if ("stream_event".equals(type)) {
                handleStreamEvent(json, Jsons.optObject(json, "event"));
                return true;
            }
            if ("assistant".equals(type)) {
                rememberAssistantBlock(json);
                return true;
            }
            if ("user".equals(type)) {
                handleUserBlock(json);
                return true;
            }
            if ("result".equals(type)) {
                rememberResultMeta(json);
                return true;
            }
            // system 等元信息行：跳过
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 流结束后补发伪流式内容（必须在 while(readLine) 循环之后、SSE [DONE] 之前调用）。
     */
    public void finalFlush() {
        if (pendingAssistantContent == null) {
            return;
        }
        JsonArray content = Jsons.optArray(pendingAssistantContent, "content");
        if (content == null) {
            return;
        }
        boolean purePseudo = !hasStreamText && !hasStreamThinking;
        for (int i = 0; i < content.size(); i++) {
            Object item = content.get(i);
            JsonObject block = item instanceof JsonObject ? (JsonObject) item : null;
            if (block == null) {
                continue;
            }
            String blockType = block.getString("type");
            if ("thinking".equals(blockType)) {
                String thinking = block.getString("thinking");
                if (thinking != null && !thinking.isEmpty() && !hasStreamThinking) {
                    emitPseudoLines(thinking, true);
                }
            } else if ("text".equals(blockType)) {
                String text = block.getString("text");
                if (text != null && !text.isEmpty() && !hasStreamText && purePseudo) {
                    emitPseudoLines(text, false);
                }
            } else if ("tool_use".equals(blockType)) {
                String toolId = block.getString("id");
                if (toolId != null && !emittedToolIds.contains(toolId)) {
                    emitToolCall(toolId, block.getString("name"), block.get("input"));
                    // 完整块 input 已就绪：直接补 tool_end（与真流式 content_block_stop 对齐）
                    Object normalized = normalizeInput(block.get("input"));
                    if (normalized instanceof JsonObject) {
                        fillToolCallInput(toolId, (JsonObject) normalized);
                    }
                    JsonObject ev = new JsonObject();
                    ev.put("kind", "tool_end");
                    ev.put("id", toolId);
                    ev.put("input", normalized);
                    listener.onFtEvent(ev);
                }
            }
        }
        pendingAssistantContent = null;
    }

    /**
     * 注入外部提取的文本增量（非 stream-json 行由调用方用旧逻辑提取后注入，
     * 统一由 bridge 写 SSE 与 blocks，避免调用方重复推送）。
     */
    public void injectTextDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        hasStreamText = true;
        appendTextBlock(delta);
        listener.onTextDelta(delta);
    }

    /**
     * 累积的全部 assistant 文本（真流式增量 + 伪流式补发），作为纯文本回复
     * （无 blocks 时直接持久化该文本，与旧行为一致）。
     */
    public String getAssistantText() {
        StringBuilder sb = new StringBuilder();
        for (JsonObject block : blocks) {
            if ("text".equals(block.getString("type"))) {
                sb.append(block.getString("text"));
            }
        }
        return sb.toString();
    }

    /**
     * 构建持久化 envelope：有结构化 blocks 时返回 {@code {"z_team_blocks":[...]}}，
     * 否则返回 null（调用方持久化纯文本）。
     */
    public String buildEnvelope() {
        if (blocks.isEmpty()) {
            return null;
        }
        JsonObject envelope = new JsonObject();
        envelope.put("z_team_blocks", blocks);
        envelope.put("totalMs", resultTotalMs != null
                ? resultTotalMs
                : Math.max(0, System.currentTimeMillis() - turnStartMs));
        if (resultUsage != null) {
            envelope.put("usage", resultUsage);
        }
        return Jsons.compact(envelope);
    }

    /** 解析 result 行：duration_ms（CLI 侧回合计时）与 usage（token 用量），缺失字段忽略。 */
    private void rememberResultMeta(JsonObject json) {
        resultTotalMs = json.getLong("duration_ms");
        JsonObject u = Jsons.optObject(json, "usage");
        if (u != null) {
            Long in = firstLong(u, "input_tokens", "inputTokens", "prompt_tokens");
            Long out = firstLong(u, "output_tokens", "outputTokens", "completion_tokens");
            if (in != null || out != null) {
                resultUsage = new JsonObject();
                if (in != null) {
                    resultUsage.put("inputTokens", in);
                }
                if (out != null) {
                    resultUsage.put("outputTokens", out);
                }
            }
        }
    }

    private static Long firstLong(JsonObject obj, String... keys) {
        for (String key : keys) {
            Long v = obj.getLong(key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    // ──────────── stream_event（真流式） ────────────

    private void handleStreamEvent(JsonObject json, JsonObject event) {
        if (event == null) {
            return;
        }
        String eventType = event.getString("type");
        // index 双位置兼容：Anthropic 原生格式在 event 内，部分 CLI 包在外层
        Integer index = event.getInt("index");
        if (index == null) {
            index = json.getInt("index");
        }

        if ("content_block_start".equals(eventType)) {
            JsonObject contentBlock = Jsons.optObject(event, "content_block");
            if (contentBlock != null && "tool_use".equals(contentBlock.getString("type"))) {
                String toolId = contentBlock.getString("id");
                String name = contentBlock.getString("name");
                JsonObject ctx = new JsonObject();
                ctx.put("id", toolId);
                ctx.put("name", name);
                ctx.put("inputBuf", new StringBuilder());
                if (index != null) {
                    openTools.put(index, ctx);
                }
                emitToolCall(toolId, name, contentBlock.get("input"));
            }
            return;
        }

        if ("content_block_delta".equals(eventType) || "content_delta".equals(eventType)) {
            JsonObject delta = Jsons.optObject(event, "delta");
            if (delta == null) {
                return;
            }
            String deltaType = delta.getString("type");
            if ("text_delta".equals(deltaType)) {
                String text = delta.getString("text");
                if (text != null && !text.isEmpty()) {
                    hasStreamText = true;
                    appendTextBlock(text);
                    listener.onTextDelta(text);
                }
            } else if ("thinking_delta".equals(deltaType)) {
                String thinking = delta.getString("thinking");
                if (thinking != null && !thinking.isEmpty()) {
                    hasStreamThinking = true;
                    appendThinkingBlock(thinking);
                    listener.onReasoningDelta(thinking);
                }
            } else if ("input_json_delta".equals(deltaType)) {
                if (index != null && openTools.containsKey(index)) {
                    JsonObject ctx = openTools.get(index);
                    StringBuilder buf = (StringBuilder) ctx.get("inputBuf");
                    String partial = delta.getString("partial_json");
                    if (buf != null && partial != null) {
                        buf.append(partial);
                    }
                }
            }
            return;
        }

        if ("content_block_stop".equals(eventType) && index != null && openTools.containsKey(index)) {
            JsonObject ctx = openTools.remove(index);
            StringBuilder buf = (StringBuilder) ctx.get("inputBuf");
            JsonObject input = parseToolInput(buf != null ? buf.toString() : null);
            fillToolCallInput(ctx.getString("id"), input);
            JsonObject ev = new JsonObject();
            ev.put("kind", "tool_end");
            ev.put("id", ctx.getString("id"));
            ev.put("input", input);
            listener.onFtEvent(ev);
        }
    }

    // ──────────── assistant / user 完整块 ────────────

    /** 记录 assistant 完整块（完整快照，finalFlush 统一消费，避免中途重复推送）。 */
    private void rememberAssistantBlock(JsonObject json) {
        JsonObject message = Jsons.optObject(json, "message");
        if (message == null) {
            return;
        }
        JsonArray content = Jsons.optArray(message, "content");
        if (content == null || content.isEmpty()) {
            return;
        }
        pendingAssistantContent = message;
    }

    private void handleUserBlock(JsonObject json) {
        JsonObject message = Jsons.optObject(json, "message");
        if (message == null) {
            return;
        }
        JsonArray content = Jsons.optArray(message, "content");
        if (content == null) {
            return;
        }
        for (int i = 0; i < content.size(); i++) {
            Object item = content.get(i);
            JsonObject block = item instanceof JsonObject ? (JsonObject) item : null;
            if (block == null || !"tool_result".equals(block.getString("type"))) {
                continue;
            }
            String toolId = block.getString("tool_use_id");
            String resultText = extractToolResultContent(block.get("content"));
            addToolResultBlock(toolId, resultText);
            JsonObject ev = new JsonObject();
            ev.put("kind", "tool_result");
            ev.put("id", toolId);
            ev.put("content", resultText);
            listener.onFtEvent(ev);
        }
    }

    // ──────────── 事件发射与 blocks 维护 ────────────

    /** 发射 tool_call 事件并落 blocks（input 未知时为 null，后续 tool_end 补齐）。 */
    private void emitToolCall(String toolId, String name, Object input) {
        closeThinking();
        if (toolId != null) {
            emittedToolIds.add(toolId);
            toolStartMs.put(toolId, System.currentTimeMillis());
        }
        JsonObject block = new JsonObject();
        block.put("type", "tool_call");
        block.put("id", toolId);
        block.put("name", name != null ? name : "tool");
        if (input != null) {
            block.put("input", normalizeInput(input));
        }
        blocks.add(block);

        JsonObject ev = new JsonObject();
        ev.put("kind", "tool_call");
        ev.put("id", toolId);
        ev.put("name", block.getString("name"));
        if (input != null) {
            ev.put("input", normalizeInput(input));
        }
        listener.onFtEvent(ev);
    }

    private void fillToolCallInput(String toolId, JsonObject input) {
        if (toolId == null || input == null) {
            return;
        }
        for (int i = blocks.size() - 1; i >= 0; i--) {
            JsonObject block = blocks.get(i);
            if ("tool_call".equals(block.getString("type")) && toolId.equals(block.getString("id"))) {
                block.put("input", input);
                return;
            }
        }
    }

    private void addToolResultBlock(String toolId, String content) {
        closeThinking();
        Long start = toolId != null ? toolStartMs.remove(toolId) : null;
        Long duration = start != null ? Long.valueOf(Math.max(0, System.currentTimeMillis() - start)) : null;
        // 耗时同时回写到 tool_call 块（调用行自带耗时）与 tool_result 块
        if (duration != null && toolId != null) {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                JsonObject call = blocks.get(i);
                if ("tool_call".equals(call.getString("type")) && toolId.equals(call.getString("id"))) {
                    call.put("durationMs", duration);
                    break;
                }
            }
        }
        JsonObject block = new JsonObject();
        block.put("type", "tool_result");
        block.put("id", toolId);
        block.put("content", content != null ? content : "");
        if (duration != null) {
            block.put("durationMs", duration);
        }
        blocks.add(block);
    }

    /** 思考结算：正文/工具事件开始即视为思考结束，耗时写入最后一个 thinking 块。 */
    private void closeThinking() {
        if (thinkingStartMs == null || thinkingClosed) {
            return;
        }
        thinkingClosed = true;
        long duration = Math.max(0, System.currentTimeMillis() - thinkingStartMs);
        for (int i = blocks.size() - 1; i >= 0; i--) {
            JsonObject block = blocks.get(i);
            if ("thinking".equals(block.getString("type"))) {
                block.put("durationMs", duration);
                return;
            }
        }
    }

    private void appendTextBlock(String delta) {
        closeThinking();
        if (!blocks.isEmpty()) {
            JsonObject last = blocks.get(blocks.size() - 1);
            if ("text".equals(last.getString("type"))) {
                last.put("text", last.getString("text") + delta);
                return;
            }
        }
        JsonObject block = new JsonObject();
        block.put("type", "text");
        block.put("text", delta);
        blocks.add(block);
    }

    private void appendThinkingBlock(String delta) {
        if (!blocks.isEmpty()) {
            JsonObject last = blocks.get(blocks.size() - 1);
            if ("thinking".equals(last.getString("type"))) {
                last.put("text", last.getString("text") + delta);
                return;
            }
        }
        if (thinkingStartMs == null) {
            thinkingStartMs = System.currentTimeMillis();
        }
        JsonObject block = new JsonObject();
        block.put("type", "thinking");
        block.put("text", delta);
        blocks.add(block);
    }

    /** 伪流式推送：按行切分文本逐段回调（thinking 走 reasoning 通道，text 走 text 通道）。 */
    private void emitPseudoLines(String text, boolean reasoning) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String piece = i < lines.length - 1 ? lines[i] + "\n" : lines[i];
            if (piece.isEmpty()) {
                continue;
            }
            if (reasoning) {
                appendThinkingBlock(piece);
                listener.onReasoningDelta(piece);
            } else {
                appendTextBlock(piece);
                listener.onTextDelta(piece);
            }
        }
    }

    // ──────────── 工具方法 ────────────

    private static JsonObject parseToolInput(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonObject parsed = JsonUtil.parseObject(raw);
            return parsed != null ? parsed : new JsonObject();
        } catch (Exception e) {
            JsonObject fallback = new JsonObject();
            fallback.put("_raw", raw.length() > MAX_RESULT_CHARS ? raw.substring(0, MAX_RESULT_CHARS) : raw);
            return fallback;
        }
    }

    private static Object normalizeInput(Object input) {
        if (input instanceof JsonObject) {
            return input;
        }
        if (input instanceof String && !((String) input).trim().isEmpty()) {
            try {
                return JsonUtil.parseObject((String) input);
            } catch (Exception e) {
                JsonObject fallback = new JsonObject();
                fallback.put("_raw", input);
                return fallback;
            }
        }
        JsonObject empty = new JsonObject();
        return empty;
    }

    /**
     * 提取 tool_result 的文本内容：string 直接返回；
     * array（[{type:"text",text:"..."}]）拼接 text 块；超长截断。
     */
    static String extractToolResultContent(Object content) {
        StringBuilder sb = new StringBuilder();
        if (content instanceof String) {
            sb.append((String) content);
        } else if (content instanceof JsonArray) {
            JsonArray arr = (JsonArray) content;
            for (int i = 0; i < arr.size(); i++) {
                Object item = arr.get(i);
                if (item instanceof JsonObject) {
                    String text = ((JsonObject) item).getString("text");
                    if (text != null) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(text);
                    }
                } else if (item instanceof String) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append((String) item);
                }
            }
        } else if (content instanceof JsonObject) {
            String text = ((JsonObject) content).getString("text");
            if (text != null) {
                sb.append(text);
            }
        }
        String result = sb.toString();
        if (result.length() > MAX_RESULT_CHARS) {
            result = result.substring(0, MAX_RESULT_CHARS) + "\n…(截断，共 " + sb.length() + " 字符)";
        }
        return result;
    }
}
