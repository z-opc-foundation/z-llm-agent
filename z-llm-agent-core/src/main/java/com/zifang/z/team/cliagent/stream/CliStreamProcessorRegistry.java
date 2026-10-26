package com.zifang.z.team.cliagent.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 按 cliType 解析 {@link CliStreamProcessor}；无匹配时走默认兜底。
 */
public class CliStreamProcessorRegistry {

    private final List<CliStreamProcessor> processors;
    private final CliStreamProcessor defaultProcessor;

    public CliStreamProcessorRegistry(List<CliStreamProcessor> processors) {
        List<CliStreamProcessor> sorted = new ArrayList<CliStreamProcessor>(processors);
        Collections.sort(sorted, new Comparator<CliStreamProcessor>() {
            @Override
            public int compare(CliStreamProcessor a, CliStreamProcessor b) {
                return Integer.compare(a.order(), b.order());
            }
        });
        this.processors = Collections.unmodifiableList(sorted);
        CliStreamProcessor fallback = null;
        for (CliStreamProcessor processor : sorted) {
            if (processor instanceof DefaultProxyCompatStreamProcessor) {
                fallback = processor;
                break;
            }
        }
        if (fallback == null) {
            fallback = new DefaultProxyCompatStreamProcessor();
        }
        this.defaultProcessor = fallback;
    }

    public CliStreamProcessor resolve(String cliType) {
        if (cliType != null) {
            for (CliStreamProcessor processor : processors) {
                if (processor.supports(cliType)) {
                    return processor;
                }
            }
        }
        return defaultProcessor;
    }

    public static CliStreamProcessorRegistry createDefault() {
        List<CliStreamProcessor> list = new ArrayList<CliStreamProcessor>();
        list.add(new ClaudeCodeStreamProcessor());
        list.add(new QoderStreamProcessor());
        list.add(new OpencodeStreamProcessor());
        list.add(new DefaultProxyCompatStreamProcessor());
        return new CliStreamProcessorRegistry(list);
    }
}
