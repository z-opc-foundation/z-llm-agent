package com.zifang.z.team.cliagent.repos;

import com.zifang.z.team.cliagent.config.CliAgentProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CodeRepoGitOpsTest {

    @Test
    void normalizeGitUrl_convertsSshToHttps() {
        Assertions.assertEquals(
                "https://gitlab.zteam.local/zteam-inpatient.git",
                CodeRepoGitOps.normalizeGitUrl(
                        "git@gitlab.zteam.local:zteam-inpatient.git"));
    }

    @Test
    void repos_不再自带默认Git凭据() {
        // 2026-10-23：默认 GitLab 凭据（ops/明文口令）已按「明文禁令」清掉
        // ⇒ 默认配置**不得**再有可用凭据，凭证只能由部署侧注入。
        CliAgentProperties properties = new CliAgentProperties();
        Assertions.assertFalse(properties.getRepos().hasGitCredentials(),
                "默认配置里不得残留 Git 凭据（明文禁令）");
        Assertions.assertTrue(properties.getRepos().getGitUsername() == null
                        || properties.getRepos().getGitUsername().isEmpty(),
                "默认 Git 用户名必须为空");
    }
}
