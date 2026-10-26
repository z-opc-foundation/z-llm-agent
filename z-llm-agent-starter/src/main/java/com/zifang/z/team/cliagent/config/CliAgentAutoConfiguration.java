package com.zifang.z.team.cliagent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * CLI Agent 模块装配入口。
 */
@Configuration
@EnableConfigurationProperties(CliAgentProperties.class)
@Import({
        CliAgentThreadPoolConfiguration.class,
        CliAgentCoreConfiguration.class,
        CliAgentCodeRepoConfiguration.class,
        EmbeddedProxyBootstrap.class
})
public class CliAgentAutoConfiguration {
}
