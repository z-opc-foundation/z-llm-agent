package com.zifang.z.team.agent.wrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI 命令定位与 Windows 启动适配（CLI wrapper 共用）。
 *
 * <p>Windows 上裸命令名直接交给 ProcessBuilder 会 {@code CreateProcess error=2}：
 * CreateProcess 不会补全 npm 全局包的 {@code .cmd} shim，且不能直接执行
 * .cmd/.bat 批处理（须经 {@code cmd.exe /c} 启动）。macOS/Linux 维持
 * Unix 目录搜索逻辑（GUI 进程 PATH 精简，需补常见 bin 目录）。
 */
public final class CliCommandLocator {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    private CliCommandLocator() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    /**
     * 在 PATH 与常见 bin 目录中定位命令，返回绝对路径；找不到返回 null。
     */
    public static String locate(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        String home = System.getProperty("user.home");
        List<String> dirs = new ArrayList<String>();
        if (WINDOWS) {
            // npm 全局 shim 目录（PATH 里通常有，GUI 启动的进程可能缺失，显式补上）
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.trim().isEmpty()) {
                dirs.add(appData.trim() + File.separator + "npm");
            }
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                for (String dir : pathEnv.split(";")) {
                    if (!dir.trim().isEmpty()) {
                        dirs.add(dir.trim());
                    }
                }
            }
            String[] suffixes = {"", ".exe", ".cmd", ".bat"};
            for (String dir : dirs) {
                for (String suffix : suffixes) {
                    File file = new File(dir, command + suffix);
                    if (file.isFile()) {
                        return file.getAbsolutePath();
                    }
                }
            }
            return null;
        }
        // macOS/Linux：GUI 进程 PATH 精简，补常见 bin 目录
        dirs.add(home + "/.local/bin");
        dirs.add("/usr/local/bin");
        dirs.add("/opt/homebrew/bin");
        dirs.add("/usr/bin");
        dirs.add("/bin");
        dirs.add(home + "/.hermes/node/bin");
        File nvmNodeDir = new File(home + "/.nvm/versions/node");
        if (nvmNodeDir.isDirectory()) {
            File[] versions = nvmNodeDir.listFiles();
            if (versions != null) {
                for (File v : versions) {
                    dirs.add(v.getAbsolutePath() + "/bin");
                }
            }
        }
        for (String dir : dirs) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Windows 下 .cmd/.bat（及未定位到的裸命令名）须经 {@code cmd.exe /c} 启动，
     * cmd 自身会按 PATHEXT 补全；.exe 直接执行。非 Windows 原样返回。
     */
    public static List<String> wrapForLaunch(List<String> command) {
        if (!WINDOWS || command == null || command.isEmpty()) {
            return command;
        }
        String exe = command.get(0).toLowerCase();
        if (exe.endsWith(".exe")) {
            return command;
        }
        List<String> wrapped = new ArrayList<String>();
        wrapped.add("cmd.exe");
        wrapped.add("/c");
        wrapped.addAll(command);
        return wrapped;
    }
}
