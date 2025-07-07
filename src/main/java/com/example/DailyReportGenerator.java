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
import java.util.stream.Collectors;

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
        public boolean analyzeAllBranches = true; // New field to control branch analysis
        public List<String> specificBranches = new ArrayList<>(); // Optional: specific branches to analyze
        
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
        public Set<String> branches = new HashSet<>(); // Track which branches had commits
        
        public int getNetChange() {
            return added - deleted;
        }
        
        @Override
        public String toString() {
            return String.format("Commits: %d, +%d/-%d lines, %d files, %d branches", 
                commits, added, deleted, files.size(), branches.size());
        }
    }
    
    public static class ReportData {
        public String repositoryName;
        public String repositoryUrl;
        public RepositoryType repositoryType;
        public LocalDate reportDate;
        public Map<String, ContributionStats> contributions = new HashMap<>();
        public Set<String> analyzedBranches = new HashSet<>(); // Track analyzed branches
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
        
        // Add token placeholders and branch configuration
        sampleRepos.get(0).token = "github_pat_your_token_here";
        sampleRepos.get(0).analyzeAllBranches = true;
        
        sampleRepos.get(1).token = "glpat-your_gitlab_token_here";
        sampleRepos.get(1).analyzeAllBranches = true;
        
        sampleRepos.get(2).token = "your_bitbucket_app_password_here";
        sampleRepos.get(2).analyzeAllBranches = false;
        sampleRepos.get(2).specificBranches = Arrays.asList("main", "develop", "feature/new-api");
        
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
            if (node.has("analyzeAllBranches")) config.analyzeAllBranches = node.get("analyzeAllBranches").asBoolean();
            
            if (node.has("specificBranches")) {
                JsonNode branchesNode = node.get("specificBranches");
                for (JsonNode branchNode : branchesNode) {
                    config.specificBranches.add(branchNode.asText());
                }
            }
            
            if (config.active) {
                configs.add(config);
            }
        }
        
        return configs;
    }
    
    private void validateRepositoryExists(RepositoryConfig config) throws IOException, InterruptedException {
        String apiUrl;
        HttpRequest.Builder requestBuilder;
        
        switch (config.type) {
            case GITHUB:
                apiUrl = String.format("https://api.github.com/repos/%s/%s", config.owner, config.repo);
                requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "DailyReportGenerator");
                
                if (config.token != null) {
                    requestBuilder.header("Authorization", "Bearer " + config.token);
                }
                break;
                
            case GITLAB:
                String projectId = config.owner + "%2F" + config.repo;
                apiUrl = String.format("https://gitlab.com/api/v4/projects/%s", projectId);
                requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "DailyReportGenerator");
                
                if (config.token != null) {
                    requestBuilder.header("PRIVATE-TOKEN", config.token);
                }
                break;
                
            case BITBUCKET:
                apiUrl = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s", 
                                     config.owner, config.repo);
                String credentials = config.owner + ":" + config.token;
                String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
                requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Basic " + encodedCredentials)
                        .header("User-Agent", "DailyReportGenerator");
                break;
                
            default:
                throw new IllegalArgumentException("Unsupported repository type: " + config.type);
        }
        
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 404) {
            throw new IOException("Repository not found: " + config.owner + "/" + config.repo);
        } else if (response.statusCode() == 401) {
            throw new IOException("Authentication failed for repository: " + config.owner + "/" + config.repo);
        } else if (response.statusCode() == 403) {
            throw new IOException("Access denied to repository: " + config.owner + "/" + config.repo);
        } else if (response.statusCode() != 200) {
            throw new IOException("Error accessing repository " + config.owner + "/" + config.repo + 
                                ": HTTP " + response.statusCode());
        }
    }

    private void validateBranchesExist(RepositoryConfig config, List<String> branchesToCheck) 
        throws IOException, InterruptedException {
    
    List<String> allBranches;
    
    try {
        switch (config.type) {
            case GITHUB:
                allBranches = getGitHubBranches(config);
                break;
            case GITLAB:
                allBranches = getGitLabBranches(config);
                break;
            case BITBUCKET:
                allBranches = getBitbucketBranches(config);
                break;
            default:
                return; // Skip validation for unknown types
        }
        
        List<String> missingBranches = branchesToCheck.stream()
            .filter(branch -> !allBranches.contains(branch))
            .collect(Collectors.toList());
        
        if (!missingBranches.isEmpty()) {
            throw new IOException("Branches not found: " + String.join(", ", missingBranches));
        }
        
    } catch (Exception e) {
        System.err.println("⚠️  Warning: Could not validate branches for " + config.name + ": " + e.getMessage());
        // Continue with specified branches even if validation fails
    }
}

