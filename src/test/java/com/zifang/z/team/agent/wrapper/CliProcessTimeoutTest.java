package com.zifang.z.team.agent.wrapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliProcessTimeoutTest {

    @AfterEach
    void restoreDefault() {
        CliProcessTimeout.setTimeoutSeconds(600);
    }

    @Test
    void defaultTimeoutIs600Seconds() {
        CliProcessTimeout.setTimeoutSeconds(600);
        assertEquals(600, CliProcessTimeout.timeoutSeconds());
        assertEquals(600_000L, CliProcessTimeout.timeoutMs());
    }

    @Test
    void setTimeoutSecondsUpdatesMs() {
        CliProcessTimeout.setTimeoutSeconds(2400);
        assertEquals(2400, CliProcessTimeout.timeoutSeconds());
        assertEquals(2_400_000L, CliProcessTimeout.timeoutMs());
    }

    @Test
    void nonPositiveSecondsFallsBackToDefault() {
        CliProcessTimeout.setTimeoutSeconds(0);
        assertEquals(600, CliProcessTimeout.timeoutSeconds());
    }
}
