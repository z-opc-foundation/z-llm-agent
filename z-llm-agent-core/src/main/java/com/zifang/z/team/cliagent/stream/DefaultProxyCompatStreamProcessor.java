package com.zifang.z.team.cliagent.stream;

/**
 * 默认兜底：z_team_event 一律映射为 {@code type=tool}。
 */
public class DefaultProxyCompatStreamProcessor extends AbstractProxyCompatStreamProcessor {

    @Override
    public boolean supports(String cliType) {
        return true;
    }

    @Override
    public int order() {
        return 1000;
    }
}
