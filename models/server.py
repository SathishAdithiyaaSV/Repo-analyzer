from flask import Flask, request, jsonify
import re
import os
from pathlib import Path
import json
from typing import Dict, List, Tuple, Optional
import hashlib
import time

app = Flask(__name__)

class CodeAnalyzer:
    def __init__(self):
        self.language_patterns = {
            'python': [r'\.py$', r'import\s+\w+', r'def\s+\w+', r'class\s+\w+', r'if\s+__name__\s*==\s*["\']__main__["\']'],
            'java': [r'\.java$', r'public\s+class', r'import\s+[\w.]+;', r'public\s+static\s+void\s+main', r'@\w+'],
            'javascript': [r'\.js$', r'function\s+\w+', r'const\s+\w+', r'let\s+\w+', r'var\s+\w+', r'=>'],
            'typescript': [r'\.ts$', r'interface\s+\w+', r'type\s+\w+', r':\s*\w+', r'export\s+'],
            'c++': [r'\.(cpp|cc|cxx)$', r'#include\s*<', r'using\s+namespace', r'std::', r'int\s+main'],
            'c': [r'\.c$', r'#include\s*<', r'int\s+main', r'printf\s*\(', r'malloc\s*\('],
            'html': [r'\.html?$', r'<html>', r'<div', r'<span', r'<!DOCTYPE'],
            'css': [r'\.css$', r'\{[^}]*\}', r'@media', r'#\w+', r'\.\w+'],
            'sql': [r'\.sql$', r'SELECT\s+', r'INSERT\s+', r'UPDATE\s+', r'DELETE\s+', r'CREATE\s+TABLE'],
            'shell': [r'\.sh$', r'#!/bin/bash', r'#!/bin/sh', r'echo\s+', r'grep\s+'],
            'go': [r'\.go$', r'package\s+\w+', r'import\s+\(', r'func\s+\w+', r'go\s+\w+'],
            'rust': [r'\.rs$', r'fn\s+\w+', r'let\s+\w+', r'struct\s+\w+', r'impl\s+'],
            'php': [r'\.php$', r'<\?php', r'\$\w+', r'function\s+\w+', r'class\s+\w+'],
            'ruby': [r'\.rb$', r'def\s+\w+', r'class\s+\w+', r'require\s+', r'puts\s+'],
            'swift': [r'\.swift$', r'func\s+\w+', r'var\s+\w+', r'let\s+\w+', r'class\s+\w+'],
            'kotlin': [r'\.kt$', r'fun\s+\w+', r'val\s+\w+', r'var\s+\w+', r'class\s+\w+'],
        }
        
        self.code_patterns = {
            'api_endpoint': [r'@app\.route', r'@RequestMapping', r'app\.(get|post|put|delete)', r'router\.(get|post|put|delete)'],
            'database_query': [r'SELECT\s+.*FROM', r'INSERT\s+INTO', r'UPDATE\s+.*SET', r'DELETE\s+FROM', r'\.find\(', r'\.save\('],
            'test_code': [r'def\s+test_', r'@Test', r'it\(.*should', r'describe\(', r'assert', r'expect\('],
            'configuration': [r'\.properties$', r'\.yaml$', r'\.yml$', r'\.json$', r'\.xml$', r'config'],
            'authentication': [r'login', r'password', r'token', r'auth', r'jwt', r'oauth'],
            'error_handling': [r'try\s*:', r'catch\s*\(', r'except\s*:', r'throw\s+', r'raise\s+'],
            'logging': [r'console\.log', r'print\(', r'logger\.', r'log\.', r'System\.out'],
            'async_code': [r'async\s+def', r'await\s+', r'Promise\s*<', r'CompletableFuture'],
            'ui_component': [r'<div', r'<span', r'<button', r'class\s*=', r'id\s*=', r'render\s*\('],
            'data_processing': [r'map\s*\(', r'filter\s*\(', r'reduce\s*\(', r'for\s+.*in\s+', r'\.stream\(\)'],
            'security': [r'encrypt', r'decrypt', r'hash', r'ssl', r'https', r'certificate'],
            'performance': [r'cache', r'optimize', r'performance', r'benchmark', r'profile'],
            'refactoring': [r'TODO', r'FIXME', r'deprecated', r'@Deprecated', r'# TODO'],
        }
        
        self.complexity_indicators = {
            'high': [r'for\s+.*for\s+', r'while\s+.*while\s+', r'if\s+.*if\s+.*if\s+', 
                    r'try\s+.*except\s+.*except\s+', r'switch\s+.*case\s+.*case\s+.*case\s+'],
            'medium': [r'for\s+.*in\s+', r'while\s+', r'if\s+.*else\s+', r'try\s+.*except\s+', 
                      r'switch\s+.*case\s+', r'class\s+.*extends\s+'],
            'low': [r'def\s+\w+', r'function\s+\w+', r'var\s+\w+', r'let\s+\w+', r'const\s+\w+']
        }

    def detect_language(self, file_name: str, code_content: str) -> str:
        """Detect programming language based on file extension and code patterns."""
        file_name_lower = file_name.lower()
        code_lower = code_content.lower()
        
        language_scores = {}
        
        for language, patterns in self.language_patterns.items():
            score = 0
            for pattern in patterns:
                if re.search(pattern, file_name_lower, re.IGNORECASE):
                    score += 3  # File extension match gets higher score
                if re.search(pattern, code_content, re.IGNORECASE | re.MULTILINE):
                    score += 1
            language_scores[language] = score
        
        # Return language with highest score, or 'unknown' if no matches
        if language_scores:
            detected_language = max(language_scores, key=language_scores.get)
            if language_scores[detected_language] > 0:
                return detected_language
        
        return 'unknown'

    def detect_code_type(self, code_content: str) -> str:
        """Detect the type/purpose of code based on patterns."""
        code_type_scores = {}
        
        for code_type, patterns in self.code_patterns.items():
            score = 0
            for pattern in patterns:
                matches = len(re.findall(pattern, code_content, re.IGNORECASE | re.MULTILINE))
                score += matches
            if score > 0:
                code_type_scores[code_type] = score
        
        if code_type_scores:
            return max(code_type_scores, key=code_type_scores.get)
        
        return 'general_code'

    def detect_patterns(self, code_content: str) -> List[str]:
        """Detect multiple code patterns in the content."""
        detected_patterns = []
        
        for pattern_name, patterns in self.code_patterns.items():
            for pattern in patterns:
                if re.search(pattern, code_content, re.IGNORECASE | re.MULTILINE):
                    detected_patterns.append(pattern_name)
                    break  # Only add once per pattern type
        
        return detected_patterns

    def assess_complexity(self, code_content: str) -> str:
        """Assess code complexity based on control structures and nesting."""
        complexity_scores = {'high': 0, 'medium': 0, 'low': 0}
        
        for complexity_level, patterns in self.complexity_indicators.items():
            for pattern in patterns:
                matches = len(re.findall(pattern, code_content, re.IGNORECASE | re.MULTILINE))
                complexity_scores[complexity_level] += matches
        
        # Weight the scores
        weighted_score = (complexity_scores['high'] * 3 + 
                         complexity_scores['medium'] * 2 + 
                         complexity_scores['low'] * 1)
        
        # Count lines of code (excluding empty lines and comments)
        code_lines = [line for line in code_content.split('\n') 
                     if line.strip() and not line.strip().startswith(('#', '//', '/*', '*'))]
        loc = len(code_lines)
        
        # Complexity assessment
        if weighted_score > 10 or loc > 100:
            return 'high'
        elif weighted_score > 5 or loc > 50:
            return 'medium'
        else:
            return 'low'

    def analyze_functionality(self, code_content: str, file_name: str, language: str) -> str:
        """Analyze what the code does based on patterns and context."""
        functionality_keywords = {
            'authentication': ['login', 'password', 'auth', 'token', 'session', 'jwt'],
            'database': ['query', 'select', 'insert', 'update', 'delete', 'database', 'sql'],
            'api': ['endpoint', 'route', 'request', 'response', 'api', 'rest'],
            'ui': ['component', 'render', 'view', 'template', 'html', 'css'],
            'testing': ['test', 'assert', 'expect', 'mock', 'unittest'],
            'configuration': ['config', 'settings', 'properties', 'environment'],
            'data_processing': ['process', 'transform', 'parse', 'convert', 'filter'],
            'utility': ['helper', 'util', 'common', 'shared', 'library'],
            'business_logic': ['calculate', 'validate', 'process', 'handle', 'manage'],
            'integration': ['connect', 'integrate', 'sync', 'import', 'export']
        }
        
        code_lower = code_content.lower()
        file_lower = file_name.lower()
        
        functionality_scores = {}
        
        for func_type, keywords in functionality_keywords.items():
            score = 0
            for keyword in keywords:
                # Check in code content
                score += len(re.findall(r'\b' + keyword + r'\b', code_lower))
                # Check in file name (weighted higher)
                if keyword in file_lower:
                    score += 3
            functionality_scores[func_type] = score
        
        if functionality_scores:
            primary_function = max(functionality_scores, key=functionality_scores.get)
            if functionality_scores[primary_function] > 0:
                return primary_function.replace('_', ' ').title()
        
        return 'General Purpose Code'

    def generate_summary(self, language: str, code_type: str, functionality: str, 
                        complexity: str, patterns: List[str], lines_added: int, 
                        lines_deleted: int) -> str:
        """Generate a human-readable summary of the code analysis."""
        
        pattern_desc = ""
        if patterns:
            # Get top 3 patterns
            top_patterns = patterns[:3]
            pattern_desc = f" with {', '.join(top_patterns[:2])}"
            if len(top_patterns) > 2:
                pattern_desc += f" and {top_patterns[2]}"
        
        change_desc = ""
        if lines_added > 0 and lines_deleted > 0:
            change_desc = f"Modified code (+{lines_added}/-{lines_deleted} lines)"
        elif lines_added > 0:
            change_desc = f"Added {lines_added} lines of code"
        elif lines_deleted > 0:
            change_desc = f"Removed {lines_deleted} lines of code"
        else:
            change_desc = "Code structure changes"
        
        summary = f"{change_desc} in {language.title()} for {functionality.lower()}"
        
        if pattern_desc:
            summary += pattern_desc
        
        summary += f". Code complexity is {complexity}."
        
        return summary

    def analyze_code_diff(self, file_name: str, added_code: str, deleted_code: str, 
                         full_diff: str, lines_added: int, lines_deleted: int) -> Dict:
        """Main analysis function that processes a code diff."""
        
        # Combine added and deleted code for comprehensive analysis
        combined_code = added_code + "\n" + deleted_code
        
        # Detect language
        language = self.detect_language(file_name, combined_code)
        
        # Detect code type
        code_type = self.detect_code_type(combined_code)
        
        # Detect patterns
        patterns = self.detect_patterns(combined_code)
        
        # Assess complexity
        complexity = self.assess_complexity(combined_code)
        
        # Analyze functionality
        functionality = self.analyze_functionality(combined_code, file_name, language)
        
        # Calculate confidence score
        confidence = self.calculate_confidence(language, code_type, patterns, combined_code)
        
        # Generate summary
        summary = self.generate_summary(language, code_type, functionality, complexity, 
                                      patterns, lines_added, lines_deleted)
        
        return {
            'language': language,
            'codeType': code_type,
            'functionality': functionality,
            'complexity': complexity,
            'patterns': patterns,
            'confidence': confidence,
            'summary': summary
        }

    def calculate_confidence(self, language: str, code_type: str, patterns: List[str], 
                           code_content: str) -> float:
        """Calculate confidence score for the analysis."""
        confidence = 0.0
        
        # Language detection confidence
        if language != 'unknown':
            confidence += 0.3
        
        # Code type confidence
        if code_type != 'general_code':
            confidence += 0.2
        
        # Patterns confidence
        if patterns:
            confidence += min(0.3, len(patterns) * 0.1)
        
        # Code length confidence (more code = more confidence)
        code_lines = len([line for line in code_content.split('\n') if line.strip()])
        if code_lines > 5:
            confidence += 0.1
        if code_lines > 20:
            confidence += 0.1
        
        return min(1.0, confidence)

