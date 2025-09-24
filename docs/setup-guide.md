# Crucible Setup Guide

This guide walks you through setting up Crucible for daily productivity workflows.

## Available Commands

Crucible provides these core commands:

- `help` - Show command help
- `l` - Open today's daily log in your editor (shortcut)
- `log daily` - Open today's daily log in your editor
- `pipe [command]` - Pipe stdin to daily log (optionally log the command)
- `qs <summary>` - Create a quick Jira story

## Quick Start

### 1. Install Global Command

Run the setup script to install the `c` command globally:

```bash
./setup.sh
```

This creates a global `c` wrapper that works from anywhere on your system. No shell aliases needed.

#### Enhanced Command Logging with cpipe

The `cpipe` function provides automatic command logging for Crucible's pipe functionality. Instead of manually typing commands like `kubectl get pods | c pipe "kubectl get pods"`, you can simply use `cpipe "kubectl get pods"`.

##### Bash Users

Add this to your `~/.bashrc` or `~/.bash_profile`:

```bash
# Crucible cpipe function for automatic command logging
cpipe() {
    eval "$*" | c pipe "$*"
}
```

##### Zsh Users

Add this to your `~/.zshrc`:

```zsh
# Crucible cpipe function for automatic command logging
cpipe() {
    eval "$*" | c pipe "$*"
}
```

##### Fish Users

Create the file `~/.config/fish/functions/cpipe.fish`:

```fish
function cpipe
    eval $argv | c pipe "$argv"
end
```

##### Activation

After adding the function to your shell profile:

```bash
# Reload your profile
source ~/.bashrc    # For bash
source ~/.zshrc     # For zsh
# Fish functions are loaded automatically
```

Or simply restart your terminal.

##### Usage Examples

**Basic Commands:**

```bash
cpipe "ls -la"
cpipe "ps aux | grep docker"
cpipe "kubectl get pods"
```

**Tee-like Behavior (Enhanced!):**
Crucible's pipe functionality acts like an enhanced `tee` - it logs the output AND passes it through to the next command:

```bash
# Log kubectl output and continue processing
kubectl get pods | c pipe "kubectl get pods" | grep Running | wc -l

# Log file contents and sort them
cat users.txt | c pipe "cat users.txt" | sort | uniq

# Debug pipeline by logging intermediate results
ps aux | c pipe "ps aux" | grep python | c pipe "grep python" | awk '{print $2}'
```

**Complex Pipelines:**

```bash
cpipe "kubectl get pods | grep Running | awk '{print \$1}'"
cpipe "cat /var/log/syslog | grep ERROR | tail -10"
cpipe "docker ps | grep -v CONTAINER"
```

##### Advanced cpipe Options

**Enhanced cpipe with Error Handling:**
For more robust error handling, you can use this enhanced version:

```bash
cpipe() {
    local cmd="$*"
    local exit_code

    # Execute the command and capture exit code
    eval "$cmd" | c pipe "$cmd"
    exit_code=${PIPESTATUS[0]}  # bash
    # exit_code=$pipestatus[1]   # zsh (uncomment for zsh)

    # Show exit code if command failed
    if [ $exit_code -ne 0 ]; then
        echo "Command exited with code: $exit_code" | c pipe "Exit code for: $cmd"
    fi

    return $exit_code
}
```

**cpipe with Timestamp in Command Log:**

```bash
cpipe() {
    local cmd="$*"
    local timestamp=$(date '+%H:%M:%S')
    eval "$cmd" | c pipe "[$timestamp] $cmd"
}
```

**cpipe with Working Directory Context:**

```bash
cpipe() {
    local cmd="$*"
    local pwd_short=$(basename "$PWD")
    eval "$cmd" | c pipe "[$pwd_short] $cmd"
}
```

### 2. Configure Jira (Required for `qs` command)

Create a configuration file at `~/.config/crucible/config.edn` or `crucible.edn` in your project:

```clojure
{:jira {:base-url "https://yourcompany.atlassian.net"
        :username "your-email@company.com"
        :api-token "your-api-token"
        :default-project "PROJ"
        :default-issue-type "Task"
        :auto-assign-self true
        :auto-add-to-sprint true}}
```

#### Environment Variables (Alternative)

You can also use environment variables:

```bash
export CRUCIBLE_JIRA_URL="https://yourcompany.atlassian.net"
export CRUCIBLE_JIRA_USER="your-email@company.com"
export CRUCIBLE_JIRA_TOKEN="your-api-token"
```

### 3. Configure AI Enhancement (Optional)

