package com.zifang.z.team.cliagent.common;

/**
 * cli-agent API 统一响应包装：{@code Result{success, code, message, data}}。
 *
 * <p>贴合 z-team 技术栈的最小响应契约（统一 z-opc Result）：
 * success=true 且 code=OK 表示业务成功；失败时 code 携带错误码，
 * message 携带人话描述。客户端 {@code RemoteCliAgentClient} 按
 * success/data/message 键解包。
 */
public class CliAgentResult<T> {

    public static final String OK = "OK";
    public static final String FAIL = "FAIL";
    public static final String PARAM_INVALID = "PARAM_INVALID";

    private boolean success;
    private String code;
    private String message;
    private T data;

    public static <T> CliAgentResult<T> success(T data) {
        CliAgentResult<T> r = new CliAgentResult<T>();
        r.success = true;
        r.code = OK;
        r.data = data;
        return r;
    }

    public static <T> CliAgentResult<T> error(String code, String message) {
        CliAgentResult<T> r = new CliAgentResult<T>();
        r.success = false;
        r.code = code != null ? code : FAIL;
        r.message = message;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
