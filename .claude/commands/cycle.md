---
name: cycle
description: Meta-command for workflow orchestration and guided navigation through development cycles
---

# Cycle Command

Interactive workflow orchestration that guides you through the complete development cycle, routing to appropriate commands based on current context and session state. Perfect when you're unsure which command to use next or want guided workflow assistance.

## Purpose

The `/cycle` command acts as an intelligent workflow dispatcher that:

1. **Assesses current state** (git, TASKS.md, session context)
2. **Recommends next actions** based on development cycle stage
3. **Routes to appropriate commands** with pre-filled context
4. **Maintains session continuity** across command transitions

## Usage

```bash
/cycle
```

No parameters needed - the command analyzes current state and provides interactive guidance.

## Workflow Decision Tree

### 1. Session State Analysis

Automatically assess current development state:

```bash
# Git state assessment
git status --porcelain
git log --oneline -1

# TASKS.md progress scan  
grep -E "^\s*- \[ \]" core/TASKS.md | head -5

# Environment validation
bb --version > /dev/null && echo "BB_OK" || echo "BB_MISSING"
clj-kondo --version > /dev/null && echo "KONDO_OK" || echo "KONDO_MISSING"

# Session context (if TodoWrite active)
[Check for active todos and session focus]
```

### 2. Cycle Stage Detection

Determine where you are in the development cycle:

```markdown
# Stage Detection Logic:

## Fresh Session (No active work):
- Clean git status
- No active TodoWrite session
- → Route to: /start-work

## Planning Stage (Work identified but not started):
- Clean/minimal changes in git
- No focused TodoWrite session
- TASKS.md has uncompleted tasks
- → Route to: /next-tasks or /focus-task

## Active Development (Work in progress):
- Uncommitted changes in git
- Active TodoWrite session
- → Route to: Continue work or /quick-commit

## Ready to Commit (Work completed):
- Changes ready for commit
- TodoWrite tasks completed
- → Route to: /commit-work or /quick-commit

## Verification Needed (Recently committed):
- Recent commit exists
- Need to verify changes work
- → Route to: /check-last-commit or /quick-check

## Error/Blocked State:
- Failed validation
- Conflicting state
- → Route to: Problem resolution guidance
```

### 3. Interactive Decision Making

Present context-aware options to the user:

```markdown
## Current State: [Detected State]

### 📊 Analysis:
- Git Status: [Clean/Modified/Staged]
- Recent Commits: [Last commit summary]
- TASKS.md Progress: [Current phase/section]
- Session Focus: [Active todos or None]
- Environment: [Tools available]

### 🎯 Recommended Actions:

1. **[Primary Recommendation]** - [Description]
   Command: /[command-name]
   Reason: [Why this is recommended]

2. **[Secondary Option]** - [Description]  
   Command: /[command-name]
   Reason: [Alternative approach]

3. **[Other Options]** - [Description]
   Command: /[command-name]
   Reason: [When this might be appropriate]

### ❓ What would you like to do?
[Interactive selection or auto-route based on clear signals]
```

## State-Specific Routing

### Fresh Session Start

```markdown
🌅 Fresh Development Session

Detected: Clean git status, no active work

Recommendations:
1. **Start Work Session** (/start-work)
   - Initialize environment and context
   - Review TASKS.md progress
   - Set session focus

Auto-routing to /start-work...
```

### Planning Required

```markdown
📋 Planning Stage

Detected: Minimal changes, no clear session focus

Current TASKS.md status:
- Phase [X.Y]: [N] uncompleted tasks
- Last work: [Previous session summary]

Recommendations:
1. **Analyze Next Tasks** (/next-tasks)
   - Review upcoming work in Phase [X.Y]
   - Get implementation plan
   
2. **Focus on Specific Task** (/focus-task "[task-name]")
   - Jump directly to task setup
   - Start implementation immediately

Which approach? [1/2 or auto-select based on context]
```

### Development in Progress

```markdown
⚡ Active Development

Detected: Modified files, active TodoWrite session

Current Session:
- Focus: [Current task/section]
- Progress: [X of Y] todos completed
- Files Modified: [List of changed files]

Recommendations:
1. **Continue Current Work**
   - [Next step from TodoWrite]
   
2. **Commit Current Progress** (/quick-commit)
   - Save incremental work
   - Continue with clean state
   
3. **Review Session Status**
   - [TodoWrite summary]
   - [Time in session estimate]

Continue working or need guidance? [Continue/Commit/Review]
```

### Ready to Commit

```markdown
💾 Ready to Commit

Detected: Completed changes, work ready for commit

Changes Summary:
- Files Modified: [List with line counts]
- Current Focus: [Task/section being worked on]
- TodoWrite Status: [Completed todos summary]

Commit Type Recommendation:
1. **Quick Commit** (/quick-commit) - Small, focused changes
2. **Full Commit** (/commit-work) - Major features, multiple files

Auto-selecting based on change scope: [recommendation + reasoning]
```

### Verification Needed

