# Test Plan: Configurable Workspace Paths

## Overview
This document provides test cases to verify that the configurable workspace paths feature works correctly on both Linux and macOS.

## Changes Made
1. Updated `core/lib/config.clj` to convert Path objects to strings for compatibility
2. Updated `core/bin/crucible.clj` to use configured paths instead of hardcoded ones
3. Functions now load config and use the configured workspace paths

## Test Cases

### Test 1: Default Configuration
**Purpose**: Verify the system works with default configuration

**Steps**:
1. Ensure no `crucible.edn` file exists in the project directory
2. Run: `echo "test output" | bb crucible pipe "test command"`
3. Verify output shows the configured path (e.g., `/home/kyle/work/logs/daily/` on Linux)
4. Check the file was created at the correct location

**Expected Result**:
- Log file created in the user's configured workspace directory
- Path shown in output matches the configuration

### Test 2: Custom Configuration
**Purpose**: Verify custom paths work correctly

**Steps**:
1. Create a `crucible.edn` file with custom paths:
   ```edn
   {:workspace {:root-dir "/tmp/crucible-custom"
                :logs-dir "my-logs"
                :tickets-dir "my-tickets"
                :docs-dir "my-docs"}}
   ```
2. Run: `echo "custom test" | bb crucible pipe "custom command"`
3. Verify the output shows `/tmp/crucible-custom/my-logs/daily/`
4. Check the file exists at that location

**Expected Result**:
- Log file created in the custom configured location
- System respects the custom configuration

### Test 3: Path Separator Compatibility
**Purpose**: Ensure paths work correctly on macOS

**Steps** (on macOS):
1. Run: `bb crucible jira-check`
2. Run: `echo "mac test" | bb crucible pipe "mac command"`
3. Verify paths use forward slashes and work correctly

**Expected Result**:
- All commands work without path-related errors
- Paths use forward slashes on both platforms

### Test 4: Environment Variable Override
**Purpose**: Verify environment variables can override workspace root

**Steps**:
1. Set environment variable: `export CRUCIBLE_WORKSPACE_DIR=/tmp/env-workspace`
2. Run: `echo "env test" | bb crucible pipe "env command"`
3. Verify the path uses the environment variable value

**Expected Result**:
- Environment variable overrides config file settings
- Log created in `/tmp/env-workspace/logs/daily/`

### Test 5: Home Directory Expansion
**Purpose**: Verify ~ expansion works in config

**Steps**:
1. Create config with: `{:workspace {:root-dir "~/crucible-workspace"}}`
2. Run: `echo "home test" | bb crucible pipe "home command"`
3. Verify ~ is expanded to the user's home directory

**Expected Result**:
- ~ is properly expanded to user's home directory
- Paths work correctly with home directory expansion

## Platform-Specific Notes

### Linux
- Tested and verified on the development machine
- Uses forward slashes for paths
- Default workspace: `/home/kyle/work`

### macOS
- Uses forward slashes (same as Linux)
- Default workspace should be configured in user's home config
- Test with both absolute and relative paths

## Verification Commands

Run these commands to quickly verify the implementation:

```bash
# Check configuration is loaded correctly
bb -e '(do (load-file "core/lib/config.clj")
           (require (quote [lib.config :as config]))
           (let [cfg (config/load-config)]
             (println "Workspace paths:")
             (println "  logs-dir:" (get-in cfg [:workspace :logs-dir]))
             (println "  Type:" (type (get-in cfg [:workspace :logs-dir])))))'

# Test pipe command
echo "test" | bb crucible pipe "test command"

# Check Jira integration still works
bb crucible jira-check
```

## Success Criteria
- [ ] All paths are configurable via config file
- [ ] Paths are stored as strings (not Path objects)
- [ ] System works on Linux
- [ ] System works on macOS
- [ ] Environment variables can override config
- [ ] Home directory expansion works
- [ ] No hardcoded paths remain in the codebase