package com.zifang.z.team.agent.wrapper;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

/**
 * 统一流式 JSON 行解析器：从 CLI stdout 逐行提取 text delta。
 *
 * <p>支持多种格式（按实测）：
 * <ul>
 *   <li><b>stream_event</b>（claude code --include-partial-messages 真流式）：
 *       {@code {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}}}
 *       → 提取 event.delta.text ★关键</li>
 *   <li><b>content_block_delta</b>：delta.text（兼容）</li>
 *   <li><b>assistant</b>：message.content[].text（兜底完整块）</li>
 *   <li><b>OpenAI choices</b>：choices[0].delta.content</li>
 *   <li><b>简单格式</b>：text / content / output 字段</li>
 * </ul>
 *
 * <p>过滤 CLI 元信息行（hermes session_id/Duration 等前缀）。
 */
public final class StreamJsonParser {

    private StreamJsonParser() {
    }

    /**
     * 从一行 stdout 提取文本增量。返回 null 表示该行无有效 delta。
     */
    public static String extractDelta(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        String trimmed = line.trim();

        // 过滤 CLI 元信息行（hermes 等）
        if (trimmed.startsWith("session_id:")
                || trimmed.startsWith("Session:")
                || trimmed.startsWith("Duration:")
                || trimmed.startsWith("Messages:")
                || trimmed.startsWith("Resume this session")
                || trimmed.startsWith("hermes --resume")
                || trimmed.startsWith("Query:")) {
            return null;
        }

        try {
            JsonObject json = JsonUtil.parseObject(line);
            String type = json.getString("type");

            // ★ stream_event（claude code --include-partial-messages 真流式）
            // {"type":"stream_event", "event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}}
            if ("stream_event".equals(type)) {
                JsonObject event = Jsons.optObject(json, "event");
                if (event != null) {
                    String eventType = event.getString("type");
                    if ("content_block_delta".equals(eventType) || "content_delta".equals(eventType)) {
                        JsonObject delta = Jsons.optObject(event, "delta");
                        if (delta != null) {
                            // 只取 text_delta，跳过 thinking_delta/signature_delta
                            if ("text_delta".equals(delta.getString("type"))) {
                                String text = delta.getString("text");
                                if (text != null && !text.isEmpty()) {
                                    return text;
                                }
                            }
                        }
                    }
                }
                // stream_event 的其他子类型（message_start/content_block_start 等）不产生 text
                return null;
            }

            // content_block_delta（非嵌套形式，兼容）
            if ("content_block_delta".equals(type)) {
                JsonObject delta = Jsons.optObject(json, "delta");
                if (delta != null) {
                    String text = delta.getString("text");
                    if (text != null) {
                        return text;
                    }
                }
            }

            // ★ claude code stream-json 的 assistant/result 是「完整快照」而非增量：
            // stream_event 的 content_block_delta 已提供全部 text_delta，
            // assistant(message.content[].text) 和 result 是同一内容的完整镜像。
            // 若当 delta 返回，会与 stream_event 重复累加（实测 "你好" 被输出 3 次）。
            // 故此处返回 null，仅依赖 stream_event 提供增量。
            // （ClaudeCodeWrapper 强制 --include-partial-messages，必有 stream_event）
            if ("assistant".equals(type) || "result".equals(type)) {
                return null;
            }

            // OpenAI 兼容: choices[0].delta.content
            JsonArray choices = Jsons.optArray(json, "choices");
            if (choices != null && !choices.isEmpty()) {
                Object first = choices.get(0);
                JsonObject choice = first instanceof JsonObject ? (JsonObject) first : null;
                if (choice != null) {
                    JsonObject delta = Jsons.optObject(choice, "delta");
                    if (delta != null) {
                        String content = delta.getString("content");
                        if (content != null) {
                            return content;
                        }
                    }
                    JsonObject message = Jsons.optObject(choice, "message");
                    if (message != null) {
                        String content = message.getString("content");
                        if (content != null) {
                            return content;
                        }
                    }
                }
            }

            // 简单格式: text / content / output
            String text = json.getString("text");
            if (text != null) {
                return text;
            }
            String content = json.getString("content");
            if (content != null) {
                return content;
            }
            String output = json.getString("output");
            if (output != null) {
                return output;
            }

        } catch (Exception e) {
            // 非 JSON 行，直接作为文本返回
            if (!line.startsWith("{") && !line.startsWith("[")) {
                return line;
            }
        }

        return null;
    }

    /**
     * 从 stdout 全量输出提取 result 行的最终回复文本：
     * 先整段解析（qodercli 单行 JSON 模式），失败再从后往前逐行解析
     * （claude-code 多行 stream-json 模式）。is_error 的 result 行不算成功输出。
     */
    public static String parseJsonOutput(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String trimmed = raw.trim();
        String fromWhole = parseJsonResultObject(trimmed);
        if (fromWhole != null) {
            return fromWhole;
        }
        String[] lines = trimmed.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String parsed = parseJsonResultObject(line);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /**
     * 从 stream-json / json 的 result 行提取错误信息（is_error=true 的 result 文本），
     * 从后往前找（取最后一次 result 行）；无错误 result 行返回 null。
     */
    public static String extractResultError(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] lines = raw.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                JsonObject json = JsonUtil.parseObject(line);
                if (!"result".equals(json.getString("type"))) {
                    continue;
                }
                if (Boolean.TRUE.equals(json.getBoolean("is_error"))) {
                    String result = json.getString("result");
                    if (result != null && !result.isEmpty()) {
                        return result;
                    }
                }
            } catch (Exception ignored) {
                // try next line
            }
        }
        return null;
    }

    /** 单行 result JSON 解析：非 result 行 / is_error / 空 result 均返回 null。 */
    private static String parseJsonResultObject(String jsonLine) {
        try {
            JsonObject json = JsonUtil.parseObject(jsonLine);
            if (!"result".equals(json.getString("type"))) {
                return null;
            }
            if (Boolean.TRUE.equals(json.getBoolean("is_error"))) {
                return null;
            }
            String result = json.getString("result");
            if (result != null && !result.isEmpty()) {
                return result;
            }
        } catch (Exception ignored) {
            // not a result json line
        }
        return null;
    }
}
