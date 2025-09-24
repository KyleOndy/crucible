---
name: quick-check
description: Fast verification for small changes and quick iteration cycles
---

# Quick Check Command

Rapidly verify that small changes work correctly without the comprehensive analysis of the full `/check-last-commit` command. Perfect for validating bug fixes, minor improvements, and rapid iteration cycles.

## When to Use Quick Check

### Ideal After

- **Quick commits**: Verify `/quick-commit` changes work
- **Bug fixes**: Confirm fix resolves the issue
- **Minor changes**: Single-function or small-scope modifications
- **Documentation updates**: Verify formatting and accuracy
- **Configuration tweaks**: Confirm settings work as expected

### Use `/check-last-commit` Instead For

- **Major features**: Complex functionality with multiple files
- **Signature changes**: Function interface modifications
- **Breaking changes**: Modifications affecting existing code
- **Comprehensive verification**: When full analysis is needed

## Workflow

### 1. Identify Change Scope

Determine what type of verification is needed:

```bash
# Get the last commit details
git log -1 --oneline
git show --stat HEAD

# Understand what was changed
git diff HEAD~1 HEAD --name-only
```

### 2. Targeted Verification

Run checks appropriate to the change type:

#### For Code Changes

```bash
# Syntax validation
clj-kondo --lint core/

# Style check (if style-sensitive)
cljstyle check

# Basic functional test
bb [relevant-command]
```

#### For Configuration Changes

```bash
# Validate configuration loads
bb test-config.clj

# Test affected functionality
bb crucible [relevant-subcommand]
```

#### For Documentation Changes

```bash
# Markdown validation (if applicable)
markdownlint-cli2 **/*.md

# Visual verification of formatting
cat [changed-file] | head -20
```

### 3. Functional Spot Check

Quick test of the specific functionality that was changed:

```bash
# For jira.clj changes
bb crucible work-on TEST-123

# For daily-log.clj changes  
bb crucible log daily

# For commands.clj changes
bb crucible doctor

# For config.clj changes
bb test-config.clj && echo "Config OK"
```

### 4. Change-Specific Validation

Verify the specific issue that was addressed:

```markdown
# For bug fixes:
1. Reproduce the original issue scenario
2. Verify the bug no longer occurs
3. Check no regression in related functionality

# For improvements:
1. Test the improved behavior works
2. Verify performance/usability gains
3. Check no degradation in other areas

# For refactoring:
1. Verify behavior is unchanged
2. Run affected commands
3. Check no broken imports/dependencies
```

### 5. Quick Report

Generate concise verification status:

```markdown
## Quick Check Results

### Commit: [hash] [message]

### ✅ Passed:
- Syntax: clj-kondo clean
- Function: [specific test passed]
- Target: [bug fixed / improvement works]

### ⚠️ Notes:
- [Any minor observations]

### 🎯 Verified:
- [Specific functionality that was tested]

### ➡️ Ready to continue or next action
```

## Check Patterns by Change Type

### Bug Fix Verification

```bash
# 1. Reproduce original issue (should be fixed)
# 2. Test edge cases around the fix
# 3. Verify no regression in related features

Example:
bb crucible work-on "INVALID-123"  # Should handle gracefully now
bb crucible work-on "VALID-123"    # Should still work normally
```

### Configuration Change Verification

```bash
# 1. Validate config loads without errors
bb test-config.clj

# 2. Test affected functionality
bb crucible [subcommand-that-uses-config]

# 3. Verify backwards compatibility
[test with old config format if applicable]
```

### Code Improvement Verification

```bash
# 1. Test improved functionality
bb [command-that-uses-improved-code]

# 2. Basic performance check (if performance-related)
time bb [relevant-command]

# 3. Verify no breaking changes
bb crucible doctor  # General health check
```

### Documentation Update Verification

