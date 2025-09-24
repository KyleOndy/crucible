# Crucible Troubleshooting Guide

This guide provides comprehensive troubleshooting information for Crucible CLI, focusing on debug flags, common issues, and diagnostic techniques.

## Debug Flags

The Crucible CLI supports comprehensive debugging flags to help diagnose issues across all integrated services.

### Available Debug Flags

- **`--debug`** - Enable all service debugging (AI, Jira, Sprint)  
- **`--debug-ai`** - Enable AI API call debugging (auth, requests, responses)
- **`--debug-jira`** - Enable Jira API and sprint detection debugging
- **`--debug-sprint`** - Enable sprint detection strategy debugging

These flags override config file debug settings and are essential for troubleshooting authentication and API integration issues.

### Debug Flag Usage

Debug flags can be added to any Crucible command to enable detailed diagnostic output:

```bash
# Add debug flags to any command
c qs --debug "test content"
c work-on --debug-jira PROJ-123
c inspect-ticket --debug-ai PROJ-456
```

## Common Troubleshooting Scenarios

### AI Authentication Issues

**Symptoms:**
- "Authentication failed" errors
- Empty or unexpected AI responses
- Connection timeouts

**Diagnosis:**
```bash
# Debug AI API calls, auth headers, and responses
c qs --ai-only --debug-ai "test content"
```

**What to look for:**
- Authentication header format and presence
- API endpoint URLs and response codes
- Request/response body content
- JSON parsing errors

### Jira Connection Problems

**Symptoms:**
- "Failed to connect to Jira" errors
- Ticket information not loading
- Sprint detection failures

**Diagnosis:**
```bash  
# Debug Jira API calls and authentication
c inspect-ticket --debug-jira TICKET-123
c work-on --debug-jira PROJ-456
```

**What to look for:**
- Jira API authentication status
- Board and project access permissions
- Network connectivity issues
- API rate limiting responses

### Sprint Detection Issues

**Symptoms:**
- "No active sprint found" warnings
- Tickets not added to correct sprint
- Sprint board access errors

**Diagnosis:**
```bash
# Debug sprint detection strategies and board queries
c qs --debug-sprint "new story"
c work-on --debug-sprint PROJ-789
```

**What to look for:**
- Sprint detection strategy execution
- Board search queries and results
- Active sprint identification logic
- Permission issues accessing sprint boards

### General Debugging

**For complex issues spanning multiple services:**

```bash
# Enable all debugging for comprehensive troubleshooting
c qs --debug "debug all services"
c work-on --debug PROJ-123
```

## Debug Output Information

When debug flags are enabled, you'll see detailed information about:

### AI Debug Output (`--debug-ai`)
- **API Endpoints**: Full URLs being called
- **Authentication Headers**: Auth token format and presence (tokens are masked)
- **Request Bodies**: Complete request payloads sent to AI services
- **Response Bodies**: Full API responses received
- **JSON Parsing**: Parsing steps and any errors encountered
- **Error Details**: Specific error messages and codes

### Jira Debug Output (`--debug-jira`)
- **API Calls**: All Jira REST API endpoints accessed
- **Authentication Status**: Login verification and token validation
- **Board Queries**: Project and board discovery operations
- **Ticket Operations**: Ticket creation, updates, and field access
- **Permission Checks**: User access verification for projects and boards
- **Rate Limiting**: API usage and throttling information

### Sprint Debug Output (`--debug-sprint`)
- **Detection Strategies**: Which sprint detection methods are tried
- **Board Searches**: Queries used to find relevant boards
- **Active Sprint Identification**: Logic for determining current sprint
- **Sprint Assignment**: Process of adding tickets to detected sprints
- **Fallback Behavior**: What happens when no active sprint is found

## Best Practices for Troubleshooting

### Using Specific Debug Flags

- **Use targeted flags** (`--debug-ai`, `--debug-jira`) to reduce noise when troubleshooting specific problems
- **Start specific, go general** - try service-specific flags before using `--debug`
- **The `--debug` flag** is useful for complex issues that span multiple services

### Debug Output Handling

- **Debug output goes to stderr**, so normal command output remains clean
- **Pipe stderr to a file** for detailed analysis: `c command 2> debug.log`
- **Use grep to filter** debug output: `c command --debug 2>&1 | grep "ERROR"`

### Progressive Troubleshooting

1. **Start with basic commands** - test with simple operations first
2. **Verify configuration** - check `c doctor` output for basic setup issues
3. **Test individual services** - isolate AI, Jira, and Sprint functionality
4. **Use minimal test cases** - try operations with known-good data
5. **Compare working vs. non-working** scenarios when possible

## Configuration Verification

Before diving into debug flags, verify basic configuration:

```bash
# Check overall system health
c doctor

# Test specific integrations
c jira-check
c jira-check KNOWN-TICKET-ID
```

## Common Error Patterns

### Authentication Errors

**Pattern**: `401 Unauthorized` or `403 Forbidden`
- **AI Services**: Check API key configuration and permissions
- **Jira**: Verify username, password/token, and server URL
- **Debug with**: `--debug-ai` or `--debug-jira`

### Network Connectivity

**Pattern**: Connection timeouts or DNS resolution errors
- **Check network access** to external services
- **Verify proxy settings** if behind corporate firewall
- **Test with curl** to isolate network vs. application issues

### Configuration Issues

**Pattern**: Missing or malformed configuration values
- **Run `c doctor`** for comprehensive configuration check
- **Check environment variables** and config file syntax
- **Verify password manager integration** if using `pass:` prefixes

### Service Availability

**Pattern**: `503 Service Unavailable` or timeout errors
- **Check service status** pages for external dependencies
- **Verify API endpoints** are correct and current
- **Test with minimal requests** to isolate the issue

## Getting Help

When reporting issues, include:

1. **Command executed** with debug flags
2. **Full debug output** (with sensitive information redacted)
3. **Expected vs. actual behavior**
4. **Environment information** from `c doctor`
5. **Configuration details** (without sensitive credentials)

## Notes for AI Sessions

When working with Claude Code or other AI assistants on Crucible issues:

- **Always suggest debug flags** when encountering authentication or API integration issues
- **Use specific debug flags** to reduce noise when troubleshooting targeted problems
- **The `--debug` flag** is useful for complex issues that span multiple services
- **Debug output provides essential context** for diagnosing integration problems
- **Recommend progressive troubleshooting** starting with `c doctor` and basic connectivity tests