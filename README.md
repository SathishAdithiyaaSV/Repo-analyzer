# Daily Code Contribution Report Generator

A Java application that automatically generates daily reports of code contributions across multiple Git repositories (GitHub, GitLab, BitBucket). The tool analyzes commits made today and provides detailed statistics including lines of code added/deleted, files modified, and contributor activity.

## Features

- **Multi-Repository Support**: Analyze multiple repositories simultaneously
- **Multiple Git Platforms**: Support for GitHub, GitLab, and BitBucket
- **Detailed Analytics**: Track commits, lines added/deleted, files modified per developer
- **Multiple Output Formats**: Generate reports in JSON, HTML, and CSV formats
- **Concurrent Processing**: Parallel analysis of repositories for improved performance
- **Authentication Support**: Handle private repositories with username/token authentication
- **Clean Visual Reports**: Beautiful HTML reports with summary statistics

## Prerequisites

- Java 8 or higher
- Maven 3.6 or higher
- Git (for repository cloning)

## Installation

### 1. Clone or Download

Clone this repository or download the source code:

```bash
git clone <your-repository-url>
cd daily-report-generator
```

### 2. Maven Dependencies

Create a `pom.xml` file in your project root:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>com.example</groupId>
    <artifactId>daily-report-generator</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    
    <name>Daily Report Generator</name>
    <description>Generate daily code contribution reports from Git repositories</description>
    
    <properties>
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    
    <dependencies>
        <!-- JGit for Git operations -->
        <dependency>
            <groupId>org.eclipse.jgit</groupId>
            <artifactId>org.eclipse.jgit</artifactId>
            <version>6.7.0.202309050840-r</version>
        </dependency>
        
        <!-- Jackson for JSON processing -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.15.2</version>
        </dependency>
        
        <!-- Jackson JSR310 module for Java 8 time support -->
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
            <version>2.15.2</version>
        </dependency>
        
        <!-- Optional: Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>2.0.7</version>
        </dependency>
    </dependencies>
    
    <build>
        <plugins>
            <!-- Maven Compiler Plugin -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>8</source>
                    <target>8</target>
                </configuration>
            </plugin>
            
            <!-- Maven Exec Plugin for easy execution -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
                <configuration>
                    <mainClass>com.example.DailyReportGenerator</mainClass>
                </configuration>
            </plugin>
            
            <!-- Maven Assembly Plugin for creating executable JAR -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <descriptorRefs>
                        <descriptorRef>jar-with-dependencies</descriptorRef>
                    </descriptorRefs>
                    <archive>
                        <manifest>
                            <mainClass>com.example.DailyReportGenerator</mainClass>
                        </manifest>
                    </archive>
                </configuration>
                <executions>
                    <execution>
                        <id>make-assembly</id>
                        <phase>package</phase>
                        <goals>
                            <goal>single</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### 3. Build the Project

```bash
mvn clean compile
```

## Configuration

### Initial Setup

Run the application for the first time to generate a sample configuration:

```bash
mvn exec:java
```

This will create a `repositories.json` file with sample configuration:

```json
[
  {
    "name": "MyProject",
    "url": "https://github.com/user/myproject.git",
    "type": "GITHUB",
    "username": "your-github-username",
    "password": "your-github-token",
    "active": true
  },
  {
    "name": "WebApp",
    "url": "https://gitlab.com/user/webapp.git",
    "type": "GITLAB",
    "active": true
  },
  {
    "name": "API",
    "url": "https://bitbucket.org/user/api.git",
    "type": "BITBUCKET",
    "active": true
  }
]
```

### Configuration Parameters

- **name**: Friendly name for the repository
- **url**: Git repository URL (HTTPS format recommended)
- **type**: Repository type (`GITHUB`, `GITLAB`, `BITBUCKET`, `SVN`)
- **username**: Username for authentication (optional for public repos)
- **password**: Password or Personal Access Token (optional for public repos)
- **active**: Whether to include this repository in reports (default: true)

### Authentication Setup

#### GitHub
1. Go to GitHub Settings → Developer settings → Personal access tokens
2. Generate a new token with `repo` scope
3. Use your GitHub username and the token as password

#### GitLab
1. Go to GitLab Settings → Access Tokens
2. Create a personal access token with `read_repository` scope
3. Use your GitLab username and the token as password

#### BitBucket
1. Go to Bitbucket Settings → App passwords
2. Create an app password with `Repositories: Read` permission
3. Use your Bitbucket username and the app password

## Usage

### Running the Application

After configuring `repositories.json`, run the report generator:

```bash
# Using Maven exec plugin
mvn exec:java

### Generated Reports

The application generates three types of reports in the `reports/` directory:

1. **JSON Report**: `daily-report-YYYY-MM-DD.json`
   - Machine-readable format with detailed statistics
   - Useful for further processing or integration

2. **HTML Report**: `daily-report-YYYY-MM-DD.html`
   - Beautiful web-based report with tables and styling
   - Open in any web browser for viewing

3. **CSV Report**: `daily-report-YYYY-MM-DD.csv`
   - Spreadsheet-compatible format
   - Easy to import into Excel or Google Sheets

## Sample Output

### Console Output
```
Analyzing 3 repositories...
Cloning MyProject...
Cloning WebApp...
Cloning API...

============================================================
DAILY CODE CONTRIBUTION REPORT
June 13, 2025
============================================================

📁 MyProject (GITHUB)
   📊 5 commits, +234/-67 lines
     👤 John Doe: Commits: 3, +180/-45 lines, 8 files
     👤 Jane Smith: Commits: 2, +54/-22 lines, 4 files

📁 WebApp (GITLAB)
   📊 No contributions today

📁 API (BITBUCKET)
   📊 2 commits, +89/-12 lines
     👤 Bob Johnson: Commits: 2, +89/-12 lines, 3 files

============================================================
📈 TOTAL: 7 commits, +323/-79 lines (net: +244)
============================================================

Reports saved to /path/to/reports
```

### HTML Report Preview
The HTML report includes:
- Executive summary with total statistics
- Repository-wise breakdown
- Developer contribution tables
- Clean, professional styling

## Project Structure

```
daily-report-generator/
├── src/main/java/com/example/
│   └── DailyReportGenerator.java
├── pom.xml
├── repositories.json          # Configuration file
├── reports/                   # Generated reports
│   ├── daily-report-2025-06-13.json
│   ├── daily-report-2025-06-13.html
│   └── daily-report-2025-06-13.csv
└── temp_repos/               # Temporary cloned repositories (auto-cleanup)
```

