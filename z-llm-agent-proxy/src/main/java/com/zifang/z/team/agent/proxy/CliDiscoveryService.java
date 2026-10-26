package com.zifang.z.team.agent.proxy;

import com.zifang.z.team.agent.runtime.CliAgentConfig;
import com.zifang.z.team.agent.runtime.CliAgentRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * CLI 自动发现服务：启动时扫描本地 PATH 及常见 bin 目录，
 * 发现已安装的 CLI 工具（claude / hermes / opencode / qodercli），
 * 并注册到 {@link CliAgentRegistry}（持久化到 ~/.z-team/cli-agents.json）。
 *
 * <p>搜索逻辑复用 {@code ClaudeCodeWrapper.resolveCommandPath()} 的路径列表。
 */
public class CliDiscoveryService {

    private static final Logger LOG = Logger.getLogger(CliDiscoveryService.class.getName());

    /**
     * CLI 命令名 → agent type 映射
     */
    private static final String[][] CLI_MAPPINGS = {
            {"claude", "claude-code"},
            {"hermes", "hermes"},
            {"opencode", "opencode"},
            {"qodercli", "qoder"}
    };

    /**
     * 扫描 PATH 及常见 bin 目录，返回本地已发现的 CLI 列表。
     */
    public static List<DiscoveredCli> discover() {
        List<DiscoveredCli> result = new ArrayList<DiscoveredCli>();
        for (String[] mapping : CLI_MAPPINGS) {
            String command = mapping[0];
            String type = mapping[1];
            String resolvedPath = resolveCommand(command);
            DiscoveredCli cli = new DiscoveredCli();
            cli.type = type;
            cli.command = command;
            cli.resolvedPath = resolvedPath;
            cli.available = resolvedPath != null;
            cli.version = cli.available ? getVersion(resolvedPath) : null;
            result.add(cli);
            if (cli.available) {
                LOG.info("Discovered CLI: " + type + " at " + resolvedPath);
            } else {
                LOG.info("CLI not found: " + command + " (type=" + type + ")");
            }
        }
        return result;
    }

    /**
     * 发现本地 CLI 并注册到 CliAgentRegistry（写入 ~/.z-team/cli-agents.json）。
     *
     * <p>对每个 available 的 CLI：
     * <ul>
     *   <li>注册表中无同 type → register() 注册（写入 cli-agents.json）</li>
     *   <li>有同 type 但 command 路径变了 → remove + register 更新</li>
     *   <li>有同 type 且路径未变 → 跳过</li>
     * </ul>
     */
    public static List<DiscoveredCli> discoverAndRegister(CliAgentRegistry registry) {
        List<DiscoveredCli> discovered = discover();
        for (DiscoveredCli cli : discovered) {
            if (!cli.available) {
                continue;
            }
            CliAgentConfig existing = findByType(registry, cli.type);
            if (existing == null) {
                // 新发现，注册（register 内部调 saveToFile 写入 cli-agents.json）
                registry.register(cli.type, cli.type, cli.command, null, null);
                LOG.info("Registered CLI: " + cli.type + " at " + cli.resolvedPath);
            } else if (!cli.command.equals(existing.getCommand())) {
                // 路径变了（如 nvm 升级），更新注册
                registry.remove(existing.getId());
                registry.register(cli.type, cli.type, cli.command, null, null);
                LOG.info("Updated CLI: " + cli.type + " -> " + cli.resolvedPath);
            } else if (!CliAgentRegistry.defaultWorkingDir(cli.type).equals(existing.getWorkingDir())) {
                // workingDir 过期（如 root 下 user.home=/root 但 HOME=/home/admin），刷新
                registry.remove(existing.getId());
                registry.register(cli.type, cli.type, cli.command, null, null);
                LOG.info("Refreshed CLI workingDir for: " + cli.type);
            } else {
                LOG.info("CLI already registered: " + cli.type + " at " + cli.resolvedPath);
            }
        }
        return discovered;
    }

    /**
     * 在注册表中按 type 查找已注册的 CliAgentConfig。
     */
    private static CliAgentConfig findByType(CliAgentRegistry registry, String type) {
        for (CliAgentConfig config : registry.listConfigs()) {
            if (type.equals(config.getType())) {
                return config;
            }
        }
        return null;
    }

    /**
     * 在 PATH + ~/.local/bin + /usr/local/bin + /opt/homebrew/bin + nvm node/bin 中查找命令。
     *
     * <p>复用 ClaudeCodeWrapper.resolveCommandPath() 的搜索逻辑。
     */
    private static String resolveCommand(String command) {
        if (command == null || command.isEmpty()) {
            return null;
        }

        // Windows 额外查 %APPDATA%\npm 下的 .exe/.cmd/.bat
        String located = com.zifang.z.team.agent.wrapper.CliCommandLocator.locate(command);
        if (located != null) {
            return located;
        }

        String home = System.getProperty("user.home");
        List<String> searchPaths = new ArrayList<String>();
        // 系统 PATH 中的目录
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (!dir.isEmpty() && !searchPaths.contains(dir)) {
                    searchPaths.add(dir);
                }
            }
        }
        // 常见 bin 目录（GUI 进程 PATH 可能不包含这些）
        searchPaths.add(home + "/.local/bin");
        searchPaths.add("/usr/local/bin");
        searchPaths.add("/opt/homebrew/bin");
        searchPaths.add("/opt/homebrew/sbin");
        searchPaths.add("/usr/bin");
        searchPaths.add("/bin");
        // nvm 管理的 node bin 目录（claude 是 npm 全局工具）
        File nvmNodeDir = new File(home + "/.nvm/versions/node");
        if (nvmNodeDir.isDirectory()) {
            File[] versions = nvmNodeDir.listFiles();
            if (versions != null) {
                for (File v : versions) {
                    searchPaths.add(v.getAbsolutePath() + "/bin");
                }
            }
        }

        for (String dir : searchPaths) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    /**
     * 执行 command --version 获取版本号，取第一行输出。
     */
    private static String getVersion(String resolvedPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(resolvedPath, "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            reader.close();
            if (line != null) {
                return line.trim();
            }
        } catch (Exception e) {
            LOG.warning("Failed to get version for " + resolvedPath + ": " + e.getMessage());
        }
        return "unknown";
    }

    /**
     * 发现的 CLI 信息。
     */
    public static class DiscoveredCli {
        public String type;         // "claude-code" / "hermes" / "opencode"
        public String command;      // "claude" / "hermes" / "opencode"
        public String resolvedPath; // 绝对路径，null 表示未找到
        public boolean available;   // 命令是否存在且可执行
        public String version;      // CLI 版本号
    }
}
