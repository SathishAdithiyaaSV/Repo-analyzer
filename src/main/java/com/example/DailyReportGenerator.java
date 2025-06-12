package com.example;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class DailyReportGenerator {
    
    private static final String CONFIG_FILE = "repositories.json";
    private static final String REPORT_DIR = "reports";
    // Configure ObjectMapper with JavaTimeModule for Java 8 date/time support
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    
    public enum RepositoryType {
        GITHUB, GITLAB, BITBUCKET, SVN
    }
    
    public static class RepositoryConfig {
        public String name;
        public String url;
        public RepositoryType type;
        public String username;
        public String password; // or token
        public boolean active = true;
        
        public RepositoryConfig() {}
        
        public RepositoryConfig(String name, String url, RepositoryType type) {
            this.name = name;
            this.url = url;
            this.type = type;
        }
    }
    
    public static class ContributionStats {
        public int added = 0;
        public int deleted = 0;
        public int commits = 0;
        public Set<String> files = new HashSet<>();
        
        public int getNetChange() {
            return added - deleted;
        }
        
        @Override
        public String toString() {
            return String.format("Commits: %d, +%d/-%d lines, %d files", 
                commits, added, deleted, files.size());
        }
    }
    
    public static class ReportData {
        public String repositoryName;
        public String repositoryUrl;
        public RepositoryType repositoryType;
        public LocalDate reportDate;
        public Map<String, ContributionStats> contributions = new HashMap<>();
        public boolean success = true;
        public String errorMessage;
        
        public int getTotalCommits() {
            return contributions.values().stream().mapToInt(s -> s.commits).sum();
        }
        
        public int getTotalLinesAdded() {
            return contributions.values().stream().mapToInt(s -> s.added).sum();
        }
        
        public int getTotalLinesDeleted() {
            return contributions.values().stream().mapToInt(s -> s.deleted).sum();
        }
    }
    
    public static void main(String[] args) {
        try {
            DailyReportGenerator generator = new DailyReportGenerator();
            
            // Check if config file exists, create sample if not
            if (!Files.exists(Paths.get(CONFIG_FILE))) {
                generator.createSampleConfig();
                System.out.println("Sample configuration created: " + CONFIG_FILE);
                System.out.println("Please edit the configuration file and run again.");
                return;
            }
            
            // Generate daily report
            List<ReportData> reports = generator.generateDailyReports();
            generator.saveReports(reports);
            generator.printSummary(reports);
            
        } catch (Exception e) {
            System.err.println("Error generating daily reports: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void createSampleConfig() throws IOException {
        List<RepositoryConfig> sampleRepos = Arrays.asList(
            new RepositoryConfig("MyProject", "https://github.com/user/myproject.git", RepositoryType.GITHUB),
            new RepositoryConfig("WebApp", "https://gitlab.com/user/webapp.git", RepositoryType.GITLAB),
            new RepositoryConfig("API", "https://bitbucket.org/user/api.git", RepositoryType.BITBUCKET)
        );
        
        // Add credentials placeholders
        sampleRepos.get(0).username = "your-github-username";
        sampleRepos.get(0).password = "your-github-token";
        
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(new File(CONFIG_FILE), sampleRepos);
    }
    
    public List<RepositoryConfig> loadConfiguration() throws IOException {
        JsonNode rootNode = objectMapper.readTree(new File(CONFIG_FILE));
        List<RepositoryConfig> configs = new ArrayList<>();
        
        for (JsonNode node : rootNode) {
            RepositoryConfig config = new RepositoryConfig();
            config.name = node.get("name").asText();
            config.url = node.get("url").asText();
            config.type = RepositoryType.valueOf(node.get("type").asText().toUpperCase());
            
            if (node.has("username")) config.username = node.get("username").asText();
            if (node.has("password")) config.password = node.get("password").asText();
            if (node.has("active")) config.active = node.get("active").asBoolean();
            
            if (config.active) {
                configs.add(config);
            }
        }
        
        return configs;
    }
    
    public List<ReportData> generateDailyReports() throws IOException {
        List<RepositoryConfig> configs = loadConfiguration();
        List<ReportData> reports = new ArrayList<>();
        
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<ReportData>> futures = new ArrayList<>();
        
        System.out.println("Analyzing " + configs.size() + " repositories...");
        
        for (RepositoryConfig config : configs) {
            futures.add(executor.submit(() -> analyzeRepository(config)));
        }
        
        for (Future<ReportData> future : futures) {
            try {
                reports.add(future.get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                ReportData errorReport = new ReportData();
                errorReport.success = false;
                errorReport.errorMessage = e.getMessage();
                reports.add(errorReport);
            }
        }
        
        executor.shutdown();
        
        // Ensure proper cleanup of executor
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        return reports;
    }
    
    private ReportData analyzeRepository(RepositoryConfig config) {
        ReportData report = new ReportData();
        report.repositoryName = config.name;
        report.repositoryUrl = config.url;
        report.repositoryType = config.type;
        report.reportDate = LocalDate.now();
        
        try {
            switch (config.type) {
                case GITHUB:
                case GITLAB:
                case BITBUCKET:
                    analyzeGitRepository(config, report);
                    break;
                case SVN:
                    analyzeSvnRepository(config, report);
                    break;
            }
        } catch (Exception e) {
            report.success = false;
            report.errorMessage = e.getMessage();
            System.err.println("Error analyzing " + config.name + ": " + e.getMessage());
        }
        
        return report;
    }
    
    private void analyzeGitRepository(RepositoryConfig config, ReportData report) 
            throws GitAPIException, IOException {
        
        String repoName = config.name.replaceAll("[^a-zA-Z0-9]", "_");
        Path localPath = Paths.get("./temp_repos/" + repoName);
        Git git = null;
        
        try {
            // Cleanup and clone
            if (Files.exists(localPath)) {
                deleteDirectory(localPath.toFile());
            }
            Files.createDirectories(localPath.getParent());
            
            System.out.println("Cloning " + config.name + "...");
            
            if (config.username != null && config.password != null) {
                git = Git.cloneRepository()
                        .setURI(config.url)
                        .setDirectory(localPath.toFile())
                        .setCredentialsProvider(new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                            config.username, config.password))
                        .call();
            } else {
                git = Git.cloneRepository()
                        .setURI(config.url)
                        .setDirectory(localPath.toFile())
                        .call();
            }
            
            LocalDate today = LocalDate.now();
            
            try (Repository repository = git.getRepository()) {
                Iterable<RevCommit> commits = git.log().call();
                
                for (RevCommit commit : commits) {
                    Instant commitInstant = Instant.ofEpochSecond(commit.getCommitTime());
                    LocalDate commitDate = commitInstant.atZone(ZoneId.systemDefault()).toLocalDate();
                    
                    if (commitDate.equals(today)) {
                        String author = commit.getAuthorIdent().getName();
                        
                        int[] changes = calculateChanges(repository, commit);
                        Set<String> modifiedFiles = getModifiedFiles(repository, commit);
                        
                        report.contributions.computeIfAbsent(author, k -> new ContributionStats());
                        ContributionStats stats = report.contributions.get(author);
                        stats.added += changes[0];
                        stats.deleted += changes[1];
                        stats.commits += 1;
                        stats.files.addAll(modifiedFiles);
                    }
                }
            }
        } finally {
            // Ensure proper cleanup
            if (git != null) {
                git.close();
            }
            deleteDirectory(localPath.toFile());
        }
    }
    
    private void analyzeSvnRepository(RepositoryConfig config, ReportData report) {
        // SVN analysis would require SVNKit library
        // This is a placeholder implementation
        report.success = false;
        report.errorMessage = "SVN support requires SVNKit library implementation";
    }
    
    private int[] calculateChanges(Repository repository, RevCommit commit) throws IOException {
        int added = 0;
        int deleted = 0;
        
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
             ObjectReader reader = repository.newObjectReader();
             RevWalk revWalk = new RevWalk(repository)) {
            
            diffFormatter.setRepository(repository);
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);
            diffFormatter.setDetectRenames(true);
            
            RevCommit parent = null;
            if (commit.getParentCount() > 0) {
                ObjectId parentId = commit.getParent(0).getId();
                parent = revWalk.parseCommit(parentId);
            }
            
            List<DiffEntry> diffs;
            if (parent == null) {
                CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
                newTreeIter.reset(reader, commit.getTree());
                diffs = diffFormatter.scan(new CanonicalTreeParser(), newTreeIter);
            } else {
                CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
                oldTreeIter.reset(reader, parent.getTree());
                CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
                newTreeIter.reset(reader, commit.getTree());
                diffs = diffFormatter.scan(oldTreeIter, newTreeIter);
            }
            
            for (DiffEntry diff : diffs) {
                FileHeader fileHeader = diffFormatter.toFileHeader(diff);
                EditList editList = fileHeader.toEditList();
                
                for (int i = 0; i < editList.size(); i++) {
                    added += editList.get(i).getEndB() - editList.get(i).getBeginB();
                    deleted += editList.get(i).getEndA() - editList.get(i).getBeginA();
                }
            }
        }
        
        return new int[]{added, deleted};
    }
    
    private Set<String> getModifiedFiles(Repository repository, RevCommit commit) throws IOException {
        Set<String> files = new HashSet<>();
        
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
             ObjectReader reader = repository.newObjectReader();
             RevWalk revWalk = new RevWalk(repository)) {
            
            diffFormatter.setRepository(repository);
            
            RevCommit parent = null;
            if (commit.getParentCount() > 0) {
                ObjectId parentId = commit.getParent(0).getId();
                parent = revWalk.parseCommit(parentId);
            }
            
            List<DiffEntry> diffs;
            if (parent == null) {
                CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
                newTreeIter.reset(reader, commit.getTree());
                diffs = diffFormatter.scan(new CanonicalTreeParser(), newTreeIter);
            } else {
                CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
                oldTreeIter.reset(reader, parent.getTree());
                CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
                newTreeIter.reset(reader, commit.getTree());
                diffs = diffFormatter.scan(oldTreeIter, newTreeIter);
            }
            
            for (DiffEntry diff : diffs) {
                files.add(diff.getNewPath());
            }
        }
        
        return files;
    }
    
    public void saveReports(List<ReportData> reports) throws IOException {
        Path reportDir = Paths.get(REPORT_DIR);
        if (!Files.exists(reportDir)) {
            Files.createDirectories(reportDir);
        }
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        
        // Save JSON report
        String jsonFilename = String.format("daily-report-%s.json", timestamp);
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(reportDir.resolve(jsonFilename).toFile(), reports);
        
        // Save HTML report
        String htmlFilename = String.format("daily-report-%s.html", timestamp);
        generateHtmlReport(reports, reportDir.resolve(htmlFilename));
        
        // Save CSV report
        String csvFilename = String.format("daily-report-%s.csv", timestamp);
        generateCsvReport(reports, reportDir.resolve(csvFilename));
        
        System.out.println("Reports saved to " + reportDir.toAbsolutePath());
    }
    
    private void generateHtmlReport(List<ReportData> reports, Path htmlFile) throws IOException {
        StringBuilder html = new StringBuilder();
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        
        html.append("<!DOCTYPE html><html><head>")
            .append("<title>Daily Code Contribution Report - ").append(date).append("</title>")
            .append("<style>")
            .append("body{font-family:Arial,sans-serif;margin:20px;}")
            .append("table{border-collapse:collapse;width:100%;margin:20px 0;}")
            .append("th,td{border:1px solid #ddd;padding:12px;text-align:left;}")
            .append("th{background-color:#f2f2f2;}")
            .append(".summary{background-color:#e8f4fd;padding:15px;border-radius:5px;margin:20px 0;}")
            .append(".error{color:red;}")
            .append(".success{color:green;}")
            .append("</style></head><body>")
            .append("<h1>Daily Code Contribution Report</h1>")
            .append("<h2>").append(date).append("</h2>");
        
        // Summary
        int totalRepos = reports.size();
        int successfulRepos = (int) reports.stream().filter(r -> r.success).count();
        int totalCommits = reports.stream().mapToInt(ReportData::getTotalCommits).sum();
        int totalLinesAdded = reports.stream().mapToInt(ReportData::getTotalLinesAdded).sum();
        int totalLinesDeleted = reports.stream().mapToInt(ReportData::getTotalLinesDeleted).sum();
        
        html.append("<div class='summary'>")
            .append("<h3>Summary</h3>")
            .append("<p><strong>Repositories Analyzed:</strong> ").append(successfulRepos).append("/").append(totalRepos).append("</p>")
            .append("<p><strong>Total Commits:</strong> ").append(totalCommits).append("</p>")
            .append("<p><strong>Total Lines Added:</strong> ").append(totalLinesAdded).append("</p>")
            .append("<p><strong>Total Lines Deleted:</strong> ").append(totalLinesDeleted).append("</p>")
            .append("<p><strong>Net Change:</strong> ").append(totalLinesAdded - totalLinesDeleted).append(" lines</p>")
            .append("</div>");
        
        // Detailed reports
        for (ReportData report : reports) {
            html.append("<h3>").append(report.repositoryName);
            if (!report.success) {
                html.append(" <span class='error'>(Failed)</span>");
            }
            html.append("</h3>");
            
            if (!report.success) {
                html.append("<p class='error'>Error: ").append(report.errorMessage).append("</p>");
                continue;
            }
            
            if (report.contributions.isEmpty()) {
                html.append("<p>No contributions found for today.</p>");
                continue;
            }
            
            html.append("<table>")
                .append("<tr><th>Developer</th><th>Commits</th><th>Lines Added</th><th>Lines Deleted</th><th>Net Change</th><th>Files Modified</th></tr>");
            
            report.contributions.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().commits, e1.getValue().commits))
                .forEach(entry -> {
                    ContributionStats stats = entry.getValue();
                    html.append("<tr>")
                        .append("<td>").append(entry.getKey()).append("</td>")
                        .append("<td>").append(stats.commits).append("</td>")
                        .append("<td>").append(stats.added).append("</td>")
                        .append("<td>").append(stats.deleted).append("</td>")
                        .append("<td>").append(stats.getNetChange()).append("</td>")
                        .append("<td>").append(stats.files.size()).append("</td>")
                        .append("</tr>");
                });
            
            html.append("</table>");
        }
        
        html.append("</body></html>");
        
        Files.write(htmlFile, html.toString().getBytes());
    }
    
    private void generateCsvReport(List<ReportData> reports, Path csvFile) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("Repository,Developer,Commits,Lines Added,Lines Deleted,Net Change,Files Modified\n");
        
        for (ReportData report : reports) {
            if (!report.success) continue;
            
            for (Map.Entry<String, ContributionStats> entry : report.contributions.entrySet()) {
                ContributionStats stats = entry.getValue();
                csv.append(report.repositoryName).append(",")
                   .append(entry.getKey()).append(",")
                   .append(stats.commits).append(",")
                   .append(stats.added).append(",")
                   .append(stats.deleted).append(",")
                   .append(stats.getNetChange()).append(",")
                   .append(stats.files.size()).append("\n");
            }
        }
        
        Files.write(csvFile, csv.toString().getBytes());
    }
    
    public void printSummary(List<ReportData> reports) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("DAILY CODE CONTRIBUTION REPORT");
        System.out.println(LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy")));
        System.out.println("=".repeat(60));
        
        int totalCommits = 0;
        int totalAdded = 0;
        int totalDeleted = 0;
        
        for (ReportData report : reports) {
            System.out.println("\n📁 " + report.repositoryName + " (" + report.repositoryType + ")");
            
            if (!report.success) {
                System.out.println("   ❌ Error: " + report.errorMessage);
                continue;
            }
            
            if (report.contributions.isEmpty()) {
                System.out.println("   📊 No contributions today");
                continue;
            }
            
            System.out.println("   📊 " + report.getTotalCommits() + " commits, +" + 
                report.getTotalLinesAdded() + "/-" + report.getTotalLinesDeleted() + " lines");
            
            report.contributions.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().commits, e1.getValue().commits))
                .forEach(entry -> {
                    ContributionStats stats = entry.getValue();
                    System.out.println("     👤 " + entry.getKey() + ": " + stats.toString());
                });
            
            totalCommits += report.getTotalCommits();
            totalAdded += report.getTotalLinesAdded();
            totalDeleted += report.getTotalLinesDeleted();
        }
        
        System.out.println("\n" + "=".repeat(60));
        System.out.println("📈 TOTAL: " + totalCommits + " commits, +" + totalAdded + 
                          "/-" + totalDeleted + " lines (net: " + (totalAdded - totalDeleted) + ")");
        System.out.println("=".repeat(60));
    }
    
    private static void deleteDirectory(File directory) throws IOException {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}