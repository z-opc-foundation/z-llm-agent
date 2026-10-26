package com.zifang.z.team.agent.wrapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CliProcessRegistry 语义验证：注册/注销双参匹配防误删、cancel 真正终止进程。
 * 用 /bin/sleep 真进程（跨平台稳定，macOS/Linux 均有）代替 mock。
 */
class CliProcessRegistryTest {

    private static Process spawnSleep() throws IOException {
        return new ProcessBuilder("/bin/sleep", "30").start();
    }

    @Test
    void cancelKillsRegisteredProcess() throws Exception {
        String sessionId = "registry-test-cancel-" + System.nanoTime();
        Process process = spawnSleep();
        try {
            CliProcessRegistry.register(sessionId, process);
            assertTrue(CliProcessRegistry.isRunning(sessionId));

            assertTrue(CliProcessRegistry.cancel(sessionId));
            // cancel 后 killer 线程最迟 800ms 强杀；宽限等待进程退出
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "process should die after cancel");
            assertFalse(CliProcessRegistry.isRunning(sessionId));
        } finally {
            process.destroyForcibly();
            CliProcessRegistry.unregister(sessionId, process);
        }
    }

    @Test
    void pidOfReturnsRealPid() throws Exception {
        Process process = spawnSleep();
        try {
            assertTrue(CliProcessRegistry.pidOf(process) > 0,
                    "pidOf should resolve real pid (reflection on public Process class)");
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void wasRecentlyCancelledOnlyAfterCancel() throws Exception {
        String sessionId = "registry-test-cancelflag-" + System.nanoTime();
        Process process = spawnSleep();
        try {
            long pid = CliProcessRegistry.pidOf(process);
            assertFalse(CliProcessRegistry.wasRecentlyCancelled(pid), "not cancelled yet");
            assertFalse(CliProcessRegistry.wasRecentlyCancelled(-1L), "invalid pid never flagged");

            CliProcessRegistry.register(sessionId, process);
            assertTrue(CliProcessRegistry.cancel(sessionId));
            assertTrue(CliProcessRegistry.wasRecentlyCancelled(pid),
                    "pid flagged as cancelled within window");
        } finally {
            process.destroyForcibly();
            CliProcessRegistry.unregister(sessionId, process);
        }
    }

    @Test
    void finalizeEnvelopeInjectsStoppedOnlyWhenCancelled() {
        String envelope = "{\"z_team_blocks\":[{\"type\":\"text\",\"text\":\"hi\"}],\"totalMs\":100}";

        assertEquals(envelope, CliProcessRegistry.finalizeEnvelope(envelope, "fb", false),
                "non-cancelled envelope unchanged");
        assertEquals("fb", CliProcessRegistry.finalizeEnvelope(null, "fb", true),
                "null envelope falls back to text");

        String injected = CliProcessRegistry.finalizeEnvelope(envelope, "fb", true);
        assertTrue(injected.contains("\"stopped\"" + ":true"),
                "cancelled envelope gains stopped flag");
        assertTrue(injected.contains("z_team_blocks"), "original blocks preserved");
    }

    @Test
    void cancelUnknownSessionReturnsFalse() {
        String sessionId = "registry-test-unknown-" + System.nanoTime();
        assertFalse(CliProcessRegistry.cancel(sessionId));
        assertFalse(CliProcessRegistry.isRunning(sessionId));
    }

    @Test
    void unregisterOnlyMatchingProcess() throws Exception {
        String sessionId = "registry-test-unreg-" + System.nanoTime();
        Process first = spawnSleep();
        Process second = spawnSleep();
        try {
            CliProcessRegistry.register(sessionId, first);
            // 新回合进程 unregister 不应误删旧注册（双参匹配）
            CliProcessRegistry.unregister(sessionId, second);
            assertTrue(CliProcessRegistry.isRunning(sessionId));

            CliProcessRegistry.unregister(sessionId, first);
            assertFalse(CliProcessRegistry.isRunning(sessionId));
        } finally {
            first.destroyForcibly();
            second.destroyForcibly();
            CliProcessRegistry.unregister(sessionId, first);
            CliProcessRegistry.unregister(sessionId, second);
        }
    }

    @Test
    void registerIgnoresInvalidArgs() throws Exception {
        Process process = spawnSleep();
        try {
            CliProcessRegistry.register(null, process);
            CliProcessRegistry.register("", process);
            CliProcessRegistry.register("registry-test-nullproc-" + System.nanoTime(), null);
            // 以上均不应进注册表：invalid id cancel 不抛错返回 false
            assertFalse(CliProcessRegistry.cancel(null));
            assertFalse(CliProcessRegistry.cancel(""));
        } finally {
            process.destroyForcibly();
        }
    }
}
