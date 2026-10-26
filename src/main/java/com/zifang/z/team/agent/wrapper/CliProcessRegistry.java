package com.zifang.z.team.agent.wrapper;

import com.zifang.util.json.JsonUtil;
import com.zifang.util.json.model.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CLI 运行中进程注册表：按本地会话 id 跟踪当前回合的 CLI 子进程，
 * 供「停止生成」真正终止 CLI（SIGTERM → 短宽限 → SIGKILL + 清理直接子进程），
 * 而不是只断开 SSE 让 CLI 继续跑完。
 *
 * <p>一个会话同时至多一个运行中回合；重复 register 覆盖旧值，
 * unregister 用 (id, process) 双参匹配防止误删新回合的进程。
 */
public final class CliProcessRegistry {

    private static final Logger LOG = Logger.getLogger(CliProcessRegistry.class.getName());

    private static final ConcurrentMap<String, Process> RUNNING = new ConcurrentHashMap<String, Process>();

    /** 最近被 cancel 的 pid → cancel 时刻（wrapper 区分「用户停止」与「CLI 真报错」，
     *  30s 后过期自清理，防 pid 复用误判）。 */
    private static final ConcurrentMap<Long, Long> CANCELLED = new ConcurrentHashMap<Long, Long>();

    /** cancel 判定窗口：进程死后 exitCode 查询在此窗口内仍识别为「被停止」。 */
    private static final long CANCEL_WINDOW_MS = 30_000L;

    private CliProcessRegistry() {
    }

    public static void register(String localSessionId, Process process) {
        if (localSessionId == null || localSessionId.isEmpty() || process == null) {
            return;
        }
        RUNNING.put(localSessionId, process);
    }

    public static void unregister(String localSessionId, Process process) {
        if (localSessionId == null || process == null) {
            return;
        }
        RUNNING.remove(localSessionId, process);
    }

    public static boolean isRunning(String localSessionId) {
        return localSessionId != null && RUNNING.containsKey(localSessionId);
    }

    /**
     * 终止会话当前运行中的 CLI 进程。
     *
     * @return true = 有进程被终止；false = 该会话当前无运行中进程
     */
    public static boolean cancel(String localSessionId) {
        if (localSessionId == null || localSessionId.isEmpty()) {
            return false;
        }
        final Process process = RUNNING.remove(localSessionId);
        if (process == null) {
            return false;
        }
        final long pid = pidOf(process);
        LOG.info("Cancelling CLI process for session " + localSessionId + " pid=" + pid);
        if (pid > 0) {
            CANCELLED.put(pid, System.currentTimeMillis());
        }
        // 先清直接子进程（趁父进程还活着，PPID 匹配有效；若先 SIGTERM 杀父，
        // 子进程过继给 launchd 后 pkill -P 落空会残留），再给 CLI 自清理机会
        killChildren(pid);
        // SIGTERM 给 CLI 自清理（工具临时文件等）的机会，短宽限后强杀
        process.destroy();
        Thread killer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!process.waitFor(800, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
                // 兜底：SIGTERM 期间 CLI 可能又 spawn 了新的工具子进程
                killChildren(pid);
            }
        }, "cli-cancel-killer");
        killer.setDaemon(true);
        killer.start();
        return true;
    }

    /** 杀直接子进程（Bash 等工具 spawn 的 shell），无匹配时 pkill 非零退出无害。
     *  waitFor 等 pkill 完成扫描再返回：若不等，紧随其后的 destroy() 瞬间杀父，
     *  pkill 尚未扫完进程表时子进程已过继（PPID 失效），会漏杀。 */
    private static void killChildren(long pid) {
        if (pid <= 0) {
            return;
        }
        try {
            Process pk = new ProcessBuilder("/usr/bin/pkill", "-P", String.valueOf(pid)).start();
            pk.waitFor(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.FINE, "pkill children failed for pid " + pid, e);
        }
    }

    /** Java 8 无 Process.pid()：9+ 在 public 类 Process 上反射（声明类是包私有
     *  ProcessImpl 时模块封装会拒 invoke），8 反射 pid 字段兜底。 */
    static long pidOf(Process process) {
        try {
            Method m = Process.class.getMethod("pid");
            Object r = m.invoke(process);
            return (r instanceof Number) ? ((Number) r).longValue() : -1L;
        } catch (Exception e) {
            try {
                Field f = process.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                return ((Number) f.get(process)).longValue();
            } catch (Exception e2) {
                return -1L;
            }
        }
    }

    /**
     * 该 pid 是否在近期被 {@link #cancel} 终止（用户主动停止）。
     * wrapper 用它区分「用户停止」与「CLI 真报错」：被停止时不推错误 SSE、
     * envelope 注入 stopped 标记。30s 后过期自清理，防 pid 复用误判。
     */
    static boolean wasRecentlyCancelled(long pid) {
        if (pid <= 0) {
            return false;
        }
        Long ts = CANCELLED.get(pid);
        if (ts == null) {
            return false;
        }
        if (System.currentTimeMillis() - ts > CANCEL_WINDOW_MS) {
            CANCELLED.remove(pid);
            return false;
        }
        return true;
    }

    /**
     * 回合收尾：被停止的回合在 envelope 注入 {@code stopped:true}，
     * 前端重放时显示「已停止生成」；无 envelope 时回退纯文本。
     */
    public static String finalizeEnvelope(String envelope, String fallbackText, boolean cancelled) {
        if (envelope == null) {
            return fallbackText;
        }
        if (!cancelled) {
            return envelope;
        }
        try {
            JsonObject obj = JsonUtil.parseObject(envelope);
            obj.put("stopped", true);
            return Jsons.compact(obj);
        } catch (Exception e) {
            LOG.log(Level.FINE, "finalizeEnvelope inject stopped failed", e);
            return envelope;
        }
    }
}