Add AI-powered content enhancement to improve your Jira tickets:

```clojure
{:jira {:base-url "https://yourcompany.atlassian.net"
        :username "your-email@company.com"
        :api-token "your-api-token"
        :default-project "PROJ"
        :default-issue-type "Task"
        :auto-assign-self true
        :auto-add-to-sprint true}

 :ai {:enabled true
      :gateway-url "https://your-ai-gateway.com/api/enhance"
      :api-key "your-api-key"
      :timeout-ms 5000
      :prompt "Enhance this Jira ticket for clarity and professionalism. Fix spelling and grammar. Keep the same general meaning but improve readability."}}
```

The AI enhancement:

- **Improves grammar and spelling** in your ticket titles and descriptions
- **Makes content more professional** while preserving your original meaning
- **Works with all input methods** - command line, editor, and file input
- **Fails gracefully** - if AI is unavailable, it uses your original content
- **Customizable prompt** - tailor the enhancement style for your team

#### AI Environment Variables (Alternative)

```bash
export CRUCIBLE_AI_GATEWAY_URL="https://your-ai-gateway.com/api/enhance"
export CRUCIBLE_AI_API_KEY="your-api-key"
```

### 4. Set Your Editor

Make sure your `$EDITOR` environment variable is set:

```bash
export EDITOR=vim    # or nano, emacs, code, etc.
```

## Required Dependencies

### Core Requirements

1. **Babashka** - Clojure scripting runtime
   - Install from: https://github.com/babashka/babashka#installation
   - Nix: `nix-env -iA nixpkgs.babashka`

2. **Text Editor** - For daily log editing
   - Set in shell config: `export EDITOR=vim` (or `nvim`, `emacs`, etc.)

### Optional but Recommended

1. **cpipe function** - For automatic command logging
   - See setup instructions above

2. **Workspace directory** - Will be created automatically
   - Default location: `./workspace/` in the Crucible directory

## Command Usage

After running `./setup.sh`, you can use `c` commands from anywhere:

### Daily Log Management

```bash
# Open today's daily log (shortcut)
c l

# Open today's daily log (full command)
c log daily

# Pipe command output to daily log
kubectl get pods | c pipe "kubectl get pods"

# Use cpipe for automatic logging
cpipe "ls -la"
cpipe "docker ps"
```

### Jira Integration

```bash
# Create a quick story
c qs "Fix authentication timeout"
c quick-story "Add API rate limiting"

# AI-enhanced ticket creation (requires AI configuration)
c qs "fix login bug" --ai                    # Create AI-enhanced story
c qs "fix login bug" --ai-only               # Test AI enhancement only
c qs -e --ai                                 # Editor + AI enhancement
c qs -f ticket.md --ai                       # File input + AI enhancement

# Force disable AI (overrides config)
c qs "exact title" --no-ai
```

### Getting Help

```bash
# Show all commands
c help
```

## Advanced Configuration

### Password Manager Integration

Any config value can use the `pass:` prefix to fetch from the pass password manager:

```clojure
{:jira {:api-token "pass:work/jira-token"}}
```

### Workspace Configuration

Customize where files are stored:

```clojure
{:workspace {:root-dir "~/crucible-workspace"
             :logs-dir "logs"
             :docs-dir "docs"}}
```

### Configuration Loading Order

Configuration is loaded from (later overrides earlier):

1. Built-in defaults
2. `~/.config/crucible/config.edn` (user config)
3. `./crucible.edn` (project-specific config)
4. Environment variables (`CRUCIBLE_*`)
5. Password manager resolution (for `pass:` prefixed values)

## Troubleshooting

### Common Issues

1. **Command not found: bb**
   - Install Babashka: https://github.com/babashka/babashka#installation

2. **Jira authentication errors**
   - Check your API token is valid
   - Verify your Jira URL is correct
   - Ensure your username/email is correct

3. **Editor not opening for daily log**
   - Set `$EDITOR` environment variable: `export EDITOR=nano`
   - Add to your shell config file for persistence

4. **Workspace permissions**
   - Crucible will create the workspace directory automatically
   - Check write permissions in the current directory

### Testing Your Setup

```bash
# Test basic functionality
c help

# Test daily log (should open your editor)
c log daily

# Test pipe functionality
echo "test" | c pipe "echo test"

# Test Jira integration (requires configuration)
c qs "Test story"
```

## Getting More Help

- Run `c help` to see all available commands
- See the [Jira Integration Guide](jira-guide.md) for detailed Jira setup
- Configuration examples are in `crucible.edn.example`