// 4. REPLACE EXISTING METHOD - Replace your existing analyzeRepository method with this
private ReportData analyzeRepository(RepositoryConfig config) {
    ReportData report = new ReportData();
    report.repositoryName = config.name;
    report.repositoryUrl = config.url;
    report.repositoryType = config.type;
    report.reportDate = LocalDate.now();
    
    try {
        // VALIDATE REPOSITORY EXISTS FIRST
        System.out.println("Validating repository: " + config.name);
        validateRepositoryExists(config);
        
        List<String> branchesToAnalyze = getBranchesToAnalyze(config);
        
        if (branchesToAnalyze.isEmpty()) {
            report.success = false;
            report.errorMessage = "No branches found to analyze";
            return report;
        }
        
        System.out.println("Analyzing " + branchesToAnalyze.size() + " branches for " + config.name);
        
        switch (config.type) {
            case GITHUB:
                analyzeGitHubRepository(config, report, branchesToAnalyze);
                break;
            case GITLAB:
                analyzeGitLabRepository(config, report, branchesToAnalyze);
                break;
            case BITBUCKET:
                analyzeBitbucketRepository(config, report, branchesToAnalyze);
                break;
        }
        
        report.analyzedBranches.addAll(branchesToAnalyze);
        
    } catch (Exception e) {
        report.success = false;
        report.errorMessage = e.getMessage();
        System.err.println("❌ Error analyzing " + config.name + ": " + e.getMessage());
    }
    
    return report;
}

    public List<ReportData> generateDailyReports() throws IOException {
        List<RepositoryConfig> configs = loadConfiguration();
        List<ReportData> reports = new ArrayList<>();
        
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<ReportData>> futures = new ArrayList<>();
        
        System.out.println("Analyzing " + configs.size() + " repositories (all branches) via API...");
        
        for (RepositoryConfig config : configs) {
            futures.add(executor.submit(() -> analyzeRepository(config)));
        }
        
        for (Future<ReportData> future : futures) {
            try {
                reports.add(future.get(60, TimeUnit.SECONDS)); // Increased timeout for multi-branch analysis
            } catch (Exception e) {
                ReportData errorReport = new ReportData();
                errorReport.success = false;
                errorReport.errorMessage = e.getMessage();
                reports.add(errorReport);
            }
        }
        
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        return reports;
    }
    
    
    private List<String> getBranchesToAnalyze(RepositoryConfig config) throws IOException, InterruptedException {
        if (!config.analyzeAllBranches) {
            List<String> branches = config.specificBranches.isEmpty() ? 
                Arrays.asList("main", "master") : config.specificBranches;
            
            // Validate that specified branches exist
            validateBranchesExist(config, branches);
            return branches;
        }
        
        // Get all branches from the repository
        switch (config.type) {
            case GITHUB:
                return getGitHubBranches(config);
            case GITLAB:
                return getGitLabBranches(config);
            case BITBUCKET:
                return getBitbucketBranches(config);
            default:
                throw new IllegalArgumentException("Unsupported repository type: " + config.type);
        }
    }
    
    private List<String> getGitHubBranches(RepositoryConfig config) throws IOException, InterruptedException {
        String apiUrl = String.format("https://api.github.com/repos/%s/%s/branches", config.owner, config.repo);
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "DailyReportGenerator");
        
        if (config.token != null) {
            requestBuilder.header("Authorization", "Bearer " + config.token);
        }
        
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API error getting branches: " + response.statusCode());
        }
        
        JsonNode branches = objectMapper.readTree(response.body());
        List<String> branchNames = new ArrayList<>();
        
        for (JsonNode branch : branches) {
            branchNames.add(branch.get("name").asText());
        }
        
        return branchNames;
    }
    
    private List<String> getGitLabBranches(RepositoryConfig config) throws IOException, InterruptedException {
        String projectId = config.owner + "%2F" + config.repo;
        String apiUrl = String.format("https://gitlab.com/api/v4/projects/%s/repository/branches", projectId);
        
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "DailyReportGenerator");
        
        if (config.token != null) {
            requestBuilder.header("PRIVATE-TOKEN", config.token);
        }
        
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("GitLab API error getting branches: " + response.statusCode());
        }
        
        JsonNode branches = objectMapper.readTree(response.body());
        List<String> branchNames = new ArrayList<>();
        
        for (JsonNode branch : branches) {
            branchNames.add(branch.get("name").asText());
        }
        
        return branchNames;
    }
    
    private List<String> getBitbucketBranches(RepositoryConfig config) throws IOException, InterruptedException {
        String apiUrl = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/refs/branches", 
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
            throw new IOException("Bitbucket API error getting branches: " + response.statusCode());
        }
        
        JsonNode data = objectMapper.readTree(response.body());
        JsonNode branches = data.get("values");
        List<String> branchNames = new ArrayList<>();
        
        for (JsonNode branch : branches) {
            branchNames.add(branch.get("name").asText());
        }
        
        return branchNames;
    }
    
    private void analyzeGitHubRepository(RepositoryConfig config, ReportData report, List<String> branches) 
            throws IOException, InterruptedException {
        
       // Get today in IST and create a wider UTC range to capture commits
        LocalDate today = LocalDate.now();
        String since = today.minusDays(1).toString() + "T18:30:00Z"; // Yesterday 6:30 PM UTC = Today 12:00 AM IST
        String until = today.toString() + "T18:29:59Z"; // Today 6:29 PM UTC = Today 11:59 PM IST

        for (String branch : branches) {
            String apiUrl = String.format("https://api.github.com/repos/%s/%s/commits", 
                                        config.owner, config.repo);
            
            // Get commits for today from specific branch (adjusted for IST timezone)
            String commitsUrl = apiUrl + "?sha=" + branch + "&since=" + since + "&until=" + until;
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(commitsUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "DailyReportGenerator");
            
            if (config.token != null) {
                requestBuilder.header("Authorization", "Bearer " + config.token);
            }
            
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Error getting commits for branch " + branch + ": " + response.statusCode());
                continue;
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
                    contributionStats.branches.add(branch);
                    
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
    }
    
    private void analyzeGitLabRepository(RepositoryConfig config, ReportData report, List<String> branches) 
            throws IOException, InterruptedException {
        
        String today = LocalDate.now().toString();
        String projectId = config.owner + "%2F" + config.repo;
        
        for (String branch : branches) {
            String apiUrl = String.format("https://gitlab.com/api/v4/projects/%s/repository/commits", projectId);
            String commitsUrl = apiUrl + "?ref_name=" + branch + "&since=" + today + "T00:00:00Z&until=" + today + "T23:59:59Z";
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(commitsUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "DailyReportGenerator");
            
            if (config.token != null) {
                requestBuilder.header("PRIVATE-TOKEN", config.token);
            }
            
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Error getting commits for branch " + branch + ": " + response.statusCode());
                continue;
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
                    contributionStats.branches.add(branch);
                    
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
    }
    
    private void analyzeBitbucketRepository(RepositoryConfig config, ReportData report, List<String> branches) 
            throws IOException, InterruptedException {
        
        String today = LocalDate.now().toString();
        String credentials = config.owner + ":" + config.token;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        
        for (String branch : branches) {
            String apiUrl = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/commits/%s", 
                                        config.owner, config.repo, branch);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Basic " + encodedCredentials)
                    .header("User-Agent", "DailyReportGenerator")
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                System.err.println("Error getting commits for branch " + branch + ": " + response.statusCode());
                continue;
            }
            
            JsonNode data = objectMapper.readTree(response.body());
            JsonNode commits = data.get("values");
            
            for (JsonNode commit : commits) {
                String commitDate = commit.get("date").asText().substring(0, 10);
                
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
                    contributionStats.branches.add(branch);
                    
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
    }
    
    public void saveReports(List<ReportData> reports) throws IOException {
        Path reportDir = Paths.get(REPORT_DIR);
        if (!Files.exists(reportDir)) {
            Files.createDirectories(reportDir);
        }
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        
        // Save JSON report
        String jsonFilename = String.format("daily-report-all-branches-%s.json", timestamp);
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(reportDir.resolve(jsonFilename).toFile(), reports);
        
        // Save HTML report
        String htmlFilename = String.format("daily-report-all-branches-%s.html", timestamp);
        generateHtmlReport(reports, reportDir.resolve(htmlFilename));
        
        // Save CSV report
        String csvFilename = String.format("daily-report-all-branches-%s.csv", timestamp);
        generateCsvReport(reports, reportDir.resolve(csvFilename));
        
        System.out.println("Reports saved to " + reportDir.toAbsolutePath());
    }

// Complete the generateHtmlReport method from where it was cut off:
private void generateHtmlReport(List<ReportData> reports, Path htmlFile) throws IOException {
    StringBuilder html = new StringBuilder();
    String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
    
    html.append("<!DOCTYPE html><html><head>")
        .append("<title>Daily Code Contribution Report (All Branches) - ").append(date).append("</title>")
        .append("<style>")
        .append("body{font-family:Arial,sans-serif;margin:20px;}")
        .append("table{border-collapse:collapse;width:100%;margin:20px 0;}")
        .append("th,td{border:1px solid #ddd;padding:12px;text-align:left;}")
        .append("th{background-color:#f2f2f2;}")
        .append(".summary{background-color:#e8f4fd;padding:15px;border-radius:5px;margin:20px 0;}")
        .append(".error{color:red;}")
        .append(".success{color:green;}")
        .append(".branch-info{background-color:#f9f9f9;padding:10px;border-radius:3px;margin:10px 0;}")
        .append("</style></head><body>")
        .append("<h1>Daily Code Contribution Report (All Branches Analysis)</h1>")
        .append("<h2>").append(date).append("</h2>");
    
    // Summary
    int totalRepos = reports.size();
    int successfulRepos = (int) reports.stream().filter(r -> r.success).count();
    int totalCommits = reports.stream().mapToInt(ReportData::getTotalCommits).sum();
    int totalLinesAdded = reports.stream().mapToInt(ReportData::getTotalLinesAdded).sum();
    int totalLinesDeleted = reports.stream().mapToInt(ReportData::getTotalLinesDeleted).sum();
    int totalBranches = reports.stream().mapToInt(r -> r.analyzedBranches.size()).sum();
    
    html.append("<div class='summary'>")
        .append("<h3>Summary</h3>")
        .append("<p><strong>Repositories Analyzed:</strong> ").append(successfulRepos).append("/").append(totalRepos).append("</p>")
        .append("<p><strong>Total Branches Analyzed:</strong> ").append(totalBranches).append("</p>")
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
        
        html.append("<div class='branch-info'>")
            .append("<strong>Analyzed Branches:</strong> ")
            .append(String.join(", ", report.analyzedBranches))
            .append(" (").append(report.analyzedBranches.size()).append(" branches)")
            .append("</div>");
        
        if (report.contributions.isEmpty()) {
            html.append("<p>No contributions found for today across all branches.</p>");
            continue;
        }
        
        html.append("<table>")
            .append("<tr><th>Developer</th><th>Commits</th><th>Lines Added</th><th>Lines Deleted</th><th>Net Change</th><th>Files Modified</th><th>Branches</th></tr>");
        
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
                    .append("<td>").append(String.join(", ", stats.branches)).append("</td>")
                    .append("</tr>");
            });
        
        html.append("</table>");
    }
    
    html.append("</body></html>");
    
    Files.write(htmlFile, html.toString().getBytes());
}

