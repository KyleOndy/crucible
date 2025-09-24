# cpipe Setup Guide

The `cpipe` function provides automatic command logging for Crucible's pipe functionality. Instead of manually typing commands like `kubectl get pods | bb pipe "kubectl get pods"`, you can simply use `cpipe "kubectl get pods"`.

## Quick Setup

### Bash Users

Add this to your `~/.bashrc` or `~/.bash_profile`:

```bash
# Crucible cpipe function for automatic command logging
cpipe() {
    eval "$*" | bb pipe "$*"
}
```

### Zsh Users

Add this to your `~/.zshrc`:

```zsh
# Crucible cpipe function for automatic command logging
cpipe() {
    eval "$*" | bb pipe "$*"
}
```

### Fish Users

Create the file `~/.config/fish/functions/cpipe.fish`:

```fish
function cpipe
    eval $argv | bb pipe "$argv"
end
```

## Activation

After adding the function to your shell profile:

```bash
# Reload your profile
source ~/.bashrc    # For bash
source ~/.zshrc     # For zsh
# Fish functions are loaded automatically
```

Or simply restart your terminal.

## Usage Examples

### Basic Commands

```bash
cpipe "ls -la"
cpipe "ps aux | grep docker"
cpipe "kubectl get pods"
```

### Tee-like Behavior (Enhanced!)

Crucible's pipe functionality acts like an enhanced `tee` - it logs the output AND passes it through to the next command:

```bash
# Log kubectl output and continue processing
kubectl get pods | bb pipe "kubectl get pods" | grep Running | wc -l

# Log file contents and sort them
cat users.txt | bb pipe "cat users.txt" | sort | uniq

# Debug pipeline by logging intermediate results
ps aux | bb pipe "ps aux" | grep python | bb pipe "grep python" | awk '{print $2}'
```

### Complex Pipelines

```bash
cpipe "kubectl get pods | grep Running | awk '{print \$1}'"
cpipe "cat /var/log/syslog | grep ERROR | tail -10"
cpipe "docker ps | grep -v CONTAINER"
```

### With Command Substitution

```bash
cpipe "echo 'Current time: \$(date)'"
cpipe "ls -la \$(pwd)"
```

### With Environment Variables

```bash
cpipe "echo \$PATH | tr ':' '\n'"
cpipe "ls -la \$HOME"
```

## Advanced Setup Options

### Enhanced cpipe with Error Handling

For more robust error handling, you can use this enhanced version:

```bash
cpipe() {
    local cmd="$*"
    local exit_code

    # Execute the command and capture exit code
    eval "$cmd" | bb pipe "$cmd"
    exit_code=${PIPESTATUS[0]}  # bash
    # exit_code=$pipestatus[1]   # zsh (uncomment for zsh)

    # Show exit code if command failed
    if [ $exit_code -ne 0 ]; then
        echo "Command exited with code: $exit_code" | bb pipe "Exit code for: $cmd"
    fi

    return $exit_code
}
```

### cpipe with Timestamp in Command Log

```bash
cpipe() {
    local cmd="$*"
    local timestamp=$(date '+%H:%M:%S')
    eval "$cmd" | bb pipe "[$timestamp] $cmd"
}
```

### cpipe with Working Directory Context

```bash
cpipe() {
    local cmd="$*"
    local pwd_short=$(basename "$PWD")
    eval "$cmd" | bb pipe "[$pwd_short] $cmd"
}
```

### ctee - Tee-like Wrapper for Pipeline Debugging

For debugging complex pipelines, create a `ctee` function that combines execution and logging:

```bash
# Enhanced tee function for pipeline debugging
ctee() {
    local cmd="$*"
    eval "$cmd" | bb pipe "$cmd"
}
```

Usage in pipeline debugging:

```bash
# Debug each step of a complex pipeline
ctee "kubectl get pods" | ctee "grep Running" | ctee "awk '{print \$1}'" | head -5

# Log intermediate results while continuing processing
ps aux | ctee "ps aux" | grep python | ctee "grep python" | wc -l

# Capture and log data transformations
cat data.csv | ctee "cat data.csv" | sort | ctee "sort" | uniq | ctee "uniq" > final.csv
```

## Troubleshooting

### Common Issues

1. **Function not found after adding to profile**
   - Make sure you reloaded your shell profile: `source ~/.bashrc`
   - Or restart your terminal

2. **Complex commands with quotes not working**
   - Use single quotes to wrap the entire command: `cpipe 'echo "hello world"'`
   - Or escape inner quotes: `cpipe "echo \"hello world\""`

3. **Commands with pipes or redirections**
   - These work fine: `cpipe "ls | grep test"`
   - Avoid output redirection: `cpipe "ls > file"` (output won't reach bb pipe)
   - Use tee instead: `cpipe "ls | tee file"`

### Testing Your Setup

```bash
# Test basic functionality
cpipe "echo 'cpipe is working!'"

# Test with pipes
cpipe "echo -e 'line1\nline2\nline3' | grep line2"

# Test with complex command
cpipe "ps aux | head -5 | awk '{print \$1, \$11}'"
```

## Integration with Other Tools

### Git Aliases

Add to your `~/.gitconfig`:

```ini
[alias]
    logcmd = "!f() { git \"$@\" | bb pipe \"git $*\"; }; f"
```

Usage: `git logcmd status`, `git logcmd log --oneline -5`

### Docker Wrapper

```bash
dcpipe() {
    cpipe "docker $*"
}
```

Usage: `dcpipe ps`, `dcpipe logs container_name`

### Kubernetes Wrapper

```bash
kcpipe() {
    cpipe "kubectl $*"
}
```

Usage: `kcpipe get pods`, `kcpipe describe pod my-pod`

## Best Practices

1. **Use descriptive commands**: The logged command becomes your documentation
2. **Prefer cpipe for investigative commands**: Great for debugging sessions
3. **Use regular pipe for sensitive data**: `sensitive-command | bb pipe` (without logging the command)
4. **Group related commands**: Use cpipe for a series of related debugging commands

## Shell-Specific Notes

### Bash

- Works with bash 3.0+
- `$*` expands to all arguments as a single string
- PIPESTATUS array available for exit codes

### Zsh

- Works with zsh 4.0+
- `$*` behavior same as bash
- Use `$pipestatus` array for exit codes

### Fish

- Uses `$argv` instead of `$*`
- Functions are automatically loaded from `~/.config/fish/functions/`
- Use `$pipestatus` for exit codes
