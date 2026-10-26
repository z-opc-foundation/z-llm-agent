package com.zifang.z.team.cliagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLI Agent 调用有界线程池（core=1/max=2/queue=8）。
 */
@Configuration
public class CliAgentThreadPoolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CliAgentThreadPoolConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "cliAgentExecutor")
    public ThreadPoolExecutor cliAgentExecutor(CliAgentProperties properties) {
        CliAgentProperties.Worker worker = properties.getWorker();
        int core = worker.getAgentCore() < 1 ? 1 : worker.getAgentCore();
        int max = worker.getAgentMax() < core ? core : worker.getAgentMax();
        int queue = worker.getAgentQueue() < 1 ? 8 : worker.getAgentQueue();
        ThreadPoolExecutor executor = namedPool("cli-agent-", core, max, queue);
        log.info("cli-agent executor core={} max={} queue={}", core, max, queue);
        return executor;
    }

    static ThreadPoolExecutor namedPool(final String prefix, int core, int max, int queue) {
        final AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + seq.getAndIncrement());
                t.setDaemon(true);
                t.setContextClassLoader(CliAgentThreadPoolConfiguration.class.getClassLoader());
                return t;
            }
        };
        return new ThreadPoolExecutor(core, max, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(queue),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