```bash
# 1. Format validation
markdownlint-cli2 [changed-files]

# 2. Content accuracy check
cat [file] | grep -A5 -B5 [changed-section]

# 3. Cross-reference validation
grep -r [references-to-changed-docs] core/
```

## Validation Levels

### Light Validation (5-10 seconds)

- **Use for**: Documentation, comments, trivial changes
- **Checks**: Syntax only, basic file integrity
- **Command**: `clj-kondo --lint core/ && echo "OK"`

### Standard Validation (30-60 seconds)

- **Use for**: Most quick commits, bug fixes, small improvements
- **Checks**: Syntax, style, functional test
- **Commands**: Full check pattern from workflow above

### Focused Validation (1-2 minutes)

- **Use for**: Changes to critical functions, config modifications
- **Checks**: Standard + specific scenario testing
- **Commands**: Standard + targeted edge case tests

## Common Validation Scenarios

### Jira Integration Changes

```bash
# Quick connection test
bb test-config.clj

# Basic functionality
bb crucible work-on TEST-123

# Error handling (if error handling was changed)
bb crucible work-on INVALID-123
```

### Daily Log Changes

```bash
# Log creation/opening
bb crucible log daily

# Entry addition
echo "Test entry" | bb pipe

# File operations
ls -la logs/daily/$(date +%Y-%m-%d).md
```

### Command Line Interface Changes

```bash
# Help text and basic commands
bb crucible --help
bb crucible doctor

# Error scenarios (if error handling changed)
bb crucible invalid-command
```

### Configuration System Changes

```bash
# Config loading
bb test-config.clj

# Environment variable override (if applicable)
CRUCIBLE_DEBUG=true bb test-config.clj

# Default value handling
mv .crucible.edn .crucible.edn.bak && bb test-config.clj
mv .crucible.edn.bak .crucible.edn
```

## Error Response

### If Quick Check Fails

1. **Document the failure** clearly
2. **Suggest immediate fix** if obvious
3. **Recommend full analysis** if complex

```markdown
❌ Quick Check Failed

Issue: [specific failure description]
Command: [command that failed]
Error: [error message or behavior]

Immediate Actions:
1. [Specific fix suggestion if known]
2. Use /check-last-commit for full analysis
3. Consider reverting commit if blocking
```

### If Unsure About Results

1. **Document uncertainty**
2. **Recommend comprehensive check**
3. **Suggest additional testing**

```markdown
⚠️ Quick Check Uncertain

Changes appear to work but:
- [Specific concern or uncertainty]
- [Area that might need more testing]

Recommendations:
1. Use /check-last-commit for thorough analysis
2. Test additional scenarios: [specific suggestions]
3. Consider manual testing of [specific workflows]
```

## Integration with Workflow

### Rapid Iteration Pattern

```
[change] → /quick-commit → /quick-check → [next change] → repeat
```

### Batch Verification Pattern

```
[change1] → /quick-commit → [change2] → /quick-commit → /quick-check (both)
```

### Escalation Pattern

```
/quick-check → (if uncertain) → /check-last-commit
```

## Expected Output Examples

### Successful Quick Check

```bash
$ /quick-check

Analyzing last commit: Fix null handling in ticket parsing

✅ Syntax: clj-kondo clean
✅ Style: cljstyle check passed  
✅ Function: bb crucible work-on TEST-123 (success)
✅ Edge case: bb crucible work-on "" (handled gracefully)

Quick check passed. Change is working correctly.

Next: Continue development or use /start-work for new session
```

### Failed Quick Check

```bash
$ /quick-check

Analyzing last commit: Update jira timeout configuration

✅ Syntax: clj-kondo clean
❌ Function: bb test-config.clj (timeout error)

Issue: Configuration change broke Jira connectivity
Suggestion: Check timeout value is reasonable (currently 5ms, too low?)

Recommend: Fix timeout value and /quick-commit again
```

This command provides rapid feedback for iterative development while maintaining quality standards appropriate to the change scope.
