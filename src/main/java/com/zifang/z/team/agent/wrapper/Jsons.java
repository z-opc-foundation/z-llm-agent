package com.zifang.z.team.agent.wrapper;

import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

import java.util.Collection;
import java.util.Map;

/**
 * 紧凑 JSON 序列化：SSE 事件帧与 envelope 持久化要求单行紧凑输出，
 * 而 z-util JsonObject/JsonArray 的 toString() 与 JsonUtil.toJson()
 * 走 BeautifyJsonUtils 美化输出（多行缩进），不适合逐行 SSE 帧与
 * 单行 JSON 协议，故在此提供紧凑序列化。
 *
 * <p>支持 JsonObject / JsonArray / Collection / String / Number / Boolean / null，
 * 未知类型兜底按字符串写出；键序不保证（JsonObject 内部为 HashMap，
 * 键序无业务语义——blocks 的时间序由外层 List 保持）。</p>
 */
public final class Jsons {

    private Jsons() {
    }

    /**
     * 宽容读取可选 JsonObject 字段：缺失或类型不符返回 null。
     * <p>z-util 的 getJsonObject 对缺失键抛 IllegalArgumentException（与 fastjson 返回 null
     * 语义不同），CLI 行协议字段可选性强，统一走本助手。
     */
    public static JsonObject optObject(JsonObject obj, String key) {
        if (obj == null || !obj.containsKey(key)) {
            return null;
        }
        Object v = obj.get(key);
        return v instanceof JsonObject ? (JsonObject) v : null;
    }

    /** 宽容读取可选 JsonArray 字段：缺失或类型不符返回 null。 */
    public static JsonArray optArray(JsonObject obj, String key) {
        if (obj == null || !obj.containsKey(key)) {
            return null;
        }
        Object v = obj.get(key);
        return v instanceof JsonArray ? (JsonArray) v : null;
    }

    /** 序列化为单行紧凑 JSON。 */
    public static String compact(Object value) {
        StringBuilder sb = new StringBuilder(128);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof JsonObject) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((JsonObject) value).getAllKeyValue()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof JsonArray) {
            JsonArray arr = (JsonArray) value;
            sb.append('[');
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, arr.get(i));
            }
            sb.append(']');
            return;
        }
        if (value instanceof Collection) {
            // envelope 持有 List<JsonObject>（z_team_blocks），等价数组输出
            sb.append('[');
            boolean first = true;
            for (Object item : (Collection<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
            return;
        }
        if (value instanceof String) {
            writeString(sb, (String) value);
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            sb.append(value.toString());
            return;
        }
        // 兜底：按字符串写出（与 fastjson 对未知对象的处理一致）
        writeString(sb, String.valueOf(value));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
