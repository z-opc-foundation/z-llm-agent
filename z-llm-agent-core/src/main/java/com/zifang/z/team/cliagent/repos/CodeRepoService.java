package com.zifang.z.team.cliagent.repos;

import com.zifang.z.team.cliagent.common.CliAgentErrorCodes;
import com.zifang.z.team.cliagent.config.CliAgentProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;

/**
 * repos1 一级目录 Git 仓库运维：列表 / clone / pull / delete。
 */
public class CodeRepoService {

    private static final Pattern INVALID_FOLDER = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int FULL_DETAIL_PARALLELISM = 8;

    public enum TaskStatus {
        PENDING, RUNNING, SUCCESS, FAILED
    }

    public static final class TaskRecord {
        private final String taskId;
        private volatile TaskStatus status;
        private volatile String message;
        private final long createdAt;
        private volatile long finishedAt;
        private final String folderName;
        private final String action;

        TaskRecord(String taskId, String action, String folderName) {
            this.taskId = taskId;
            this.action = action;
            this.folderName = folderName;
            this.status = TaskStatus.PENDING;
            this.createdAt = System.currentTimeMillis();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("task_id", taskId);
            out.put("action", action);
            out.put("folder_name", folderName);
            out.put("status", status.name());
            out.put("message", message);
            out.put("created_at", createdAt);
            out.put("finished_at", finishedAt > 0 ? finishedAt : null);
            return out;
        }
    }

    public static final class BatchTaskRecord {
        private static final int MAX_ITEMS = 2000;

        private final String taskId;
        private volatile TaskStatus status;
        private volatile String message;
        private volatile String currentFolder;
        private final long createdAt;
        private volatile long finishedAt;
        private final int total;
        private volatile int current;
        private volatile int ok;
        private volatile int fail;
        private volatile int skip;

        BatchTaskRecord(String taskId, int total) {
            this.taskId = taskId;
            this.total = total;
            this.status = TaskStatus.PENDING;
            this.createdAt = System.currentTimeMillis();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("task_id", taskId);
            out.put("action", "batch-clone");
            out.put("status", status.name());
            out.put("message", message);
            out.put("current_folder", currentFolder);
            out.put("total", total);
            out.put("current", current);
            out.put("ok", ok);
            out.put("fail", fail);
            out.put("skip", skip);
            out.put("created_at", createdAt);
            out.put("finished_at", finishedAt > 0 ? finishedAt : null);
            return out;
        }
    }

    public static final class BatchCloneItem {
        private final String gitUrl;
        private final String folderName;
        private final String branch;

        BatchCloneItem(String gitUrl, String folderName, String branch) {
            this.gitUrl = gitUrl;
            this.folderName = folderName;
            this.branch = branch;
        }
    }

    private final CliAgentProperties properties;
    private final CodeRepoGitOps gitOps;
    private final ExecutorService executor;
    private final ExecutorService listDetailExecutor;
    private final ConcurrentHashMap<String, TaskRecord> tasks = new ConcurrentHashMap<String, TaskRecord>();
    private final ConcurrentHashMap<String, BatchTaskRecord> batchTasks =
            new ConcurrentHashMap<String, BatchTaskRecord>();

