package com.zifang.z.team.cliagent.config;

import com.zifang.z.team.cliagent.core.CliAgentService;
import com.zifang.z.team.cliagent.stream.CliStreamProcessorRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class CliAgentCoreConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CliStreamProcessorRegistry cliStreamProcessorRegistry() {
        return CliStreamProcessorRegistry.createDefault();
    }

    @Bean
    @ConditionalOnMissingBean
    public CliAgentService cliAgentService(CliAgentProperties properties,
                                           @Qualifier("cliAgentExecutor") ThreadPoolExecutor cliAgentExecutor,
                                           CliStreamProcessorRegistry cliStreamProcessorRegistry) {
        return new CliAgentService(properties, cliAgentExecutor, cliStreamProcessorRegistry);
    }
}
