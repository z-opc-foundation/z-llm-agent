package com.zifang.z.team.agent.proxy;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Session 注册表：管理 sessionId → cliType/agentId 的绑定关系，支持文件持久化。
 *
 * <p>openSession 时绑定 CLI 类型，后续 chat 请求只需带 sessionId 即可自动路由到对应 wrapper，
 * 无需每次携带 {@code X-CLI-Type} 或 {@code X-Agent-Id} 头。
 *
 * <p>持久化：构造时传入 persistDir，session 绑定关系保存到 {@code sessions.json}，
 * proxy 重启后自动加载，确保 session 路由不丢失。
 *
 * <p>如果 openSession 时未指定 cliType（向后兼容），session 为"无类型"，
 * chat 请求仍可通过 header 路由。
 */
public class SessionRegistry {

    private static final Logger LOG = Logger.getLogger(SessionRegistry.class.getName());

    private final ConcurrentMap<String, String> sessionCliTypes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionAgentIds = new ConcurrentHashMap<>();
    private final Path persistFile;

    /**
     * 无参构造（不持久化，仅内存态）。
     */
    public SessionRegistry() {
        this.persistFile = null;
    }

    /**
     * 带持久化目录的构造函数。
     * 启动时从 {persistDir}/sessions.json 加载已保存的 session 绑定关系。
     *
     * @param persistDir 持久化目录（通常为 ~/.z-team）
     */
    public SessionRegistry(String persistDir) {
        if (persistDir != null && !persistDir.trim().isEmpty()) {
            String expanded = persistDir.replace("~", System.getProperty("user.home"));
            this.persistFile = Paths.get(expanded).resolve("sessions.json");
            loadFromFile();
        } else {
            this.persistFile = null;
        }
    }

    /**
     * 创建会话并绑定 CLI 类型。
     *
     * @param cliType CLI 类型（claude-code / opencode / hermes），可为 null（无类型会话）
     * @param agentId 已注册的 agent ID，可为 null（按 type 路由而非精确 agent）
     * @return 会话 ID（UUID）
     */
    public String openSession(String cliType, String agentId) {
        String sessionId = UUID.randomUUID().toString();
        if (cliType != null && !cliType.isEmpty()) {
            sessionCliTypes.put(sessionId, cliType);
        }
        if (agentId != null && !agentId.isEmpty()) {
            sessionAgentIds.put(sessionId, agentId);
        }
        saveToFile();
        LOG.info("Session opened: " + sessionId + " cliType=" + cliType + " agentId=" + agentId);
        return sessionId;
    }

    /**
     * 获取 session 绑定的 CLI 类型。
     *
     * @return cliType，未绑定返回 null
     */
    public String getCliType(String sessionId) {
        return sessionCliTypes.get(sessionId);
    }

    /**
     * 获取 session 绑定的 agent ID。
     *
     * @return agentId，未绑定返回 null
     */
    public String getAgentId(String sessionId) {
        return sessionAgentIds.get(sessionId);
    }

    // ──────────── 持久化 ────────────

    /**
     * 从文件加载已保存的 session 绑定关系。
     */
    private void loadFromFile() {
        if (persistFile == null || !Files.exists(persistFile)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(persistFile), StandardCharsets.UTF_8);
            JsonArray arr = JsonUtil.parseArray(json);
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.getJsonObject(i);
                String sessionId = obj.getString("sessionId");
                String cliType = obj.getString("cliType");
                String agentId = obj.getString("agentId");
                if (sessionId != null) {
                    if (cliType != null && !cliType.isEmpty()) {
                        sessionCliTypes.put(sessionId, cliType);
                    }
                    if (agentId != null && !agentId.isEmpty()) {
                        sessionAgentIds.put(sessionId, agentId);
                    }
                }
            }
            LOG.info("Loaded " + sessionCliTypes.size() + " sessions from " + persistFile);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to load sessions from " + persistFile, e);
        }
    }

    /**
     * 保存 session 绑定关系到文件。
     */
    private void saveToFile() {
        if (persistFile == null) {
            return;
        }
        try {
            // 收集所有 sessionId（两个 map 的并集）
            Set<String> allIds = new HashSet<>();
            allIds.addAll(sessionCliTypes.keySet());
            allIds.addAll(sessionAgentIds.keySet());

            JsonArray arr = new JsonArray();
            for (String sessionId : allIds) {
                JsonObject obj = new JsonObject();
                obj.put("sessionId", sessionId);
                obj.put("cliType", sessionCliTypes.get(sessionId));
                obj.put("agentId", sessionAgentIds.get(sessionId));
                arr.add(obj);
            }
            Path parent = persistFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(persistFile, JsonUtil.toJson(arr).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to save sessions to " + persistFile, e);
        }
    }
}
