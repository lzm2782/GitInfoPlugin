package com.hushan.gitinfoplugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * GitInfoPlugin — 将 Git 分支/提交/打包人/versionName 写入 assets/git_info.json
 *
 * 使用方式（app/build.gradle）：
 *   plugins {
 *       id 'com.hushan.gitinfo'
 *   }
 *
 * 插件自动注册 generateGitInfoToAssets 任务，并挂到 preBuild 之前。
 */
public class GitInfoPlugin implements Plugin<Project> {

    @Override
    public void apply(Project targetProject) {
        Task gitInfoTask = targetProject.getTasks().register("generateGitInfoToAssets", task -> {
            task.getOutputs().upToDateWhen(t -> false); // 每次编译必执行

            task.doLast(t -> {
                Project p = t.getProject();
                String buildTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                String branch = "unknown";
                String builder = "unknown";
                String commitCount = "0";
                String gitLogs = "[]";

                String gradleTaskNames = String.join(",", p.getGradle().getStartParameter().getTaskNames());
                String buildEnv = (gradleTaskNames.contains("Release") || gradleTaskNames.contains("release"))
                        ? "Release" : "Debug";

                String versionName = "unknown";
                try {
                    Object androidExt = p.getExtensions().findByName("android");
                    if (androidExt != null) {
                        Object defaultConfig = androidExt.getClass().getMethod("getDefaultConfig").invoke(androidExt);
                        Object vn = defaultConfig.getClass().getMethod("getVersionName").invoke(defaultConfig);
                        Object vns = defaultConfig.getClass().getMethod("getVersionNameSuffix").invoke(defaultConfig);
                        versionName = (vn != null ? vn.toString() : "") + (vns != null ? vns.toString() : "");
                    }
                } catch (Exception e) {
                    System.out.println("【获取 versionName 异常】: " + e.getMessage());
                }

                File rootDir = p.getRootProject().getProjectDir();
                System.out.println("================ 开始抓取 Git 详细信息 ================");

                try {
                    branch      = execCmd(p, rootDir, "git", "rev-parse", "--abbrev-ref", "HEAD");
                    commitCount = execCmd(p, rootDir, "git", "rev-list", "--count", "HEAD");

                    try {
                        String gitUser = execCmd(p, rootDir, "git", "config", "user.name");
                        builder = (gitUser != null && !gitUser.isEmpty())
                                ? gitUser : System.getProperty("user.name");
                    } catch (Exception e) {
                        builder = System.getProperty("user.name");
                    }

                    int totalCommits = 0;
                    try { totalCommits = Integer.parseInt(commitCount); } catch (Exception ignore) {}

                    int fetchCount = 0;
                    if (totalCommits > 0) {
                        fetchCount = Math.min(totalCommits, 10);
                        String logRaw = execCmd(p, rootDir, "git", "log",
                                "-" + fetchCount,
                                "--pretty=format:%h|||%an|||%ad|||%s",
                                "--date=format:%m-%d %H:%M");

                        StringBuilder logsBuilder = new StringBuilder("[");
                        String[] lines = logRaw.split("\n");
                        boolean first = true;
                        for (String line : lines) {
                            String[] parts = line.split("\\|\\|\\|");
                            if (parts.length == 4) {
                                String safeMsg = parts[3].replace("\"", "\\\"");
                                if (!first) logsBuilder.append(",");
                                logsBuilder.append("{\"hash\":\"").append(parts[0])
                                        .append("\",\"author\":\"").append(parts[1])
                                        .append("\",\"date\":\"").append(parts[2])
                                        .append("\",\"msg\":\"").append(safeMsg).append("\"}");
                                first = false;
                            }
                        }
                        logsBuilder.append("]");
                        gitLogs = logsBuilder.toString();
                    }

                    System.out.println("【检查结果】分支: " + branch
                            + ", 总提交数: " + commitCount
                            + ", 展示记录数: " + fetchCount
                            + ", 打包人: " + builder
                            + ", versionName: " + versionName);

                } catch (Exception e) {
                    System.out.println("【Git 命令执行异常】: " + e.getMessage());
                }

                String json = "{\n"
                        + "  \"branch\": \"" + branch + "\",\n"
                        + "  \"versionName\":\"" + versionName + "\",\n"
                        + "  \"builder\": \"" + builder + "\",\n"
                        + "  \"buildTime\": \"" + buildTime + "\",\n"
                        + "  \"buildEnv\": \"" + buildEnv + "\",\n"
                        + "  \"recentLogs\": " + gitLogs + "\n"
                        + "}";

                File assetsDir = new File(p.getProjectDir(), "src/main/assets");
                if (!assetsDir.exists()) assetsDir.mkdirs();
                File infoFile = new File(assetsDir, "git_info.json");
                try {
                    java.nio.file.Files.write(infoFile.toPath(), json.getBytes("UTF-8"));
                } catch (Exception e) {
                    throw new RuntimeException("写入 git_info.json 失败: " + e.getMessage(), e);
                }

                System.out.println("【Git Info】详细数据已生成至: " + infoFile.getAbsolutePath());
                System.out.println("=======================================================");
            });
        }).get();

        targetProject.afterEvaluate(prj -> {
            try {
                Task preBuild = prj.getTasks().findByName("preBuild");
                if (preBuild != null) {
                    preBuild.dependsOn(gitInfoTask);
                }
            } catch (Exception ignore) {}
        });
    }

    /**
     * 执行命令并返回
     */
    private static String execCmd(Project p, File workingDir, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        p.exec(action -> {
            action.setWorkingDir(workingDir);
            action.setCommandLine((Object[]) args);
            action.setStandardOutput(stdout);
        });
        return stdout.toString().trim();
    }
}
