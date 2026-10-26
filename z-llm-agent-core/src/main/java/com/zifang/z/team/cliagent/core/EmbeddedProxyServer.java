package com.zifang.z.team.cliagent.core;

import com.zifang.z.team.agent.proxy.CliDiscoveryService;
import com.zifang.z.team.agent.proxy.ProxyHttpServer;
import com.zifang.z.team.agent.proxy.SessionRegistry;
import com.zifang.z.team.agent.runtime.CliAgentRegistry;
import com.zifang.z.team.agent.wrapper.ClaudeCodeWrapper;
import com.zifang.z.team.agent.wrapper.CliAgentWrapperRegistry;
import com.zifang.z.team.agent.wrapper.CliChatExecutor;
import com.zifang.z.team.agent.wrapper.CliProcessTimeout;
import com.zifang.z.team.agent.wrapper.HermesProcessWrapper;
import com.zifang.z.team.agent.wrapper.OpencodeProcessWrapper;
import com.zifang.z.team.agent.wrapper.QoderProcessWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内嵌 agent-proxy HTTP 服务生命周期（参考 bench EmbeddedProxyBootstrap，bench 侧不改动）。
 */
public class EmbeddedProxyServer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedProxyServer.class);

    private final String sessionDir;
    private final int proxyPort;
    private final int runTimeoutSeconds;
    private int actualPort;

    public EmbeddedProxyServer(String sessionDir, int proxyPort, int runTimeoutSeconds) {
        this.sessionDir = sessionDir;
        this.proxyPort = proxyPort;
        this.runTimeoutSeconds = runTimeoutSeconds;
    }

    public void start() {
        CliProcessTimeout.setTimeoutSeconds(runTimeoutSeconds);
        log.info("CLI process timeout set to {}s", runTimeoutSeconds);
        log.info("Starting embedded agent-proxy on port {}", proxyPort);

        CliAgentRegistry cliRegistry = new CliAgentRegistry(sessionDir);
        int registered = CliDiscoveryService.discoverAndRegister(cliRegistry).size();
        log.info("CLI discovery finished, {} agents in registry", registered);

        CliAgentWrapperRegistry wrapperRegistry = new CliAgentWrapperRegistry(
                new ClaudeCodeWrapper(),
                new HermesProcessWrapper(),
                new OpencodeProcessWrapper(),
                new QoderProcessWrapper()
        );
        CliChatExecutor cliExecutor = new CliChatExecutor();
        cliExecutor.setRegistry(wrapperRegistry);

        SessionRegistry sessionRegistry = new SessionRegistry(sessionDir);
        actualPort = ProxyHttpServer.start(proxyPort, cliRegistry, cliExecutor, sessionRegistry);
        log.info("Embedded agent-proxy ready on http://127.0.0.1:{}", actualPort);
    }

    public void stop() {
        log.info("Stopping embedded agent-proxy");
        ProxyHttpServer.stop();
    }

    public int getActualPort() {
        return actualPort;
    }
}
