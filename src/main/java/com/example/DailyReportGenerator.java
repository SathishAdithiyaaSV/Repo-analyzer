package com.example;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class DailyReportGenerator {
    
    private static final String CONFIG_FILE = "repositories.json";
    private static final String REPORT_DIR = "reports";
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    
    public enum RepositoryType {
        GITHUB, GITLAB, BITBUCKET
    }
    
    public static class RepositoryConfig {
        public String name;
        public String url;
        public RepositoryType type;
        public String token; // API token/personal access token
        public String owner; // Repository owner/organization
        public String repo; // Repository name
        public boolean active = true;
        
        public RepositoryConfig() {}
        
        public RepositoryConfig(String name, String url, RepositoryType type, String owner, String repo) {
            this.name = name;
            this.url = url;
            this.type = type;
            this.owner = owner;
            this.repo = repo;
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
            
            if (!Files.exists(Paths.get(CONFIG_FILE))) {
                generator.createSampleConfig();
                System.out.println("Sample configuration created: " + CONFIG_FILE);
                System.out.println("Please edit the configuration file and run again.");
                return;
            }
            
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
            new RepositoryConfig("MyProject", "https://github.com/user/myproject", RepositoryType.GITHUB, "user", "myproject"),
            new RepositoryConfig("WebApp", "https://gitlab.com/user/webapp", RepositoryType.GITLAB, "user", "webapp"),
            new RepositoryConfig("API", "https://bitbucket.org/user/api", RepositoryType.BITBUCKET, "user", "api")
        );
        
        // Add token placeholders
        sampleRepos.get(0).token = "github_pat_your_token_here";
        sampleRepos.get(1).token = "glpat-your_gitlab_token_here";
        sampleRepos.get(2).token = "your_bitbucket_app_password_here";
        
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
            config.owner = node.get("owner").asText();
            config.repo = node.get("repo").asText();
            
            if (node.has("token")) config.token = node.get("token").asText();
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
        
        System.out.println("Analyzing " + configs.size() + " repositories via API...");
        
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
                    analyzeGitHubRepository(config, report);
                    break;
                case GITLAB:
                    analyzeGitLabRepository(config, report);
                    break;
                case BITBUCKET:
                    analyzeBitbucketRepository(config, report);
                    break;
            }
        } catch (Exception e) {
            report.success = false;
            report.errorMessage = e.getMessage();
            System.err.println("Error analyzing " + config.name + ": " + e.getMessage());
        }
        
        return report;
    }
    
    private void analyzeGitHubRepository(RepositoryConfig config, ReportData report) 
            throws IOException, InterruptedException {
        
        String today = LocalDate.now().toString();
        String apiUrl = String.format("https://api.github.com/repos/%s/%s/commits", 
                                    config.owner, config.repo);
        
        // Get commits for today
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "?since=" + today + "T00:00:00Z&until=" + today + "T23:59:59Z"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "DailyReportGenerator");
        
        if (config.token != null) {
            requestBuilder.header("Authorization", "Bearer " + config.token);
        }
        
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API error: " + response.statusCode() + " - " + response.body());
        }
        
        JsonNode commits = objectMapper.readTree(response.body());
        
        for (JsonNode commit : commits) {
            String author = commit.get("commit").get("author").get("name").asText();
            String sha = commit.get("sha").asText();
            
            // Get detailed commit info with stats
            String commitUrl = String.format("https://api.github.com/repos/%s/%s/commits/%s", 
                                           config.owner, config.repo, sha);
            
            HttpRequest commitRequest = HttpRequest.newBuilder()
                    .uri(URI.create(commitUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "DailyReportGenerator")
                    .header("Authorization", config.token != null ? "Bearer " + config.token : "")
                    .build();
            
            HttpResponse<String> commitResponse = httpClient.send(commitRequest, HttpResponse.BodyHandlers.ofString());
            
            if (commitResponse.statusCode() == 200) {
                JsonNode commitData = objectMapper.readTree(commitResponse.body());
                JsonNode stats = commitData.get("stats");
                JsonNode files = commitData.get("files");
                
                ContributionStats contributionStats = report.contributions.computeIfAbsent(author, k -> new ContributionStats());
                contributionStats.commits++;
                
                if (stats != null) {
                    contributionStats.added += stats.get("additions").asInt();
                    contributionStats.deleted += stats.get("deletions").asInt();
                }
                
                if (files != null) {
                    for (JsonNode file : files) {
                        contributionStats.files.add(file.get("filename").asText());
                    }
                }
            }
        }
    }
    
    private void analyzeGitLabRepository(RepositoryConfig config, ReportData report) 
            throws IOException, InterruptedException {
        
        String today = LocalDate.now().toString();
        String projectId = config.owner + "%2F" + config.repo; // URL encode the project path
        String apiUrl = String.format("https://gitlab.com/api/v4/projects/%s/repository/commits", projectId);
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "?since=" + today + "T00:00:00Z&until=" + today + "T23:59:59Z"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "DailyReportGenerator");
        
        if (config.token != null) {
            requestBuilder.header("PRIVATE-TOKEN", config.token);
        }
        
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("GitLab API error: " + response.statusCode() + " - " + response.body());
        }
        
        JsonNode commits = objectMapper.readTree(response.body());
        
        for (JsonNode commit : commits) {
            String author = commit.get("author_name").asText();
            String sha = commit.get("id").asText();
            
            // Get commit diff stats
            String commitUrl = String.format("https://gitlab.com/api/v4/projects/%s/repository/commits/%s", 
                                           projectId, sha);
            
            HttpRequest commitRequest = HttpRequest.newBuilder()
                    .uri(URI.create(commitUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "DailyReportGenerator")
                    .header("PRIVATE-TOKEN", config.token != null ? config.token : "")
                    .build();
            
            HttpResponse<String> commitResponse = httpClient.send(commitRequest, HttpResponse.BodyHandlers.ofString());
            
            if (commitResponse.statusCode() == 200) {
                JsonNode commitData = objectMapper.readTree(commitResponse.body());
                JsonNode stats = commitData.get("stats");
                
                ContributionStats contributionStats = report.contributions.computeIfAbsent(author, k -> new ContributionStats());
                contributionStats.commits++;
                
                if (stats != null) {
                    contributionStats.added += stats.get("additions").asInt();
                    contributionStats.deleted += stats.get("deletions").asInt();
                }
                
                // Get modified files from diff
                String diffUrl = commitUrl + "/diff";
                HttpRequest diffRequest = HttpRequest.newBuilder()
                        .uri(URI.create(diffUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "DailyReportGenerator")
                        .header("PRIVATE-TOKEN", config.token != null ? config.token : "")
                        .build();
                
                HttpResponse<String> diffResponse = httpClient.send(diffRequest, HttpResponse.BodyHandlers.ofString());
                if (diffResponse.statusCode() == 200) {
                    JsonNode diffs = objectMapper.readTree(diffResponse.body());
                    for (JsonNode diff : diffs) {
                        contributionStats.files.add(diff.get("new_path").asText());
                    }
                }
            }
        }
    }
    
    private void analyzeBitbucketRepository(RepositoryConfig config, ReportData report) 
            throws IOException, InterruptedException {
        
        // Bitbucket API v2.0
        String apiUrl = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/commits", 
                                    config.owner, config.repo);
        
        String credentials = config.owner + ":" + config.token;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + encodedCredentials)
                .header("User-Agent", "DailyReportGenerator")
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Bitbucket API error: " + response.statusCode() + " - " + response.body());
        }
        
        JsonNode data = objectMapper.readTree(response.body());
        JsonNode commits = data.get("values");
        
        String today = LocalDate.now().toString();
        
        for (JsonNode commit : commits) {
            String commitDate = commit.get("date").asText().substring(0, 10); // Extract date part
            
            if (commitDate.equals(today)) {
                String author = commit.get("author").get("user").get("display_name").asText();
                String hash = commit.get("hash").asText();
                
                // Get commit diff stats
                String diffUrl = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/diffstat/%s", 
                                             config.owner, config.repo, hash);
                
                HttpRequest diffRequest = HttpRequest.newBuilder()
                        .uri(URI.create(diffUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Basic " + encodedCredentials)
                        .header("User-Agent", "DailyReportGenerator")
                        .build();
                
                HttpResponse<String> diffResponse = httpClient.send(diffRequest, HttpResponse.BodyHandlers.ofString());
                
                ContributionStats contributionStats = report.contributions.computeIfAbsent(author, k -> new ContributionStats());
                contributionStats.commits++;
                
                if (diffResponse.statusCode() == 200) {
                    JsonNode diffData = objectMapper.readTree(diffResponse.body());
                    JsonNode diffStats = diffData.get("values");
                    
                    for (JsonNode fileStat : diffStats) {
                        contributionStats.added += fileStat.get("lines_added").asInt();
                        contributionStats.deleted += fileStat.get("lines_removed").asInt();
                        contributionStats.files.add(fileStat.get("new").get("path").asText());
                    }
                }
            }
        }
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
            .append("<h1>Daily Code Contribution Report (API-Based)</h1>")
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
        System.out.println("DAILY CODE CONTRIBUTION REPORT (API-BASED)");
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
}