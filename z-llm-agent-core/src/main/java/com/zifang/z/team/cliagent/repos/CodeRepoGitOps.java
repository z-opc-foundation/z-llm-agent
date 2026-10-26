package com.zifang.z.team.cliagent.repos;

import com.zifang.z.team.cliagent.config.CliAgentProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * repos1 Git 运维（JGit，无容器 git 命令依赖）。
 * <p>
 * 凭证：URL userinfo 优先；否则使用 {@code cli-agent.repos.git-username/git-password}
 *（与 z-team.git-clone 默认 ops 账号对齐）。
 */
public class CodeRepoGitOps {

    private static final Pattern SSH_GIT_URL = Pattern.compile("^git@([^:]+):(.+)$");

    private final String gitUsername;
    private final String gitPassword;

    public CodeRepoGitOps(CliAgentProperties properties) {
        CliAgentProperties.Repos repos = properties.getRepos();
        this.gitUsername = repos.getGitUsername();
        this.gitPassword = repos.getGitPassword();
    }

    public static class ExecResult {
        private final int exitCode;
        private final String output;

        public ExecResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public boolean isOk() {
            return exitCode == 0;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }
    }

    public ExecResult clone(String gitUrl, String branch, File dir, long timeoutMinutes) {
        String normalizedUrl = normalizeGitUrl(gitUrl);
        String b = branch != null && !branch.trim().isEmpty() ? branch.trim() : "master";
        int timeoutSec = (int) Math.min(timeoutMinutes * 60L, 3600L);
        CredentialsProvider cp = credentialsOf(normalizedUrl);
        try {
            try (Git ignored = Git.cloneRepository()
                    .setURI(normalizedUrl)
                    .setDirectory(dir)
                    .setBranch(b)
                    .setTimeout(timeoutSec)
                    .setCredentialsProvider(cp)
                    .call()) {
                return new ExecResult(0, "");
            }
        } catch (Exception e) {
            return new ExecResult(-1, "clone failed: " + e.getMessage());
        }
    }

    /**
     * fetch origin 并 hard reset 到 origin/&lt;当前分支&gt;。
     */
    public ExecResult pull(File repoDir) {
        Git git = null;
        try {
            git = Git.open(repoDir);
            String branch = currentBranch(git);
            if (branch == null || branch.trim().isEmpty()) {
                return new ExecResult(-1, "detached HEAD, cannot pull");
            }
            String originUrl = git.getRepository().getConfig().getString("remote", "origin", "url");
            CredentialsProvider cp = credentialsOf(normalizeGitUrl(originUrl));
            git.fetch().setRemote("origin").setForceUpdate(true)
                    .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                    .setTagOpt(TagOpt.FETCH_TAGS)
                    .setCredentialsProvider(cp)
                    .setTimeout(600).call();
            Ref remote = git.getRepository().exactRef("refs/remotes/origin/" + branch);
            if (remote == null || remote.getObjectId() == null) {
                return new ExecResult(-1, "origin/" + branch + " not found after fetch");
            }
            boolean localExists = git.getRepository().exactRef("refs/heads/" + branch) != null;
            if (localExists) {
                git.checkout().setName(branch).setForce(true).call();
            } else {
                git.checkout().setName(branch).setCreateBranch(true)
                        .setStartPoint("refs/remotes/origin/" + branch).call();
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remote.getObjectId().name()).call();
            git.clean().setCleanDirectories(true).setIgnore(false).call();
            return new ExecResult(0, "");
        } catch (Exception e) {
            return new ExecResult(-1, "pull failed: " + e.getMessage());
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    static String currentBranch(Git git) throws Exception {
        Ref head = git.getRepository().exactRef("HEAD");
        if (head == null || !head.isSymbolic()) {
            return null;
        }
        String full = head.getTarget().getName();
        if (full != null && full.startsWith("refs/heads/")) {
            return full.substring("refs/heads/".length());
        }
        return null;
    }

    static String normalizeGitUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        Matcher ssh = SSH_GIT_URL.matcher(trimmed);
        if (ssh.matches()) {
            String host = ssh.group(1);
            String path = ssh.group(2).replace('\\', '/');
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            return "https://" + host + path;
        }
        return trimmed;
    }

    private CredentialsProvider credentialsOf(String url) {
        CredentialsProvider fromUrl = credentialsFromUrlUserInfo(url);
        if (fromUrl != null) {
            return fromUrl;
        }
        if (gitUsername != null && !gitUsername.isEmpty()
                && gitPassword != null && !gitPassword.isEmpty()) {
            return new UsernamePasswordCredentialsProvider(gitUsername, gitPassword);
        }
        return null;
    }

    private static CredentialsProvider credentialsFromUrlUserInfo(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            URI u = new URI(url.replace(" ", "%20"));
            String userInfo = u.getUserInfo();
            if (userInfo != null) {
                int c = userInfo.indexOf(':');
                if (c > 0) {
                    return new UsernamePasswordCredentialsProvider(
                            URLDecoder.decode(userInfo.substring(0, c), "UTF-8"),
                            URLDecoder.decode(userInfo.substring(c + 1), "UTF-8"));
                }
            }
        } catch (Exception ignore) {
            // fall through to configured credentials
        }
        return null;
    }
}
