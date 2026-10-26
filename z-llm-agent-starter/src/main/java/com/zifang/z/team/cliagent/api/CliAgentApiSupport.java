package com.zifang.z.team.cliagent.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 控制器公共工具：请求体取值。
 */
final class CliAgentApiSupport {

    private CliAgentApiSupport() {
    }

    static String readString(Map<String, Object> body, String... keys) {
        if (body == null) {
            return null;
        }
        for (int i = 0; i < keys.length; i++) {
            Object raw = body.get(keys[i]);
            if (raw != null && String.valueOf(raw).trim().length() > 0) {
                return String.valueOf(raw).trim();
            }
        }
        return null;
    }

    static boolean readBoolean(Map<String, Object> body, String key, boolean defaultValue) {
        if (body == null || !body.containsKey(key)) {
            return defaultValue;
        }
        Object raw = body.get(key);
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readMapList(Map<String, Object> body, String... keys) {
        if (body == null) {
            return null;
        }
        for (int i = 0; i < keys.length; i++) {
            Object raw = body.get(keys[i]);
            if (raw instanceof List) {
                List<?> list = (List<?>) raw;
                List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
                for (int j = 0; j < list.size(); j++) {
                    Object item = list.get(j);
                    if (item instanceof Map) {
                        out.add((Map<String, Object>) item);
                    }
                }
                return out;
            }
        }
        return null;
    }
}
