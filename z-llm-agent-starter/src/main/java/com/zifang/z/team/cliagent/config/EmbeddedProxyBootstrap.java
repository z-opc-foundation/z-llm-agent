package com.zifang.z.team.cliagent.config;

import com.zifang.z.team.cliagent.core.EmbeddedProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 在 cli-agent 进程内启动 agent-proxy HTTP 服务。
 */
@Component
@ConditionalOnProperty(prefix = "cli-agent", name = "embed-proxy", havingValue = "true", matchIfMissing = true)
public class EmbeddedProxyBootstrap {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedProxyBootstrap.class);

    private final CliAgentProperties properties;
    private EmbeddedProxyServer proxyServer;

    public EmbeddedProxyBootstrap(CliAgentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            log.info("cli-agent disabled, skip embedded proxy");
            return;
        }
        proxyServer = new EmbeddedProxyServer(
                properties.getSessionDir(),
                properties.getProxyPort(),
                properties.getRunTimeoutSeconds()
        );
        proxyServer.start();
    }

    @PreDestroy
    public void stop() {
        if (proxyServer != null) {
            proxyServer.stop();
        }
    }
}
