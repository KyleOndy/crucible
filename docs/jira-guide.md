# Jira Integration Guide

Crucible provides powerful Jira integration for seamless ticket management and workflow automation. This guide covers everything you need to set up and use Jira features effectively.

## Table of Contents

- [Quick Start](#quick-start)
- [Getting Your Jira API Token](#getting-your-jira-api-token)
- [Configuration Setup](#configuration-setup)
- [Usage Examples](#usage-examples)
- [Configuration Reference](#configuration-reference)
- [Troubleshooting](#troubleshooting)
- [Advanced Features](#advanced-features)

## Quick Start

1. **Get your Jira API token** (see [detailed instructions](#getting-your-jira-api-token))
2. **Configure Crucible** with your Jira details
3. **Test your connection** with `c jira-check`
4. **Create your first ticket** with `c qs "Your ticket summary"`

```bash
# Test your configuration
c jira-check

# Create a quick story/task
c qs "Fix login timeout issue"
c quick-story "Add rate limiting to API"
```

## Getting Your Jira API Token

### Step 1: Access Atlassian Account Settings

1. Go to [https://id.atlassian.com/manage-profile/security/api-tokens](https://id.atlassian.com/manage-profile/security/api-tokens)
2. Click **"Create API token"**
3. Enter a label like "Crucible CLI Tool"
4. Copy the generated token immediately (you won't see it again)

### Step 2: Store Your Token Securely

**Option A: Using Pass Password Manager (Recommended)**
```bash
# Store in pass
pass insert work/jira-token
# Enter your token when prompted

# Your config will reference: "pass:work/jira-token"
```

**Option B: Environment Variable**
```bash
# Add to your shell profile (.bashrc, .zshrc, etc.)
export CRUCIBLE_JIRA_TOKEN="your_actual_token_here"
```

**Option C: Direct in Config File (Not Recommended)**
```edn
:api-token "your_actual_token_here"  ; Less secure
```

## Configuration Setup

### Configuration File Locations

Crucible looks for configuration in this order (later overrides earlier):

1. **Built-in defaults**
2. **User global config**:
   - `~/.config/crucible/config.edn` (XDG standard)
   - `~/.crucible/config.edn` (legacy)
3. **Project-specific config**: `./crucible.edn`
4. **Environment variables**

### Basic Configuration

Create `~/.config/crucible/config.edn` (or copy from `crucible.edn.example`):

```edn
{:jira {:base-url "https://yourcompany.atlassian.net"
        :username "your.email@company.com"
        :api-token "pass:work/jira-token"
        :default-project "PROJ"           ; Your project key
        :default-issue-type "Task"        ; "Task", "Story", "Bug", etc.
        :auto-assign-self true
        :auto-add-to-sprint true}}
```

### Environment Variable Setup

Add to your shell profile:

```bash
# Required
export CRUCIBLE_JIRA_URL="https://yourcompany.atlassian.net"
export CRUCIBLE_JIRA_USER="your.email@company.com" 
export CRUCIBLE_JIRA_TOKEN="your_token_or_pass_reference"

# Optional overrides
export CRUCIBLE_WORKSPACE_DIR="~/work/crucible-workspace"
```

## Usage Examples

### Creating Tickets

```bash
# Quick story creation
c qs "Fix authentication timeout"
c quick-story "Implement user dashboard"

# The system will:
# 1. Create the ticket in your default project
# 2. Auto-assign to you (if configured)
# 3. Add to current sprint (if configured)
# 4. Show you the ticket key and URL
```

### Example Output

```
Creating story...

✓ Created PROJ-1234: Fix authentication timeout
  Status: To Do
  Added to current sprint
  Assigned to: John Doe

Start working: c work-on PROJ-1234
```

### Testing Your Configuration

```bash
# Test connection and configuration
c jira-check

# Test with a specific ticket
c jira-check PROJ-1234
```

## Configuration Reference

### Jira Settings

| Setting | Required | Default | Description |
|---------|----------|---------|-------------|
| `:base-url` | ✅ | - | Your Atlassian instance URL |
| `:username` | ✅ | - | Your email address |
| `:api-token` | ✅ | - | API token or `pass:` reference |
| `:default-project` | ✅ | - | Project key for quick stories |
| `:default-issue-type` | ❌ | "Task" | Issue type for new tickets |
| `:default-story-points` | ❌ | 1 | Default story points |
| `:auto-assign-self` | ❌ | true | Auto-assign new tickets |
| `:auto-add-to-sprint` | ❌ | true | Add to current sprint |

### Workspace Settings

| Setting | Required | Default | Description |
|---------|----------|---------|-------------|
| `:root-dir` | ❌ | "workspace" | Root directory for workspace |
| `:logs-dir` | ❌ | "logs" | Logs directory (relative to root) |
| `:docs-dir` | ❌ | "docs" | Docs directory (relative to root) |

### Password Manager Integration

Use the `pass:` prefix for secure token storage:

```edn
:api-token "pass:work/jira-token"
:api-token "pass:company/atlassian/api-key" 
```

The value after `pass:` is passed to: `pass show <value>`

## Troubleshooting

### Common Issues

**"Configuration errors found"**
```bash
# Check what's missing
c jira-check

# Common fixes:
export CRUCIBLE_JIRA_URL="https://yourcompany.atlassian.net"
export CRUCIBLE_JIRA_USER="your.email@company.com"
export CRUCIBLE_JIRA_TOKEN="your_token"
```

**"Authentication failed"**
- Verify your API token is correct
- Check your username (should be email address)  
- Ensure your Jira URL is correct (don't forget https://)
- Test token manually: `curl -u email@company.com:token https://yourcompany.atlassian.net/rest/api/3/myself`

**"Project not found" / "No default project configured"**
```edn
{:jira {:default-project "PROJECTKEY"}}  ; Use your actual project key
```

**"Failed to retrieve password from pass"**
- Ensure `pass` is installed and configured
- Test: `pass show work/jira-token`
- Check the path matches your config

**"No current sprint found"**
- Your project might not use sprints
- Set `:auto-add-to-sprint false` in config
- Or ensure your board has an active sprint

### Debug Steps

1. **Test configuration**: `c jira-check`
2. **Test connection manually**:
   ```bash
   bb test-jira.clj
   ```
3. **Test with specific ticket**: `c jira-check PROJ-123`
4. **Check configuration loading**:
   ```bash
   bb -e "(load-file \"core/lib/config.clj\") (require '[lib.config :as config]) (config/load-config)"
   ```

## Advanced Features

### Auto-Assignment

When `:auto-assign-self true` (default), new tickets are automatically assigned to you based on your Jira user account.

### Sprint Integration

When `:auto-add-to-sprint true` (default), new tickets are automatically added to the current active sprint for your project.

**Requirements:**
- Your project must use a Scrum/Kanban board
- There must be an active sprint
- You must have permissions to modify the sprint

### Project-Specific Configuration

Override user settings for specific projects:

```edn
;; ./crucible.edn (in your project directory)
{:jira {:default-project "SPECIAL"
        :default-issue-type "Bug"
        :auto-add-to-sprint false}}
```

### Multiple Jira Instances

Use environment variables to switch between instances:

```bash
# Production environment
export CRUCIBLE_JIRA_URL="https://company.atlassian.net"
export CRUCIBLE_JIRA_TOKEN="pass:work/jira-prod"

# Development environment  
export CRUCIBLE_JIRA_URL="https://company-dev.atlassian.net"
export CRUCIBLE_JIRA_TOKEN="pass:work/jira-dev"
```

### Security Best Practices

1. **Never commit API tokens** to version control
2. **Use pass or environment variables** for token storage
3. **Use project-specific tokens** when possible
4. **Regularly rotate your API tokens**
5. **Set appropriate Jira permissions** for the token

## Getting Help

- **Test your setup**: `c jira-check`
- **View configuration locations**: `c help`
- **Check logs**: `tail -f workspace/logs/*.log`
- **Manual testing**: `bb test-jira.clj TICKET-ID`

For more help, see the main [README.md](../README.md) or check the [troubleshooting section](#troubleshooting) above.