# Initialize the analyzer
analyzer = CodeAnalyzer()

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint."""
    return jsonify({
        'status': 'healthy',
        'timestamp': time.time(),
        'service': 'Code Analysis API'
    })

@app.route('/analyze-code', methods=['POST'])
def analyze_code():
    """Main endpoint for code analysis."""
    try:
        # Get request data
        data = request.get_json()
        
        if not data:
            return jsonify({'error': 'No JSON data provided'}), 400
        
        # Extract required fields
        file_name = data.get('fileName', '')
        added_code = data.get('addedCode', '')
        deleted_code = data.get('deletedCode', '')
        full_diff = data.get('fullDiff', '')
        lines_added = data.get('linesAdded', 0)
        lines_deleted = data.get('linesDeleted', 0)
        
        # Validate input
        if not file_name:
            return jsonify({'error': 'fileName is required'}), 400
        
        if not added_code and not deleted_code and not full_diff:
            return jsonify({'error': 'At least one of addedCode, deletedCode, or fullDiff is required'}), 400
        
        # Perform analysis
        result = analyzer.analyze_code_diff(
            file_name=file_name,
            added_code=added_code,
            deleted_code=deleted_code,
            full_diff=full_diff,
            lines_added=lines_added,
            lines_deleted=lines_deleted
        )
        
        return jsonify(result)
        
    except Exception as e:
        return jsonify({
            'error': 'Internal server error',
            'message': str(e)
        }), 500

@app.route('/analyze-batch', methods=['POST'])
def analyze_batch():
    """Batch analysis endpoint for multiple files."""
    try:
        data = request.get_json()
        
        if not data or 'files' not in data:
            return jsonify({'error': 'files array is required'}), 400
        
        files = data['files']
        if not isinstance(files, list):
            return jsonify({'error': 'files must be an array'}), 400
        
        results = []
        
        for file_data in files:
            try:
                result = analyzer.analyze_code_diff(
                    file_name=file_data.get('fileName', ''),
                    added_code=file_data.get('addedCode', ''),
                    deleted_code=file_data.get('deletedCode', ''),
                    full_diff=file_data.get('fullDiff', ''),
                    lines_added=file_data.get('linesAdded', 0),
                    lines_deleted=file_data.get('linesDeleted', 0)
                )
                result['fileName'] = file_data.get('fileName', '')
                results.append(result)
            except Exception as e:
                results.append({
                    'fileName': file_data.get('fileName', ''),
                    'error': str(e)
                })
        
        return jsonify({'results': results})
        
    except Exception as e:
        return jsonify({
            'error': 'Internal server error',
            'message': str(e)
        }), 500

@app.route('/languages', methods=['GET'])
def get_supported_languages():
    """Get list of supported programming languages."""
    return jsonify({
        'languages': list(analyzer.language_patterns.keys()),
        'total': len(analyzer.language_patterns)
    })

@app.route('/patterns', methods=['GET'])
def get_code_patterns():
    """Get list of detectable code patterns."""
    return jsonify({
        'patterns': list(analyzer.code_patterns.keys()),
        'total': len(analyzer.code_patterns)
    })

@app.errorhandler(404)
def not_found(error):
    return jsonify({'error': 'Endpoint not found'}), 404

@app.errorhandler(405)
def method_not_allowed(error):
    return jsonify({'error': 'Method not allowed'}), 405

@app.errorhandler(500)
def internal_error(error):
    return jsonify({'error': 'Internal server error'}), 500

if __name__ == '__main__':
    print("Starting Code Analysis Flask Server...")
    print("Available endpoints:")
    print("  GET  /health - Health check")
    print("  POST /analyze-code - Analyze single code diff")
    print("  POST /analyze-batch - Analyze multiple code diffs")
    print("  GET  /languages - List supported languages")
    print("  GET  /patterns - List detectable patterns")
    print("\nServer starting on http://localhost:5000")
    
    app.run(host='0.0.0.0', port=5000, debug=True)