package com.zifang.z.team.cliagent.api;

import com.zifang.z.team.cliagent.common.CliAgentResult;
import com.zifang.z.team.cliagent.common.CliAgentErrorCodes;
import com.zifang.z.team.cliagent.repos.CodeRepoService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * repos1 代码仓库运维：列表 / clone / pull / delete。
 */
@RestController
@RequestMapping("/api/cli-agent/code-repos")
public class CodeRepoController {

    private final CodeRepoService codeRepoService;

    public CodeRepoController(CodeRepoService codeRepoService) {
        this.codeRepoService = codeRepoService;
    }

    @GetMapping
    public CliAgentResult<Map<String, Object>> list(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(name = "page_size", required = false, defaultValue = "20") int pageSize,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "summary") String detail) {
        try {
            return CliAgentResult.success(codeRepoService.listRepos(page, pageSize, keyword, detail));
        } catch (IllegalStateException e) {
            return disabled(e.getMessage());
        } catch (Exception e) {
            return CliAgentResult.success(error(CliAgentErrorCodes.CLI_UNAVAILABLE, e.getMessage()));
        }
    }

    @GetMapping("/tasks/{taskId}")
    public CliAgentResult<Map<String, Object>> task(@PathVariable("taskId") String taskId) {
        return CliAgentResult.success(codeRepoService.getTask(taskId));
    }

    @GetMapping("/{folderName}")
    public CliAgentResult<Map<String, Object>> detail(@PathVariable("folderName") String folderName) {
        try {
            return CliAgentResult.success(codeRepoService.getRepoDetail(folderName));
        } catch (IllegalStateException e) {
            return disabled(e.getMessage());
        } catch (SecurityException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return CliAgentResult.success(error(CliAgentErrorCodes.CLI_UNAVAILABLE, e.getMessage()));
        }
    }

    @PostMapping("/clone/batch")
    public CliAgentResult<Map<String, Object>> cloneBatch(@RequestBody Map<String, Object> body) {
        try {
            List<Map<String, Object>> repos = CliAgentApiSupport.readMapList(body, "repos");
            String branch = CliAgentApiSupport.readString(body, "branch");
            boolean skipExisting = CliAgentApiSupport.readBoolean(body, "skip_existing", true);
            return CliAgentResult.success(codeRepoService.cloneBatchAsync(repos, branch, skipExisting));
        } catch (IllegalStateException e) {
            return disabled(e.getMessage());
        } catch (SecurityException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return CliAgentResult.success(error(CliAgentErrorCodes.PARAM_INVALID, e.getMessage()));
        }
    }

    @PostMapping("/clone")
    public CliAgentResult<Map<String, Object>> clone(@RequestBody Map<String, Object> body) {
        try {
            String gitUrl = CliAgentApiSupport.readString(body, "git_url", "gitUrl");
            String branch = CliAgentApiSupport.readString(body, "branch");
            String folderName = CliAgentApiSupport.readString(body, "folder_name", "folderName");
            return CliAgentResult.success(codeRepoService.cloneAsync(gitUrl, branch, folderName));
        } catch (IllegalStateException e) {
            return disabled(e.getMessage());
        } catch (SecurityException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return CliAgentResult.success(error(CliAgentErrorCodes.PARAM_INVALID, e.getMessage()));
        }
    }

    @PostMapping("/{folderName}/pull")
    public CliAgentResult<Map<String, Object>> pull(@PathVariable("folderName") String folderName) {
        try {
            return CliAgentResult.success(codeRepoService.pullAsync(folderName));
        } catch (IllegalStateException e) {
            return disabled(e.getMessage());
        } catch (SecurityException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return CliAgentResult.success(error(CliAgentErrorCodes.PARAM_INVALID, e.getMessage()));
        }
    }

    @DeleteMapping("/{folderName}")
    public CliAgentResult<Map<String, Object>> delete(@PathVariable("folderName") String folderName) {
        try {
            return CliAgentResult.success(codeRepoService.delete(folderName));
        } catch (IllegalStateException e) {
            return disabled(e.getMessage());
        } catch (SecurityException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return CliAgentResult.success(error(CliAgentErrorCodes.PARAM_INVALID, e.getMessage()));
        }
    }

    private CliAgentResult<Map<String, Object>> disabled(String message) {
        return CliAgentResult.success(error(CliAgentErrorCodes.DISABLED, message));
    }

    private CliAgentResult<Map<String, Object>> badRequest(String message) {
        return CliAgentResult.success(error(CliAgentErrorCodes.PARAM_INVALID, message));
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("error_code", code);
        if (message != null) {
            out.put("error_message", message);
        }
        return out;
    }
}