```markdown
🔍 Verification Required

Detected: Recent commit, need to verify functionality

Last Commit: [commit hash] [commit message]
Changes: [Files and scope]

Verification Type:
1. **Quick Check** (/quick-check) - Fast validation
2. **Full Check** (/check-last-commit) - Comprehensive analysis

Auto-selecting based on commit scope: [recommendation + reasoning]
```

### Error/Blocked States

```markdown
🚨 Issue Detected

Problem: [Specific issue identified]

Examples:
- Git conflicts need resolution
- Environment tools missing  
- TASKS.md inconsistent with current work
- Failed validation from previous command

Resolution Steps:
1. [Immediate action needed]
2. [Follow-up steps]
3. [Return to cycle when resolved]

Fix and re-run /cycle to continue
```

## Advanced Routing Logic

### Smart Defaults

```markdown
# When signals are clear, auto-route without prompting:

Clear Signals → Auto Route:
- Clean repo + morning time → /start-work
- Single task in progress → /focus-task [current-task]
- Small changes ready → /quick-commit
- Large changes ready → /commit-work
- Recent commit → /quick-check

Ambiguous Signals → Interactive:
- Multiple possible next steps
- Unclear change scope
- First time using cycle command
```

### Context Memory

```markdown
# Track session patterns to improve routing:

Session Patterns:
- Last command used: [command-name]
- Typical session length: [duration estimate]
- Preferred commit style: [quick vs full]
- Current working phase: [TASKS.md section]

Use patterns to:
- Pre-select likely next commands
- Suggest workflow optimizations
- Detect unusual patterns (possible errors)
```

### Integration Awareness

```markdown
# Consider cross-platform development cycle:

Linux Development:
- Full development capabilities
- Prefer comprehensive workflows
- Route to full analysis commands

Pre-Mac Sync:
- Ensure committed state
- Route to verification commands
- Prepare for platform switch

Mac Usage Preparation:
- Verify cross-platform compatibility
- Test key user workflows
- Route to integration testing
```

## Command Coordination

### Pre-Command Setup

```markdown
# Before routing to target command:

Setup Context:
1. Ensure environment is ready
2. Set appropriate working directory
3. Prepare any needed parameters
4. Clear stale TodoWrite if starting fresh

Example:
"Routing to /focus-task with task 'Update get-ticket signature'..."
[Sets up focus context before executing /focus-task]
```

### Post-Command Integration

```markdown
# After target command completes:

Integration Actions:
1. Update cycle state memory
2. Suggest natural next command
3. Maintain session continuity
4. Learn from command sequence

Example:
"/focus-task completed. Session ready for implementation.
Next: Begin TDD cycle or use /cycle for guidance."
```

### Error Recovery

```markdown
# When target command fails:

Recovery Options:
1. Diagnose why command failed
2. Suggest alternative approaches
3. Route to problem-solving commands
4. Return to cycle when ready

Example:
"/commit-work failed due to test failures.
Routing to /quick-check for diagnosis..."
```

## Usage Examples

### Example 1: Morning Startup

```bash
$ /cycle

🌅 Fresh Development Session
Git: Clean, Last commit: 2 hours ago
TASKS.md: Phase 1.2 (3 uncompleted tasks)

Auto-routing to /start-work...
[Executes /start-work command]

Session initialized. Use /cycle again for next steps.
```

### Example 2: Mid-Development

```bash
$ /cycle

⚡ Active Development
Focus: Update get-ticket signature (2 of 5 todos completed)
Files: core/jira.clj (modified)

Continue current work? [Y/n]
> y

Continue implementation. Use /cycle when ready to commit.
```

### Example 3: Uncertain State

```bash
$ /cycle

📋 Planning Stage - Multiple Options

TASKS.md Status: Phase 1.2 (3 uncompleted tasks)
Recent Work: Signature analysis completed

Options:
1. Analyze next tasks (/next-tasks)
2. Focus on "Update get-ticket signature" (/focus-task)
3. Start different task (/focus-task [other])

Which approach? [1/2/3]
> 2

Routing to /focus-task "Update get-ticket signature"...
```

## Benefits

1. **Reduces Decision Fatigue**: Clear guidance on what to do next
2. **Maintains Flow**: Seamless transitions between work stages
3. **Prevents Errors**: Catches inconsistent states before they become problems
4. **Learns Patterns**: Adapts to your workflow preferences over time
5. **Saves Time**: No need to remember which command to use when

## Integration with All Commands

The `/cycle` command acts as the central orchestrator for the entire command ecosystem:

```markdown
Command Ecosystem Integration:

Entry Points:
/cycle → /start-work → /next-tasks → /focus-task → [work] → /quick-commit → /quick-check → /cycle

Alternative Flows:
/cycle → /commit-work → /check-last-commit → /cycle
/cycle → direct problem resolution → /cycle

Always Available:
Use /cycle at any point when unsure of next steps
```

This meta-command transforms the command collection from individual tools into a cohesive, intelligent workflow system that adapts to your development patterns and guides you through optimal development cycles.
