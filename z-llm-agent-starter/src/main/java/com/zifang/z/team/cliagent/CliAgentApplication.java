package com.zifang.z.team.cliagent;

import com.zifang.z.team.cliagent.config.CliAgentAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * CLI Agent 独立进程入口。
 */
@SpringBootApplication
@Import(CliAgentAutoConfiguration.class)
public class CliAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CliAgentApplication.class, args);
    }
}
