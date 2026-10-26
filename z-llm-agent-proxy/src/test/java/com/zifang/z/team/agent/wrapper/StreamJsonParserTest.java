package com.zifang.z.team.agent.wrapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StreamJsonParserTest {

    @Test
    void parseJsonOutput_extractsSuccessResult() {
        String raw = "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"你好\",\"is_error\":false}";
        assertEquals("你好", StreamJsonParser.parseJsonOutput(raw));
    }

    @Test
    void parseJsonOutput_returnsNullForErrorResult() {
        String raw = "{\"type\":\"result\",\"is_error\":true,\"result\":\"401 Unauthorized\"}";
        assertNull(StreamJsonParser.parseJsonOutput(raw));
        assertEquals("401 Unauthorized", StreamJsonParser.extractResultError(raw));
    }
}
