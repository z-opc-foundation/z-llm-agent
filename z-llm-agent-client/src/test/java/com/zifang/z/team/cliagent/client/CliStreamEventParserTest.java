package com.zifang.z.team.cliagent.client;

import com.zifang.z.team.cliagent.common.CliStreamEvent;
import com.zifang.z.team.cliagent.common.CliStreamEventTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class CliStreamEventParserTest {

    @Test
    public void parseNamedEventBlock() {
        CliStreamEventParser parser = new CliStreamEventParser();
        final List<CliStreamEvent> events = new ArrayList<CliStreamEvent>();
        parser.feedLine("event: text", events::add);
        parser.feedLine("data: {\"type\":\"text\",\"delta\":\"hi\"}", events::add);
        parser.feedLine("", events::add);
        parser.finish(events::add);

        Assertions.assertEquals(1, events.size());
        Assertions.assertEquals(CliStreamEventTypes.TEXT, events.get(0).getType());
        Assertions.assertEquals("hi", events.get(0).getDelta());
    }

    @Test
    public void ignoreDoneMarker() {
        CliStreamEventParser parser = new CliStreamEventParser();
        final List<CliStreamEvent> events = new ArrayList<CliStreamEvent>();
        parser.feedLine("data: [DONE]", events::add);
        parser.feedLine("", events::add);
        parser.finish(events::add);
        Assertions.assertTrue(events.isEmpty());
    }
}
