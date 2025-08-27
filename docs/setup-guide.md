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

#### Optional: cpipe function for automatic command logging

Add to your shell config (`~/.bashrc`, `~/.zshrc`, etc.):

```bash
# Optional: cpipe function for automatic command logging
cpipe() {
    eval "$*" | c pipe "$*"
}
```

For Fish shell, create `~/.config/fish/functions/cpipe.fish`:

```fish
function cpipe
    eval $argv | c pipe "$argv"
end
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

### 3. Set Your Editor

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
2. `~/.config/crucible/config.edn` or `~/.crucible/config.edn` (user config)
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
- Check out `docs/cpipe-setup.md` for advanced cpipe configurations
- Configuration examples are in `crucible.edn.example`