// Complete the generateCsvReport method (missing from first file):
private void generateCsvReport(List<ReportData> reports, Path csvFile) throws IOException {
    StringBuilder csv = new StringBuilder();
    csv.append("Repository,Developer,Commits,Lines Added,Lines Deleted,Net Change,Files Modified,Branches,Analyzed Branches\n");
    
    for (ReportData report : reports) {
        if (!report.success) continue;
        
        String analyzedBranches = String.join(";", report.analyzedBranches);
        
        for (Map.Entry<String, ContributionStats> entry : report.contributions.entrySet()) {
            ContributionStats stats = entry.getValue();
            String developerBranches = String.join(";", stats.branches);
            
            csv.append(report.repositoryName).append(",")
               .append(entry.getKey()).append(",")
               .append(stats.commits).append(",")
               .append(stats.added).append(",")
               .append(stats.deleted).append(",")
               .append(stats.getNetChange()).append(",")
               .append(stats.files.size()).append(",")
               .append(developerBranches).append(",")
               .append(analyzedBranches).append("\n");
        }
    }
    
    Files.write(csvFile, csv.toString().getBytes());
}

// Complete the printSummary method (missing from first file):
public void printSummary(List<ReportData> reports) {
    System.out.println("\n" + "=".repeat(60));
    System.out.println("DAILY CODE CONTRIBUTION REPORT (ALL BRANCHES)");
    System.out.println(LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy")));
    System.out.println("=".repeat(60));
    
    int totalCommits = 0;
    int totalAdded = 0;
    int totalDeleted = 0;
    int totalBranches = 0;
    int successfulRepos = 0;
    int failedRepos = 0;
    
    for (ReportData report : reports) {
        System.out.println("\n📁 " + report.repositoryName + " (" + report.repositoryType + ")");
        
        if (!report.success) {
            failedRepos++;
            System.out.println("   ❌ Error: " + report.errorMessage);
            
            // Categorize error types
            if (report.errorMessage.contains("not found")) {
                System.out.println("   💡 Suggestion: Check repository owner/name in configuration");
            } else if (report.errorMessage.contains("Authentication failed")) {
                System.out.println("   💡 Suggestion: Check API token permissions");
            } else if (report.errorMessage.contains("Access denied")) {
                System.out.println("   💡 Suggestion: Repository may be private or token needs more permissions");
            }
            continue;
        }
        
        successfulRepos++;
        System.out.println("   ✅ Repository validated successfully");
        System.out.println("   🌿 Analyzed " + report.analyzedBranches.size() + " branches: " + 
                          String.join(", ", report.analyzedBranches));
        
        if (report.contributions.isEmpty()) {
            System.out.println("   📊 No contributions found today across all analyzed branches");
        } else {
            System.out.println("   📊 " + report.getTotalCommits() + " commits, +" + 
                report.getTotalLinesAdded() + "/-" + report.getTotalLinesDeleted() + " lines");
            
            report.contributions.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().commits, e1.getValue().commits))
                .forEach(entry -> {
                    ContributionStats stats = entry.getValue();
                    System.out.println("     👤 " + entry.getKey() + ": " + stats.toString());
                    System.out.println("        🌿 Branches: " + String.join(", ", stats.branches));
                });
        }
        
        totalCommits += report.getTotalCommits();
        totalAdded += report.getTotalLinesAdded();
        totalDeleted += report.getTotalLinesDeleted();
        totalBranches += report.analyzedBranches.size();
    }
    
    System.out.println("\n" + "=".repeat(60));
    System.out.println("📊 SUMMARY");
    System.out.println("✅ Successful repositories: " + successfulRepos);
    if (failedRepos > 0) {
        System.out.println("❌ Failed repositories: " + failedRepos);
    }
    System.out.println("📈 TOTAL: " + totalCommits + " commits across " + totalBranches + " branches");
    System.out.println("📈 LINES: +" + totalAdded + "/-" + totalDeleted + " (net: " + (totalAdded - totalDeleted) + ")");
    System.out.println("=".repeat(60));
}
}