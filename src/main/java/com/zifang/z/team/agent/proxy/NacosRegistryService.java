package com.zifang.z.team.agent.proxy;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonArray;
import com.zifang.util.json.model.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Nacos 配置注册（CAS 乐观锁 + 轮流保活）。
 *
 * <p>配置内容格式（dataId=z-team-register, group=DEFAULT_GROUP）：
 * <pre>{@code
 * {
 *   "refreshTime": 1786511341686,
 *   "records": [
 *     {"cliType":"claude-code","cliVersion":"2.1.197","ip":"10.10.129.56",
 *      "port":9098,"available":true,"lastPingTime":1786511341686},
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>保活机制（轮流保活，降低通知频率）：
 * <ul>
 *   <li>每个 proxy 每 1 分钟检查配置最外层 refreshTime</li>
 *   <li>如果 now - refreshTime > 5 分钟，发起保活：更新自己记录的 lastPingTime + refreshTime</li>
 *   <li>如果 <= 5 分钟，跳过（其他 proxy 刚保活过了）</li>
 *   <li>多个 proxy 轮流保活，配置变更频率约 5 分钟一次</li>
 * </ul>
 *
 * <p>并发安全：读取-修改-发布使用 Nacos casMD5 乐观锁，失败自动重试。
 *
 * <p>未指定 --nacos.addr 时跳过注册（裸用不强制要求 Nacos）。
 */
public class NacosRegistryService {

    private static final Logger LOG = Logger.getLogger(NacosRegistryService.class.getName());
    private static final String DEFAULT_GROUP = "DEFAULT_GROUP";
    private static final long CONFIG_TIMEOUT_MS = 5000;
    private static final long KEEPALIVE_CHECK_SEC = 60;          // 每 1 分钟检查一次
    private static final long STALE_THRESHOLD_MS = 5 * 60 * 1000; // 5 分钟未刷新则保活
    private static final int MAX_CAS_RETRIES = 3;

    private static volatile ConfigService configService;
    private static volatile String nacosAddr;
    private static volatile String nacosNamespace;
    private static volatile String registeredDataId;
    private static volatile List<String> registeredIps = new ArrayList<String>();
    private static volatile ScheduledExecutorService keepaliveScheduler;

    // ======================== 注册 / 注销 ========================

    /**
     * 注册到 Nacos 配置中心。
     */
    public static boolean register(String addr, String namespace, String dataId,
                                   int port, List<CliDiscoveryService.DiscoveredCli> cliInfos,
                                   String customIp) {
        if (addr == null || addr.trim().isEmpty()) {
            LOG.info("Nacos addr not specified, skipping registration");
            return false;
        }
        if (dataId == null || dataId.trim().isEmpty()) {
            dataId = "z-team-register";
        }

        try {
            Properties properties = new Properties();
            properties.put(PropertyKeyConst.SERVER_ADDR, addr);
            properties.put(PropertyKeyConst.IS_USE_CLOUD_NAMESPACE_PARSING, false);
            if (namespace != null && !namespace.trim().isEmpty()) {
                properties.put(PropertyKeyConst.NAMESPACE, namespace);
            }
            configService = NacosFactory.createConfigService(properties);
            nacosAddr = addr;
            nacosNamespace = namespace != null ? namespace : "";
            registeredDataId = dataId;

            // 获取本机所有可用 IP
            List<String> ips = new ArrayList<String>();
            if (customIp != null && !customIp.trim().isEmpty()) {
                for (String s : customIp.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        ips.add(trimmed);
                    }
                }
            } else {
                ips = getLocalIps();
            }
            if (ips.isEmpty()) {
                ips.add("127.0.0.1");
            }
            registeredIps = new ArrayList<String>(ips);

            // 构建本机的注册记录
            final JsonArray myRecords = new JsonArray();
            final long now = System.currentTimeMillis();
            for (CliDiscoveryService.DiscoveredCli cli : cliInfos) {
                if (!cli.available) {
                    continue;
                }
                for (String ip : ips) {
                    JsonObject record = new JsonObject();
                    record.put("cliType", cli.type);
                    record.put("cliVersion", cli.version != null ? cli.version : "unknown");
                    record.put("ip", ip);
                    record.put("port", port);
                    record.put("available", true);
                    record.put("lastPingTime", now);
                    myRecords.add(record);
                }
            }

            // CAS 更新：移除本机旧记录 → 追加新记录 → 更新 refreshTime
            final List<String> finalIps = ips;
            boolean success = casUpdate(dataId, new ConfigModifier() {
                public JsonObject modify(JsonObject existing) {
                    JsonArray records = existing.getJsonArray("records");
                    if (records == null) {
                        records = new JsonArray();
                    }
                    // 移除本机旧记录
                    JsonArray filtered = new JsonArray();
                    for (int i = 0; i < records.size(); i++) {
                        JsonObject r = records.getJsonObject(i);
                        if (!finalIps.contains(r.getString("ip"))) {
                            filtered.add(r);
                        }
                    }
                    // 追加新记录
                    for (int i = 0; i < myRecords.size(); i++) {
                        filtered.add(myRecords.getJsonObject(i));
                    }
                    existing.put("records", filtered);
                    existing.put("refreshTime", now);
                    return existing;
                }
            });

            LOG.info("Registered to Nacos config: addr=" + addr + " dataId=" + dataId
                    + " ips=" + ips + " port=" + port
                    + " records=" + myRecords.size() + " success=" + success);

            if (success) {
                startKeepalive();
            }
            return success;

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to register to Nacos: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从 Nacos 配置中移除本机记录。
     */
    public static void deregister() {
        if (configService == null || registeredDataId == null) {
            return;
        }
        stopKeepalive();
        try {
            final long now = System.currentTimeMillis();
            casUpdate(registeredDataId, new ConfigModifier() {
                public JsonObject modify(JsonObject existing) {
                    JsonArray records = existing.getJsonArray("records");
                    if (records == null) {
                        return null;
                    }
                    JsonArray filtered = new JsonArray();
                    for (int i = 0; i < records.size(); i++) {
                        JsonObject r = records.getJsonObject(i);
                        if (!registeredIps.contains(r.getString("ip"))) {
                            filtered.add(r);
                        }
                    }
                    existing.put("records", filtered);
                    existing.put("refreshTime", now);
                    return existing;
                }
            });
            LOG.info("Deregistered from Nacos config: dataId=" + registeredDataId
                    + " removedIps=" + registeredIps);
            configService.shutDown();
            LOG.info("Nacos ConfigService shut down");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deregister from Nacos: " + e.getMessage(), e);
        }
    }

    // ======================== 轮流保活 ========================

    /**
     * 保活检查：每 1 分钟执行。
     * 读取配置 refreshTime，如果距今超过 5 分钟，发起保活（更新自己记录 + refreshTime）。
     */
    private static void keepaliveCheck() {
        if (configService == null || registeredDataId == null) {
            return;
        }
        try {
            // 先读取配置检查 refreshTime，避免不必要的 CAS 写入
            String existing = configService.getConfig(registeredDataId, DEFAULT_GROUP, CONFIG_TIMEOUT_MS);
            if (existing == null || existing.trim().isEmpty()) {
                return;
            }
            JsonObject config = JsonUtil.parseObject(existing);
            Long refreshTime = config.getLong("refreshTime");
            long now = System.currentTimeMillis();
            if (refreshTime != null && (now - refreshTime) < STALE_THRESHOLD_MS) {
                return; // 其他 proxy 刚保活过了，跳过
            }

            // 需要保活：更新自己记录的 lastPingTime + refreshTime
            final long pingTime = now;
            boolean success = casUpdate(registeredDataId, new ConfigModifier() {
                public JsonObject modify(JsonObject config) {
                    // CAS 重试后重新检查 refreshTime（可能其他 proxy 刚保活过）
                    Long rt = config.getLong("refreshTime");
                    if (rt != null && (pingTime - rt) < STALE_THRESHOLD_MS) {
                        return null; // 其他 proxy 已保活，跳过
                    }
                    JsonArray records = config.getJsonArray("records");
                    if (records != null) {
                        for (int i = 0; i < records.size(); i++) {
                            JsonObject r = records.getJsonObject(i);
                            if (registeredIps.contains(r.getString("ip"))) {
                                r.put("lastPingTime", pingTime);
                            }
                        }
                    }
                    config.put("refreshTime", pingTime);
                    return config;
                }
            });

            if (success) {
                LOG.info("Keepalive refreshed: refreshTime=" + pingTime);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Keepalive check failed: " + e.getMessage(), e);
        }
    }

    private static void startKeepalive() {
        keepaliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nacos-keepalive");
            t.setDaemon(true);
            return t;
        });
        keepaliveScheduler.scheduleAtFixedRate(NacosRegistryService::keepaliveCheck,
                KEEPALIVE_CHECK_SEC, KEEPALIVE_CHECK_SEC, TimeUnit.SECONDS);
        LOG.info("Keepalive started: checkInterval=" + KEEPALIVE_CHECK_SEC + "s staleThreshold="
                + (STALE_THRESHOLD_MS / 1000) + "s");
    }

    private static void stopKeepalive() {
        if (keepaliveScheduler != null) {
            keepaliveScheduler.shutdownNow();
            keepaliveScheduler = null;
            LOG.info("Keepalive stopped");
        }
    }

    // ======================== CAS 乐观锁 ========================

    /**
     * CAS 更新配置：读取 → 修改 → 带 casMD5 发布，失败自动重试。
     */
    private static boolean casUpdate(String dataId, ConfigModifier modifier) {
        for (int retry = 0; retry < MAX_CAS_RETRIES; retry++) {
            try {
                // 1. 读取当前配置
                String existing = configService.getConfig(dataId, DEFAULT_GROUP, CONFIG_TIMEOUT_MS);
                String casMD5 = (existing == null || existing.trim().isEmpty())
                        ? "" : md5Hex(existing);

                JsonObject config;
                if (existing == null || existing.trim().isEmpty()) {
                    config = new JsonObject();
                    config.put("refreshTime", System.currentTimeMillis());
                    config.put("records", new JsonArray());
                } else {
                    try {
                        config = JsonUtil.parseObject(existing);
                    } catch (Exception e) {
                        // 旧格式（纯数组）或不兼容，重新初始化
                        LOG.warning("Existing config format incompatible, reinitializing");
                        config = new JsonObject();
                        config.put("refreshTime", System.currentTimeMillis());
                        config.put("records", new JsonArray());
                    }
                }

                // 2. 执行修改
                JsonObject modified = modifier.modify(config);
                if (modified == null) {
                    return true; // 无需更新
                }

                // 3. CAS 发布
                String content = JsonUtil.toJsonPretty(modified);
                boolean published = publishConfigCas(dataId, content, casMD5);
                if (published) {
                    return true;
                }

                LOG.warning("CAS conflict (retry " + (retry + 1) + "/" + MAX_CAS_RETRIES
                        + "), someone else updated the config concurrently");
            } catch (Exception e) {
                LOG.log(Level.WARNING, "CAS update failed (retry " + (retry + 1) + ")", e);
            }
        }
        return false;
    }

    /**
     * 通过 Nacos HTTP API 发布配置（带 casMD5 乐观锁）。
     */
    private static boolean publishConfigCas(String dataId, String content, String casMD5) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + nacosAddr + "/nacos/v1/cs/configs");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            StringBuilder params = new StringBuilder();
            params.append("dataId=").append(URLEncoder.encode(dataId, "UTF-8"));
            params.append("&group=").append(DEFAULT_GROUP);
            if (nacosNamespace != null && !nacosNamespace.trim().isEmpty()) {
                params.append("&tenant=").append(URLEncoder.encode(nacosNamespace, "UTF-8"));
            }
            params.append("&content=").append(URLEncoder.encode(content, "UTF-8"));
            params.append("&casMD5=").append(casMD5);

            OutputStream os = conn.getOutputStream();
            os.write(params.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String resp = reader.readLine();
            reader.close();

            if (code == 200 && "true".equalsIgnoreCase(resp != null ? resp.trim() : "")) {
                return true;
            }
            LOG.warning("publishConfigCas response: code=" + code + " body=" + resp);
            return false;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "publishConfigCas failed: " + e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String md5Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ======================== 工具方法 ========================

    private static List<String> getLocalIps() {
        List<String> result = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (addr.isLinkLocalAddress()) {
                        continue;
                    }
                    result.add(ip);
                }
            }
            if (result.isEmpty()) {
                result.add(InetAddress.getLocalHost().getHostAddress());
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to detect local IPs, falling back to localhost", e);
            result.add("127.0.0.1");
        }
        return result;
    }

    /**
     * 配置修改回调接口。
     * 返回修改后的 JsonObject，返回 null 表示无需更新。
     */
    private interface ConfigModifier {
        JsonObject modify(JsonObject existing);
    }
}
