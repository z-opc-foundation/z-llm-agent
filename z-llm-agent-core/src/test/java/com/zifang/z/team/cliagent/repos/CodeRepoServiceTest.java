package com.zifang.z.team.cliagent.repos;

import com.zifang.z.team.cliagent.common.CliAgentErrorCodes;
import com.zifang.z.team.cliagent.config.CliAgentProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class CodeRepoServiceTest {

    @TempDir
    Path tempDir;

    private CliAgentProperties properties;
    private CodeRepoService service;

    @BeforeEach
    void setUp() {
        properties = new CliAgentProperties();
        properties.getRepos().setRoot(tempDir.toString());
        service = new CodeRepoService(properties, new CodeRepoGitOps(properties));
    }

    @Test
    void listRepos_detectsGitDirectory() throws Exception {
        Path repoDir = tempDir.resolve("demo-repo");
        Files.createDirectories(repoDir.resolve(".git"));
        Files.createDirectories(tempDir.resolve("plain-dir"));

        Map<String, Object> out = service.listRepos(1, 20, "", "summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repos = (List<Map<String, Object>>) out.get("repos");
        Assertions.assertEquals(2, out.get("total"));
        Assertions.assertEquals(2, repos.size());
        Assertions.assertEquals("summary", out.get("detail"));
        Map<String, Object> gitRepo = null;
        for (int i = 0; i < repos.size(); i++) {
            if ("demo-repo".equals(repos.get(i).get("folder_name"))) {
                gitRepo = repos.get(i);
                break;
            }
        }
        Assertions.assertNotNull(gitRepo);
        Assertions.assertEquals(true, gitRepo.get("is_git"));
    }

    @Test
    void delete_removesChildDirectory() throws Exception {
        Path repoDir = tempDir.resolve("to-delete");
        Files.createDirectories(repoDir);
        Files.write(repoDir.resolve("README"), "x".getBytes());

        Map<String, Object> out = service.delete("to-delete");
        Assertions.assertNull(out.get("error_code"));
        Assertions.assertEquals(true, out.get("deleted"));
        Assertions.assertFalse(Files.exists(repoDir));
    }

    @Test
    void delete_rejectsPathTraversal() {
        Assertions.assertThrows(SecurityException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws Throwable {
                service.delete("../escape");
            }
        });
    }

    @Test
    void resolveChildPath_rejectsInvalidFolderName() {
        Assertions.assertFalse(CodeRepoService.isValidFolderName("bad/name"));
        Assertions.assertFalse(CodeRepoService.isValidFolderName(".."));
    }

    @Test
    void cloneBatchAsync_rejectsEmptyRepos() throws Exception {
        Map<String, Object> out = service.cloneBatchAsync(null, "master", true);
        Assertions.assertEquals(CliAgentErrorCodes.PARAM_INVALID, out.get("error_code"));
    }

    @Test
    void cloneBatchAsync_rejectsMissingGitUrl() throws Exception {
        List<Map<String, Object>> repos = new java.util.ArrayList<Map<String, Object>>();
        repos.add(new java.util.LinkedHashMap<String, Object>());
        Map<String, Object> out = service.cloneBatchAsync(repos, "master", true);
        Assertions.assertEquals(CliAgentErrorCodes.PARAM_INVALID, out.get("error_code"));
    }

    @Test
    void cloneBatchAsync_acceptsValidRequest() throws Exception {
        List<Map<String, Object>> repos = new java.util.ArrayList<Map<String, Object>>();
        Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("git_url", "https://example.com/group/demo.git");
        row.put("folder_name", "demo-batch");
        repos.add(row);
        Map<String, Object> out = service.cloneBatchAsync(repos, "master", true);
        Assertions.assertNull(out.get("error_code"));
        Assertions.assertNotNull(out.get("task_id"));
        Assertions.assertEquals("batch-clone", out.get("action"));
        Assertions.assertEquals(1, out.get("total"));
    }

    @Test
    void listRepos_paginatesAndFiltersKeyword() throws Exception {
        Files.createDirectories(tempDir.resolve("alpha-app"));
        Files.createDirectories(tempDir.resolve("beta-app"));
        Files.createDirectories(tempDir.resolve("other"));

        Map<String, Object> page1 = service.listRepos(1, 1, "", "summary");
        Assertions.assertEquals(3, page1.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page1Repos = (List<Map<String, Object>>) page1.get("repos");
        Assertions.assertEquals(1, page1Repos.size());
        Assertions.assertEquals("alpha-app", page1Repos.get(0).get("folder_name"));

        Map<String, Object> filtered = service.listRepos(1, 20, "beta", "summary");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filteredRepos = (List<Map<String, Object>>) filtered.get("repos");
        Assertions.assertEquals(1, filtered.get("total"));
        Assertions.assertEquals("beta-app", filteredRepos.get(0).get("folder_name"));
    }

    @Test
    void getRepoDetail_returnsSummaryForExistingDirectory() throws Exception {
        Path repoDir = tempDir.resolve("detail-repo");
        Files.createDirectories(repoDir.resolve(".git"));

        Map<String, Object> out = service.getRepoDetail("detail-repo");
        Assertions.assertNull(out.get("error_code"));
        Assertions.assertEquals("detail-repo", out.get("folder_name"));
        Assertions.assertEquals(true, out.get("is_git"));
    }

    @Test
    void getRepoDetail_notFound() throws Exception {
        Map<String, Object> out = service.getRepoDetail("missing-repo");
        Assertions.assertEquals(CliAgentErrorCodes.NOT_FOUND, out.get("error_code"));
    }

    @Test
    void normalizePageSize_capsAtMax() {
        Assertions.assertEquals(20, CodeRepoService.normalizePageSize(0));
        Assertions.assertEquals(100, CodeRepoService.normalizePageSize(500));
    }

    @Test
    void cloneAsync_rejectsExistingDirectory() throws Exception {
        Files.createDirectories(tempDir.resolve("exists"));
        Map<String, Object> out = service.cloneAsync(
                "https://example.com/group/exists.git", "master", "exists");
        Assertions.assertEquals(CliAgentErrorCodes.CONFLICT, out.get("error_code"));
    }

    @Test
    void deriveFolderNameFromUrl_stripsGitSuffix() {
        Assertions.assertEquals("my-app", CodeRepoService.deriveFolderNameFromUrl(
                "https://gitlab.example.com/group/my-app.git"));
    }

    @Test
    void disabledThrows() {
        properties.getRepos().setEnabled(false);
        Assertions.assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws Throwable {
                service.listRepos(1, 20, "", "summary");
            }
        });
    }
}
