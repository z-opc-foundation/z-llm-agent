package com.zifang.z.team.cliagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CLI Agent 服务配置（前缀 {@code cli-agent}）。
 */
@ConfigurationProperties(prefix = "cli-agent")
public class CliAgentProperties {

    private boolean enabled = true;
    private boolean embedProxy = true;
    private int proxyPort = 18180;
    private String proxyBaseUrl = "http://127.0.0.1:18180";
    private int runTimeoutSeconds = 600;
    private int httpReadTimeoutSeconds = 600;
    private String sessionDir = "~/.z-team";
    private String defaultCliType = "claude-code";
    private Worker worker = new Worker();
    private Repos repos = new Repos();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEmbedProxy() {
        return embedProxy;
    }

    public void setEmbedProxy(boolean embedProxy) {
        this.embedProxy = embedProxy;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public String getProxyBaseUrl() {
        return proxyBaseUrl;
    }

    public void setProxyBaseUrl(String proxyBaseUrl) {
        this.proxyBaseUrl = proxyBaseUrl;
    }

    public int getRunTimeoutSeconds() {
        return runTimeoutSeconds;
    }

    public void setRunTimeoutSeconds(int runTimeoutSeconds) {
        this.runTimeoutSeconds = runTimeoutSeconds;
    }

    public int getHttpReadTimeoutSeconds() {
        return httpReadTimeoutSeconds;
    }

    public void setHttpReadTimeoutSeconds(int httpReadTimeoutSeconds) {
        this.httpReadTimeoutSeconds = httpReadTimeoutSeconds;
    }

    public String getSessionDir() {
        return sessionDir;
    }

    public void setSessionDir(String sessionDir) {
        this.sessionDir = sessionDir;
    }

    public String getDefaultCliType() {
        return defaultCliType;
    }

    public void setDefaultCliType(String defaultCliType) {
        this.defaultCliType = defaultCliType;
    }

    public Worker getWorker() {
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker == null ? new Worker() : worker;
    }

    public Repos getRepos() {
        return repos;
    }

    public void setRepos(Repos repos) {
        this.repos = repos == null ? new Repos() : repos;
    }

    public static class Worker {

        private int agentCore = 1;
        private int agentMax = 2;
        private int agentQueue = 8;

        public int getAgentCore() {
            return agentCore;
        }

        public void setAgentCore(int agentCore) {
            this.agentCore = agentCore;
        }

        public int getAgentMax() {
            return agentMax;
        }

        public void setAgentMax(int agentMax) {
            this.agentMax = agentMax;
        }

        public int getAgentQueue() {
            return agentQueue;
        }

        public void setAgentQueue(int agentQueue) {
            this.agentQueue = agentQueue;
        }
    }

    /**
     * Zoekt repos1 磁盘运维：clone / pull / 列表（默认 {@code /home/admin/repos1}）。
     */
    public static class Repos {
        /**
         * 与 z-team.git-clone 对齐，Nacos 可覆盖。
         *
         * ⚠ **默认留空（不得回填字面量）**：这两个默认值曾是内部 GitLab ops 账号的字面量，
         * 会随 jar 一起发到 Maven Central —— **中央件不可撤回** ⇒ 清源侧改为空默认，
         * 由 Nacos / 环境变量注入（属性源 env > jar 内 yaml）。
         * 同类处置见 046 卡（z-team-starter 的 jwt-secret / api-key）。
         */
        private static final String DEFAULT_GIT_USERNAME = "";
        private static final String DEFAULT_GIT_PASSWORD = "";

        private boolean enabled = true;
        /** 空则运行时按 HOME 解析 */
        private String root = "";
        private long cloneTimeoutMinutes = 10L;
        private String gitUsername = DEFAULT_GIT_USERNAME;
        private String gitPassword = DEFAULT_GIT_PASSWORD;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRoot() {
            return root;
        }

        public void setRoot(String root) {
            this.root = root == null ? "" : root.trim();
        }

        public long getCloneTimeoutMinutes() {
            return cloneTimeoutMinutes <= 0 ? 10L : cloneTimeoutMinutes;
        }

        public void setCloneTimeoutMinutes(long cloneTimeoutMinutes) {
            this.cloneTimeoutMinutes = cloneTimeoutMinutes;
        }

        public String getGitUsername() {
            return gitUsername;
        }

        public void setGitUsername(String gitUsername) {
            this.gitUsername = gitUsername == null ? "" : gitUsername.trim();
        }

        public String getGitPassword() {
            return gitPassword;
        }

        public void setGitPassword(String gitPassword) {
            this.gitPassword = gitPassword == null ? "" : gitPassword;
        }

        public boolean hasGitCredentials() {
            return gitUsername != null && !gitUsername.isEmpty()
                    && gitPassword != null && !gitPassword.isEmpty();
        }

        public String resolveRoot() {
            if (root != null && !root.isEmpty()) {
                return root;
            }
            String home = System.getenv("HOME");
            if (home == null || home.trim().isEmpty()) {
                home = "/home/admin";
            }
            return home.trim() + "/repos1";
        }
    }
}
