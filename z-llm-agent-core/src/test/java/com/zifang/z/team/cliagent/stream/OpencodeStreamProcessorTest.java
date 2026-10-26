package com.zifang.z.team.cliagent.stream;

import com.zifang.z.team.cliagent.common.CliStreamEvent;
import com.zifang.z.team.cliagent.common.CliStreamEventTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class OpencodeStreamProcessorTest {

    private final OpencodeStreamProcessor processor = new OpencodeStreamProcessor();

    @Test
    public void textOnlyChunk() {
        CliStreamContext ctx = new CliStreamContext("opencode", "s1");
        String payload = "{\"choices\":[{\"delta\":{\"content\":\"line1\\n\"}}]}";
        List<CliStreamEvent> events = processor.processPayload(payload, ctx);
        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(CliStreamEventTypes.TEXT, events.get(0).getType());
        Assertions.assertEquals("line1\n", events.get(0).getDelta());
    }
}
