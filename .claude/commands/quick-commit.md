---
name: quick-commit
description: Create streamlined commits for small changes, bug fixes, and rapid iterations
---

# Quick Commit Command

Create focused, efficient commits for small changes while maintaining quality standards. Perfect for bug fixes, minor improvements, and rapid iteration cycles.

## When to Use Quick Commit

### Ideal Scenarios

- **Bug fixes**: Single-issue corrections
- **Minor improvements**: Small optimizations or cleanup
- **Documentation updates**: README, comments, or docstring changes
- **Configuration tweaks**: Small config or setting adjustments
- **Rapid iteration**: Quick changes during development cycles
- **Single-function changes**: Focused modifications to one function

### Use Regular `/commit-work` Instead For

- **Multiple related changes**: Complex features spanning multiple files
- **Signature refactoring**: Major structural changes
- **New features**: Substantial functionality additions
- **Breaking changes**: Modifications that affect existing interfaces

## Workflow

### 1. Quick Change Assessment

Verify the scope is appropriate for quick commit:

```bash
# Check what changed
git status

# Review the actual changes
git diff

# Ensure changes are focused and small (< 50 lines typically)
git diff --stat
```

### 2. Focused Validation

Run targeted checks appropriate to the change:

```bash
# For Clojure changes - syntax check
clj-kondo --lint core/

# For format-sensitive changes
cljstyle check

# For functional changes - quick test
bb [relevant-command-to-test]

# For config changes - validate config loads
bb test-config.clj
```

### 3. TodoWrite Quick Update

Update session tracking without heavy analysis:

```markdown
# Simple TodoWrite update:
1. Mark current task as completed if applicable
2. Note what was fixed/improved
3. Keep tracking minimal for quick changes
```

### 4. Streamlined Commit Creation

Generate focused commit message:

```bash
# Stage the changes
git add [specific-files-or-all]

# Create commit with concise but clear message
git commit -m "$(cat <<'EOF'
[Type]: [Brief description]

[Optional: 1-2 line explanation if context needed]

🤖 Generated with [Claude Code](https://claude.ai/code)

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

### 5. Quick Verification

Basic verification appropriate to change scope:

```bash
# Verify commit was created
git log -1 --oneline

# Quick functional test if applicable
bb [test-command]

# Check git status is clean
git status
```

## Commit Message Format

### Standard Types

- **Fix**: Bug corrections
- **Improve**: Performance or usability enhancements  
- **Refactor**: Code structure improvements (no behavior change)
- **Update**: Documentation, comments, or configuration
- **Add**: Small feature additions
- **Remove**: Cleanup or deletion

### Examples

```bash
# Bug fix
Fix: Handle null values in ticket-id parsing

# Performance improvement  
Improve: Cache compiled regex patterns in adf.clj

# Code cleanup
Refactor: Extract duplicate string validation logic

# Documentation
Update: Add docstring for parse-inline-formatting

# Configuration
Update: Increase Jira API timeout to 30 seconds

# Small feature
Add: Support for basic markdown in log entries
```

## Validation Levels

### Minimal Validation (for safe changes)

- Syntax check with clj-kondo
- Git status verification
- **Use for**: Documentation, comments, simple bug fixes

### Standard Validation (most changes)

- Syntax and style checks
- Basic functional test with bb command
- **Use for**: Logic changes, new small functions

### Enhanced Validation (risky changes)

- Full test suite if available
- Cross-platform considerations
- **Use for**: Changes affecting core workflows

## Integration with TASKS.md

### Task Completion

```markdown
# If change completes a task:
- Change [ ] to [x] in TASKS.md
- Include TASKS.md in the commit

# If change partially completes a task:
- Add progress note but keep task open
- Mention progress in commit message
```

### Task Discovery

```markdown
# If change reveals new tasks:
- Add new tasks to TASKS.md
- Note task additions in commit message
- Consider if new tasks should block current work
```

## Quick Commit vs Regular Commit Decision Tree

```
Change affects multiple files? → Regular Commit
Change modifies function signatures? → Regular Commit
Change requires extensive testing? → Regular Commit
Change is < 50 lines and focused? → Quick Commit
Change is bug fix or improvement? → Quick Commit
Change is documentation only? → Quick Commit
Unsure about scope? → Quick Commit (safer to be minimal)
```

## Common Quick Commit Scenarios

### Bug Fix Flow

1. Identify and fix bug
2. `git diff` to verify fix is focused
3. `clj-kondo --lint core/` for syntax
4. `bb [test-command]` for function test
5. `git add -A && /quick-commit`

### Documentation Update Flow

1. Update documentation/comments
2. `git diff` to review changes
3. `markdownlint-cli2` if markdown files changed
4. `git add -A && /quick-commit`

### Configuration Tweak Flow

1. Modify configuration
2. `bb test-config.clj` to validate
3. Test relevant functionality
4. `git add -A && /quick-commit`

## Error Handling

### If Validation Fails

1. **Fix the issue** before committing
2. **Re-run validation** to ensure fix works
3. **Consider scope expansion** - might need regular commit

### If Change Scope Grows

1. **Stop quick commit process**
2. **Use `/commit-work`** for comprehensive approach
3. **Document expanded scope** in regular commit

### If Unsure About Impact

1. **Default to `/commit-work`** for safety
2. **Get more context** with `/check-last-commit` after
3. **Learn for future** quick vs regular decisions

## Integration with Development Cycle

### Linux Development

- Primary use case for quick commits
- Full tooling available for validation
- Fast iteration cycles

### Mac Testing Preparation

- Ensure quick commits are complete and tested
- Batch multiple quick commits before Mac sync
- Test accumulated changes on Mac

### Session Flow

```
/start-work → [work] → /quick-commit → [more work] → /quick-commit → /commit-work
```

## Expected Output

```bash
# Quick commit execution example:

$ /quick-commit

Analyzing changes...
Files changed: 1 (core/jira.clj)
Lines changed: +3 -1

Running validation...
✅ clj-kondo: No issues found
✅ cljstyle: Format correct
✅ Basic test: bb crucible log daily (success)

Creating commit...
[more-ai 1a2b3c4] Fix: Handle empty sprint names in jira integration

Changes committed successfully.

Next: Continue work or use /quick-check for verification
```

This command enables rapid, quality-focused iteration while maintaining the project's high standards for commit quality and traceability.
