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

#### Option A: Using Pass Password Manager (Recommended)

```bash
# Store in pass
pass insert work/jira-token
# Enter your token when prompted

# Your config will reference: "pass:work/jira-token"
```

#### Option B: Environment Variable

```bash
# Add to your shell profile (.bashrc, .zshrc, etc.)
export CRUCIBLE_JIRA_TOKEN="your_actual_token_here"
```

#### Option C: Direct in Config File (Not Recommended)

```edn
:api-token "your_actual_token_here"  ; Less secure
```

## Configuration Setup

### Configuration File Locations

Crucible looks for configuration in this order (later overrides earlier):

1. **Built-in defaults**
2. **User global config**: `~/.config/crucible/config.edn` (XDG standard)
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

# AI-enhanced ticket creation (requires AI configuration)
c qs "fix login bug" --ai                    # Create AI-enhanced story
c qs -e --ai                                 # Editor + AI enhancement
c qs -f ticket.md --ai                       # File input + AI enhancement

# Test AI enhancement without creating tickets (perfect for prompt tuning)
c qs "fix auth bug users cant login" --ai-only

# Force disable AI (overrides config)
c qs "exact title" --no-ai

# The system will:
# 1. Enhance content with AI (if enabled)
# 2. Create the ticket in your default project
# 3. Auto-assign to you (if configured)
# 4. Add to current sprint (if configured)
# 5. Show you the ticket key and URL
```

### AI Enhancement Features

When AI enhancement is enabled, Crucible will:

- **Improve grammar and spelling** in titles and descriptions
- **Make content more professional** while preserving your original meaning
- **Show before/after diff** when changes are made
- **Fall back gracefully** if AI is unavailable

Example AI enhancement:

```
Before: "fix auth bug users cant login"
After:  "Fix authentication bug - users cannot login"

Before: "the login form is broken and users r complaining"
After:  "The login form is broken and users are complaining"
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

### AI Enhancement Settings

| Setting | Required | Default | Description |
|---------|----------|---------|-------------|
| `:enabled` | ❌ | false | Enable AI content enhancement |
| `:gateway-url` | ✅* | - | AI gateway API endpoint URL |
| `:api-key` | ✅* | - | API key or `pass:` reference |
| `:timeout-ms` | ❌ | 5000 | Request timeout in milliseconds |
| `:prompt` | ❌ | Default prompt | Customizable enhancement instructions |

\* Required when `:enabled` is true

**Example AI Configuration:**

```edn
:ai {:enabled true
     :gateway-url "https://your-gateway.com/api/enhance"
     :api-key "pass:work/ai-gateway-key"
     :timeout-ms 8000
     :prompt "Enhance this Jira ticket for clarity and professionalism. Fix spelling and grammar. Keep the same general meaning but improve readability."}
```

### Password Manager Integration

Use the `pass:` prefix for secure token storage:

```edn
:api-token "pass:work/jira-token"
:api-token "pass:company/atlassian/api-key"
```

The value after `pass:` is passed to: `pass show <value>`

## Troubleshooting

### Common Issues

#### "Configuration errors found"

```bash
# Check what's missing
c jira-check

# Common fixes:
export CRUCIBLE_JIRA_URL="https://yourcompany.atlassian.net"
export CRUCIBLE_JIRA_USER="your.email@company.com"
export CRUCIBLE_JIRA_TOKEN="your_token"
```

#### "Authentication failed"

