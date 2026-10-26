package com.zifang.z.team.cliagent.common;

/**
 * CLI Agent 统一 error_code。
 */
public final class CliAgentErrorCodes {

    /** 参数错误。 */
    public static final String PARAM_INVALID = "PARAM_INVALID";
    /** 内嵌 agent-proxy 不可用。 */
    public static final String PROXY_UNAVAILABLE = "PROXY_UNAVAILABLE";
    /** CLI 调用超时。 */
    public static final String CLI_TIMEOUT = "CLI_TIMEOUT";
    /** 会话不存在或无效。 */
    public static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
    /** 有界线程池拒绝。 */
    public static final String QUEUE_REJECTED = "QUEUE_REJECTED";
    /** 资源不存在：仓库目录 / 异步任务。 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 状态冲突：目标目录已存在。 */
    public static final String CONFLICT = "CONFLICT";
    /** 功能关闭：如 repos 运维未启用。 */
    public static final String DISABLED = "DISABLED";
    /** 依赖不可用：cli-agent 自身或磁盘 Git 运维失败。 */
    public static final String CLI_UNAVAILABLE = "CLI_UNAVAILABLE";

    private CliAgentErrorCodes() {
    }
}
