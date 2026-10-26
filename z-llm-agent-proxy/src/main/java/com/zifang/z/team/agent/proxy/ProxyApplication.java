package com.zifang.z.team.agent.proxy;

import com.zifang.z.team.agent.runtime.CliAgentRegistry;
import com.zifang.z.team.agent.wrapper.*;

import java.util.List;
import java.util.logging.Logger;

/**
 * Proxy 独立启动入口（裸 proxy）。
 *
 * <p>启动流程：
 * <ol>
 *   <li>解析 --port（默认 9098）</li>
 *   <li>创建 CliAgentRegistry，加载 ~/.z-team/cli-agents.json</li>
 *   <li>CliDiscoveryService.discoverAndRegister() — 扫描 PATH 发现本地 CLI，
 *       新发现的注册到注册表（写入 cli-agents.json），路径变了的更新</li>
 *   <li>创建 CliAgentWrapperRegistry（注册三种 wrapper）</li>
 *   <li>创建 CliChatExecutor 并注入 wrapperRegistry</li>
 *   <li>启动 ProxyHttpServer</li>
 * </ol>
 *
 * <p>用法：
 * <pre>{@code
 * # 脸启动（不注册 Nacos）
 * java -jar z-llm-agent-proxy-*.jar --port=9098
 *
 * # 启动并注册到 Nacos
 * java -jar z-llm-agent-proxy-*.jar --port=9098 --nacos.addr=127.0.0.1:8848
 * }</pre>
 */
public class ProxyApplication {

    private static final Logger LOG = Logger.getLogger(ProxyApplication.class.getName());
    private static final int DEFAULT_PORT = 9098;

    public static void main(String[] args) {
        // 1. 解析 --port
        int port = parsePort(args, DEFAULT_PORT);
        LOG.info("Starting z-llm-agent-proxy on port " + port);

        // 2. 创建 CliAgentRegistry，加载已注册的 CLI agents
        CliAgentRegistry cliRegistry = new CliAgentRegistry("~/.z-team");
        LOG.info("CliAgentRegistry loaded: " + cliRegistry.listConfigs().size() + " agents");

        // 3. 自动扫描本地 CLI 并注册到 cliRegistry（持久化到 ~/.z-team/cli-agents.json）
        List<CliDiscoveryService.DiscoveredCli> discoveredClis = CliDiscoveryService.discoverAndRegister(cliRegistry);
        LOG.info("After discovery: " + cliRegistry.listConfigs().size() + " agents registered");

        // 4. 创建 CliAgentWrapperRegistry（注册三种 wrapper）
        CliAgentWrapperRegistry wrapperRegistry = new CliAgentWrapperRegistry(
                new ClaudeCodeWrapper(),
                new HermesProcessWrapper(),
                new OpencodeProcessWrapper(),
                new QoderProcessWrapper()
        );

        // 5. 创建 CliChatExecutor 并注入 wrapperRegistry
        CliChatExecutor cliExecutor = new CliChatExecutor();
        cliExecutor.setRegistry(wrapperRegistry);

        // 6. 创建 SessionRegistry（持久化 session→cliType 绑定关系）
        String sessionDir = parseArg(args, "--session.dir", "~/.z-team");
        SessionRegistry sessionRegistry = new SessionRegistry(sessionDir);

        // 7. 启动 HTTP 服务器
        int actualPort = ProxyHttpServer.start(port, cliRegistry, cliExecutor, sessionRegistry);

        // 8. 将本机 CLI 信息注册到 Nacos 配置中心（可选）
        String nacosAddr = parseArg(args, "--nacos.addr", null);
        String nacosNamespace = parseArg(args, "--nacos.namespace", "application");
        String nacosDataId = parseArg(args, "--nacos.data.id", "z-team-register");
        String nacosIp = parseArg(args, "--nacos.ip", null);
        NacosRegistryService.register(nacosAddr, nacosNamespace, nacosDataId,
                actualPort, discoveredClis, nacosIp);

        // 9. 注册 shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down z-llm-agent-proxy...");
            NacosRegistryService.deregister();
            ProxyHttpServer.stop();
        }));

        LOG.info("z-llm-agent-proxy ready on http://127.0.0.1:" + actualPort);
        LOG.info("  GET  /v1/cli-agents      — list discovered CLI agents");
        LOG.info("  POST /v1/session/open     — create session (binds cliType), returns sessionId");
        LOG.info("  POST /v1/chat/completions — chat (SSE/JSON, OpenAI compatible)");
        LOG.info("  GET  /health              — health check");
    }

    /**
     * 从命令行参数解析 --port。
     * 支持 --port=9098 和 --port 9098 两种格式。
     */
    private static int parsePort(String[] args, int defaultPort) {
        String val = parseArg(args, "--port", null);
        return val != null ? Integer.parseInt(val) : defaultPort;
    }

    /**
     * 通用参数解析：支持 --key=value 和 --key value 两种格式。
     */
    private static String parseArg(String[] args, String key, String defaultValue) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith(key + "=")) {
                return arg.substring(key.length() + 1);
            }
            if (arg.equals(key) && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

}