- Verify your API token is correct
- Check your username (should be email address)
- Ensure your Jira URL is correct (don't forget https://)
- Test token manually: `curl -u email@company.com:token https://yourcompany.atlassian.net/rest/api/3/myself`

#### "Project not found" / "No default project configured"

```edn
{:jira {:default-project "PROJECTKEY"}}  ; Use your actual project key
```

#### "Failed to retrieve password from pass"

- Ensure `pass` is installed and configured
- Test: `pass show work/jira-token`
- Check the path matches your config

#### "No current sprint found"

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

### Default Field Values (Story Points & Fix Versions)

You can configure Crucible to automatically set story points and fix versions when creating tickets using the `qs` command.

#### Configuration

Add these settings to your configuration file:

```clojure
{:jira {:default-story-points 3
        :story-points-field "customfield_10002"
        :default-fix-version-id "10100"}}
```

#### Finding Your Story Points Field ID

Story points are stored in a custom field. To find the correct field ID for your Jira instance:

##### Method 1: Use Crucible's field discovery

```bash
# This will show you available fields and their IDs
bb -cp core -e "(require '[lib.jira :as j] '[lib.config :as c])
                (let [config (c/load-config)]
                  (j/get-create-metadata (:jira config) \"YOUR-PROJECT\" \"Story\"))"
```

Look for fields with names like "Story Points", "Story Point Estimate", or similar.

##### Method 2: Check an existing story ticket

1. Open any Story ticket in your browser
2. Look at the URL - it should be like `https://your-jira.com/browse/PROJ-123`
3. Change the URL to `https://your-jira.com/rest/api/2/issue/PROJ-123`
4. Look for fields starting with `customfield_` that contain numeric values
5. The story points field typically has a name like "Story Points" or "Story Point Estimate"

##### Method 3: Common field IDs

Story points are commonly found in these custom fields:

- `customfield_10002`
- `customfield_10004`
- `customfield_10008`
- `customfield_10016`

#### Finding Your Fix Version ID

Fix versions use standard Jira version IDs. To find the correct ID for your project:

##### Method 1: Use Crucible's field discovery

```bash
# Get project versions and their IDs
bb -cp core -e "(require '[lib.jira :as j] '[lib.config :as c])
                (let [config (c/load-config)
                      response (j/jira-request (:jira config) :get \"/project/YOUR-PROJECT/versions\")]
                  (:body response))"
```

This will show all versions for your project with their IDs and names.

##### Method 2: Check an existing ticket with fix version

1. Open a ticket that has a fix version set
2. Change the URL to `https://your-jira.com/rest/api/2/issue/PROJ-123`
3. Look for the `fixVersions` field - it will show the ID and name:

   ```json
   "fixVersions": [{"id": "10100", "name": "2.1.0"}]
   ```

#### Example Configuration

```clojure
;; Example configuration with story points and fix version
{:jira {:base-url "https://your-company.atlassian.net"
        :username "your.email@company.com"
        :api-token "pass:jira/api-token"
        :default-project "PROJ"
        :default-issue-type "Story"
        :default-story-points 5      ; Default story points for new tickets
        :story-points-field "customfield_10002"  ; Your story points field ID
        :default-fix-version-id "10100"          ; Default fix version ID
        :auto-assign-self true
        :auto-add-to-sprint true}}
```

#### How It Works

When you create a ticket with `c qs "My story"`, Crucible will:

1. Create the ticket with your title and description
2. If `default-story-points` is set AND `story-points-field` is configured:
   - Add the default story points to the ticket
   - Only if story points aren't already specified in `custom-fields`
3. If `default-fix-version-id` is configured:
   - Add the fix version to the ticket
   - This is a standard Jira field, not a custom field
4. Apply any other configured custom fields

#### Alternative: Using Custom Fields Directly

You can also set story points (and override the default) using the custom fields configuration:

```clojure
{:jira {:custom-fields {"customfield_10002" 8}}}  ; Always use 8 story points
```

The `custom-fields` approach takes precedence over `default-story-points`.

**Note**: Fix versions cannot be overridden through custom fields since it's a standard Jira field. If you need different fix versions per ticket, omit `default-fix-version-id` from your config and set them manually.

#### Validation

To test your configuration:

```bash
# Dry run to see what would be created
c qs "Test story" --dry-run

# This will show you the story points and fix version values that would be set
```

If story points and fix versions appear in the dry run output, your configuration is working correctly.

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