    public CodeRepoService(CliAgentProperties properties, CodeRepoGitOps gitOps) {
        this.properties = properties;
        this.gitOps = gitOps;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cli-agent-code-repos");
                t.setDaemon(true);
                return t;
            }
        });
        this.listDetailExecutor = Executors.newFixedThreadPool(FULL_DETAIL_PARALLELISM, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cli-agent-code-repos-list");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public Map<String, Object> listRepos(int page, int pageSize, String keyword, String detail)
            throws IOException {
        ensureEnabled();
        Path root = reposRoot();
        int safePage = page <= 0 ? DEFAULT_PAGE : page;
        int safePageSize = normalizePageSize(pageSize);
        String safeDetail = normalizeDetail(detail);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);

        List<Path> allDirs = listChildDirs(root);
        List<Path> filtered = filterDirsByKeyword(allDirs, kw);
        int total = filtered.size();
        int from = (safePage - 1) * safePageSize;
        List<Path> pageDirs = new ArrayList<Path>();
        if (from < total) {
            int to = Math.min(from + safePageSize, total);
            pageDirs.addAll(filtered.subList(from, to));
        }

        List<Map<String, Object>> items;
        if ("full".equals(safeDetail)) {
            items = buildRepoItemsFull(pageDirs);
        } else {
            items = buildRepoItemsSummary(pageDirs);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("root", root.toString());
        out.put("enabled", properties.getRepos().isEnabled());
        out.put("total", total);
        out.put("page", safePage);
        out.put("page_size", safePageSize);
        out.put("detail", safeDetail);
        out.put("repos", items);
        return out;
    }

    public Map<String, Object> getRepoDetail(String folderName) throws IOException {
        ensureEnabled();
        Path repoDir = resolveChildPath(normalizeFolderName(folderName));
        if (!Files.isDirectory(repoDir)) {
            return error(CliAgentErrorCodes.NOT_FOUND, "directory not found: " + folderName);
        }
        return toRepoItemFull(repoDir);
    }

    public Map<String, Object> getTask(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task != null) {
            return task.toMap();
        }
        BatchTaskRecord batchTask = batchTasks.get(taskId);
        if (batchTask != null) {
            return batchTask.toMap();
        }
        return error(CliAgentErrorCodes.NOT_FOUND, "task not found: " + taskId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> cloneBatchAsync(List<Map<String, Object>> repos, String defaultBranch,
                                                 boolean skipExisting) throws IOException {
        ensureEnabled();
        if (repos == null || repos.isEmpty()) {
            return error(CliAgentErrorCodes.PARAM_INVALID, "repos required");
        }
        if (repos.size() > BatchTaskRecord.MAX_ITEMS) {
            return error(CliAgentErrorCodes.PARAM_INVALID,
                    "repos exceeds max " + BatchTaskRecord.MAX_ITEMS);
        }
        List<BatchCloneItem> items = new ArrayList<BatchCloneItem>();
        for (int i = 0; i < repos.size(); i++) {
            Map<String, Object> row = repos.get(i);
            if (row == null) {
                return error(CliAgentErrorCodes.PARAM_INVALID, "repos[" + i + "] required");
            }
            String gitUrl = readRepoField(row, "git_url", "gitUrl");
            if (gitUrl == null || gitUrl.trim().isEmpty()) {
                return error(CliAgentErrorCodes.PARAM_INVALID, "repos[" + i + "].git_url required");
            }
            String folderName = readRepoField(row, "folder_name", "folderName");
            String folder = resolveFolderName(gitUrl.trim(), folderName);
            if (!isValidFolderName(folder)) {
                return error(CliAgentErrorCodes.PARAM_INVALID, "invalid folder_name: " + folder);
            }
            String branch = readRepoField(row, "branch");
            if (branch == null || branch.trim().isEmpty()) {
                branch = defaultBranch;
            }
            if (branch == null || branch.trim().isEmpty()) {
                branch = "master";
            }
            items.add(new BatchCloneItem(gitUrl.trim(), folder, branch.trim()));
        }
        String taskId = newTaskId();
        BatchTaskRecord task = new BatchTaskRecord(taskId, items.size());
        batchTasks.put(taskId, task);
        final List<BatchCloneItem> queue = items;
        final long timeout = properties.getRepos().getCloneTimeoutMinutes();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                runBatchClone(task, queue, skipExisting, timeout);
            }
        });
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("task_id", taskId);
        out.put("action", "batch-clone");
        out.put("total", items.size());
        out.put("status", task.status.name());
        return out;
    }

    public Map<String, Object> cloneAsync(String gitUrl, String branch, String folderName) throws IOException {
        ensureEnabled();
        if (gitUrl == null || gitUrl.trim().isEmpty()) {
            return error(CliAgentErrorCodes.PARAM_INVALID, "git_url required");
        }
        String folder = resolveFolderName(gitUrl.trim(), folderName);
        if (!isValidFolderName(folder)) {
            return error(CliAgentErrorCodes.PARAM_INVALID, "invalid folder_name: " + folder);
        }
        Path target = resolveChildPath(folder);
        if (Files.exists(target)) {
            return error(CliAgentErrorCodes.CONFLICT, "directory already exists: " + folder);
        }
        String taskId = newTaskId();
        TaskRecord task = new TaskRecord(taskId, "clone", folder);
        tasks.put(taskId, task);
        final String url = gitUrl.trim();
        final String b = branch != null && !branch.trim().isEmpty() ? branch.trim() : "master";
        final long timeout = properties.getRepos().getCloneTimeoutMinutes();
        final File targetFile = target.toFile();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                runClone(task, url, b, targetFile, timeout);
            }
        });
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("task_id", taskId);
        out.put("folder_name", folder);
        out.put("status", task.status.name());
        return out;
    }

    public Map<String, Object> pullAsync(String folderName) throws IOException {
        ensureEnabled();
        Path repoDir = resolveChildPath(normalizeFolderName(folderName));
        if (!Files.isDirectory(repoDir)) {
            return error(CliAgentErrorCodes.NOT_FOUND, "directory not found: " + folderName);
        }
        if (!isGitRepo(repoDir)) {
            return error(CliAgentErrorCodes.PARAM_INVALID, "not a git repository: " + folderName);
        }
        String taskId = newTaskId();
        TaskRecord task = new TaskRecord(taskId, "pull", repoDir.getFileName().toString());
        tasks.put(taskId, task);
        final File repoFile = repoDir.toFile();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                runPull(task, repoFile);
            }
        });
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("task_id", taskId);
        out.put("folder_name", repoDir.getFileName().toString());
        out.put("status", task.status.name());
        return out;
    }

    public Map<String, Object> delete(String folderName) throws IOException {
        ensureEnabled();
        Path repoDir = resolveChildPath(normalizeFolderName(folderName));
        if (!Files.exists(repoDir)) {
            return error(CliAgentErrorCodes.NOT_FOUND, "directory not found: " + folderName);
        }
        deleteRecursively(repoDir);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("folder_name", repoDir.getFileName().toString());
        out.put("deleted", true);
        return out;
    }

    Path resolveChildPath(String folderName) throws IOException {
        Path root = reposRoot();
        String name = normalizeFolderName(folderName);
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new SecurityException("invalid folder_name");
        }
        Path candidate = root.resolve(name).normalize();
        if (!candidate.startsWith(root)) {
            throw new SecurityException("path escapes repos root");
        }
        return candidate;
    }

    static boolean isValidFolderName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String trimmed = name.trim();
        return !INVALID_FOLDER.matcher(trimmed).find()
                && !".".equals(trimmed)
                && !"..".equals(trimmed);
    }

    static String deriveFolderNameFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "repo";
        }
        String u = url.trim().replaceAll("/+$", "");
        if (u.contains("@") && u.contains(":") && !u.contains("://")) {
            int idx = u.lastIndexOf(':');
            u = u.substring(idx + 1);
        }
        int slash = u.lastIndexOf('/');
        String name = slash >= 0 ? u.substring(slash + 1) : u;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isEmpty() ? "repo" : name;
    }

    private void runBatchClone(BatchTaskRecord task, List<BatchCloneItem> items, boolean skipExisting,
                               long timeoutMinutes) {
        task.status = TaskStatus.RUNNING;
        int ok = 0;
        int fail = 0;
        int skip = 0;
        for (int i = 0; i < items.size(); i++) {
            BatchCloneItem item = items.get(i);
            task.current = i + 1;
            task.currentFolder = item.folderName;
            task.message = "processing " + item.folderName + " (" + task.current + "/" + task.total + ")";
            try {
                Path target = resolveChildPath(item.folderName);
                if (Files.exists(target)) {
                    if (skipExisting) {
                        skip++;
                        task.skip = skip;
                        continue;
                    }
                    fail++;
                    task.fail = fail;
                    continue;
                }
                File targetDir = target.toFile();
                File parent = targetDir.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    fail++;
                    task.fail = fail;
                    continue;
                }
                CodeRepoGitOps.ExecResult result = gitOps.clone(
                        item.gitUrl, item.branch, targetDir, timeoutMinutes);
                if (result.isOk()) {
                    ok++;
                    task.ok = ok;
                } else {
                    fail++;
                    task.fail = fail;
                    deleteQuietly(target);
                }
            } catch (Exception e) {
                fail++;
                task.fail = fail;
            }
        }
        task.currentFolder = null;
        task.ok = ok;
        task.fail = fail;
        task.skip = skip;
        if (fail > 0) {
            task.status = TaskStatus.FAILED;
            task.message = "batch clone finished: ok=" + ok + ", fail=" + fail + ", skip=" + skip;
        } else {
            task.status = TaskStatus.SUCCESS;
            task.message = "batch clone finished: ok=" + ok + ", skip=" + skip;
        }
        task.finishedAt = System.currentTimeMillis();
    }

    private void runClone(TaskRecord task, String gitUrl, String branch, File targetDir, long timeoutMinutes) {
        task.status = TaskStatus.RUNNING;
        try {
            if (!targetDir.getParentFile().exists() && !targetDir.getParentFile().mkdirs()) {
                fail(task, "failed to create parent directory");
                return;
            }
            CodeRepoGitOps.ExecResult result = gitOps.clone(gitUrl, branch, targetDir, timeoutMinutes);
            if (result.isOk()) {
                succeed(task, "clone ok");
            } else {
                fail(task, result.getOutput());
                deleteQuietly(targetDir.toPath());
            }
        } catch (Exception e) {
            fail(task, e.getMessage());
            deleteQuietly(targetDir.toPath());
        }
    }

    private void runPull(TaskRecord task, File repoDir) {
        task.status = TaskStatus.RUNNING;
        CodeRepoGitOps.ExecResult result = gitOps.pull(repoDir);
        if (result.isOk()) {
            succeed(task, "pull ok");
        } else {
            fail(task, result.getOutput());
        }
    }

    private void succeed(TaskRecord task, String message) {
        task.status = TaskStatus.SUCCESS;
        task.message = message;
        task.finishedAt = System.currentTimeMillis();
    }

    private void fail(TaskRecord task, String message) {
        task.status = TaskStatus.FAILED;
        task.message = message;
        task.finishedAt = System.currentTimeMillis();
    }

    private List<Path> listChildDirs(Path root) {
        List<Path> dirs = new ArrayList<Path>();
        if (!Files.isDirectory(root)) {
            return dirs;
        }
        File[] children = root.toFile().listFiles();
        if (children == null) {
            return dirs;
        }
        for (int i = 0; i < children.length; i++) {
            if (children[i].isDirectory()) {
                dirs.add(children[i].toPath());
            }
        }
        dirs.sort(new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return a.getFileName().toString().toLowerCase(Locale.ROOT)
                        .compareTo(b.getFileName().toString().toLowerCase(Locale.ROOT));
            }
        });
        return dirs;
    }

    private static List<Path> filterDirsByKeyword(List<Path> dirs, String keyword) {
        if (keyword.isEmpty()) {
            return dirs;
        }
        List<Path> out = new ArrayList<Path>();
        for (int i = 0; i < dirs.size(); i++) {
            String name = dirs.get(i).getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains(keyword)) {
                out.add(dirs.get(i));
            }
        }
        return out;
    }

    private List<Map<String, Object>> buildRepoItemsSummary(List<Path> dirs) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>(dirs.size());
        for (int i = 0; i < dirs.size(); i++) {
            items.add(toRepoItemSummary(dirs.get(i)));
        }
        return items;
    }

    private List<Map<String, Object>> buildRepoItemsFull(List<Path> dirs) throws IOException {
        if (dirs.isEmpty()) {
            return new ArrayList<Map<String, Object>>();
        }
        if (dirs.size() == 1) {
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>(1);
            items.add(toRepoItemFull(dirs.get(0)));
            return items;
        }
        List<Future<Map<String, Object>>> futures = new ArrayList<Future<Map<String, Object>>>(dirs.size());
        for (int i = 0; i < dirs.size(); i++) {
            final Path dir = dirs.get(i);
            futures.add(listDetailExecutor.submit(new Callable<Map<String, Object>>() {
                @Override
                public Map<String, Object> call() {
                    return toRepoItemFull(dir);
                }
            }));
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>(dirs.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                items.add(futures.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while loading repo details", e);
            } catch (ExecutionException e) {
                throw new IOException("failed to load repo details", e.getCause());
            }
        }
        return items;
    }

    private Map<String, Object> toRepoItemSummary(Path dir) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        String name = dir.getFileName().toString();
        item.put("folder_name", name);
        item.put("is_git", isGitRepo(dir));
        item.put("branch", null);
        item.put("head_sha", null);
        item.put("head_sha_short", null);
        item.put("remote_url", null);
        putModifiedAt(item, dir);
        return item;
    }

    private Map<String, Object> toRepoItemFull(Path dir) {
        Map<String, Object> item = toRepoItemSummary(dir);
        if (Boolean.TRUE.equals(item.get("is_git"))) {
            fillGitMetadata(item, dir);
        }
        return item;
    }

    private static void fillGitMetadata(Map<String, Object> item, Path dir) {
        Git git = null;
        try {
            git = Git.open(dir.toFile());
            item.put("branch", CodeRepoGitOps.currentBranch(git));
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head != null) {
                String sha = head.name();
                item.put("head_sha", sha);
                item.put("head_sha_short", sha.length() > 8 ? sha.substring(0, 8) : sha);
            }
            String origin = git.getRepository().getConfig().getString("remote", "origin", "url");
            item.put("remote_url", scrubCredentials(origin));
        } catch (Exception ignore) {
            // 损坏的 .git 仍展示目录名
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    private static void putModifiedAt(Map<String, Object> item, Path dir) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            item.put("modified_at", attrs.lastModifiedTime().toMillis());
            item.put("modified_at_iso", Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()).toString());
        } catch (IOException e) {
            item.put("modified_at", null);
            item.put("modified_at_iso", null);
        }
    }

    static int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    static String normalizeDetail(String detail) {
        if (detail != null && "full".equalsIgnoreCase(detail.trim())) {
            return "full";
        }
        return "summary";
    }

    private static String scrubCredentials(String url) {
        if (url == null || url.trim().isEmpty()) {
            return url;
        }
        return url.replaceAll("://[^@/]+@", "://");
    }

    private static boolean isGitRepo(Path dir) {
        return Files.isDirectory(dir.resolve(".git"));
    }

    private Path reposRoot() throws IOException {
        Path root = Paths.get(properties.getRepos().resolveRoot()).normalize().toAbsolutePath();
        if (!Files.exists(root)) {
            Files.createDirectories(root);
        }
        return root.toRealPath();
    }

    private void ensureEnabled() {
        if (!properties.getRepos().isEnabled()) {
            throw new IllegalStateException(CliAgentErrorCodes.DISABLED);
        }
    }

    private static String resolveFolderName(String gitUrl, String folderName) {
        if (folderName != null && !folderName.trim().isEmpty()) {
            return folderName.trim();
        }
        return deriveFolderNameFromUrl(gitUrl);
    }

    private static String normalizeFolderName(String folderName) {
        if (folderName == null) {
            throw new SecurityException("folder_name required");
        }
        String name = folderName.trim();
        if (name.isEmpty()) {
            throw new SecurityException("folder_name required");
        }
        return name;
    }

    private static String newTaskId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException ignored) {
            // best effort cleanup after failed clone
        }
    }

    private static String readRepoField(Map<String, Object> row, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            Object value = row.get(keys[i]);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("error_code", code);
        out.put("error_message", message);
        return out;
    }
}
