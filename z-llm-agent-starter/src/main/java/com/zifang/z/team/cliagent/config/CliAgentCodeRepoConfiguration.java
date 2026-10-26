package com.zifang.z.team.cliagent.config;

import com.zifang.z.team.cliagent.repos.CodeRepoGitOps;
import com.zifang.z.team.cliagent.repos.CodeRepoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CliAgentCodeRepoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CodeRepoGitOps codeRepoGitOps(CliAgentProperties properties) {
        return new CodeRepoGitOps(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public CodeRepoService codeRepoService(CliAgentProperties properties, CodeRepoGitOps gitOps) {
        return new CodeRepoService(properties, gitOps);
    }
}
