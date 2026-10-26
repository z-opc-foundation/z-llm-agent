package com.zifang.z.team.agent.wrapper;

import com.zifang.z.team.agent.runtime.CliAgentConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliPromptDeliveryTest {

    @Test
    void useStdin_falseForSmallPrompt() {
        assertFalse(CliPromptDelivery.useStdin("hello"));
        assertFalse(CliPromptDelivery.useStdin(null));
        assertFalse(CliPromptDelivery.useStdin(""));
    }

    @Test
    void useStdin_trueWhenExceedsThreshold() {
        char[] chars = new char[CliPromptDelivery.MAX_PROMPT_ARG_BYTES + 1];
        Arrays.fill(chars, 'a');
        assertTrue(CliPromptDelivery.useStdin(new String(chars)));
    }

    @Test
    void omitPromptPlaceholder_removesStandalonePlaceholder() {
        List<String> template = Arrays.asList("-p", "{prompt}", "--output-format", "json");
        assertEquals(Arrays.asList("-p", "--output-format", "json"),
                CliPromptDelivery.omitPromptPlaceholder(template));
    }

    @Test
    void omitPromptPlaceholder_matchesClaudeDefaultArgs() {
        List<String> template = CliAgentConfig.defaultArgs("claude-code");
        List<String> out = CliPromptDelivery.omitPromptPlaceholder(template);
        assertTrue(out.contains("-p"));
        assertFalse(out.contains("{prompt}"));
    }
}
