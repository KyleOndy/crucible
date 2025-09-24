---
name: start-work
description: Initialize development session with context gathering and environment validation
---

# Start Work Command

Initialize a productive development session by gathering context, validating environment, and setting focus for the work ahead.

## Workflow

### 1. Environment Validation

Check that all necessary tools are available:

```bash
# Verify core tools
bb --version
clj-kondo --version
cljstyle --version

# Check git status
git status --porcelain

# Verify working directory
pwd
ls -la core/
```

### 2. Repository Status Assessment

Understand current state:

```bash
# Check for uncommitted changes
git status

# Review recent commits for context
git log --oneline -5

# Check current branch
git branch --show-current

# Verify we're on expected branch (usually 'more-ai' or feature branch)
```

### 3. TASKS.md Progress Scan

Review current task status:

```markdown
# Read TASKS.md and identify:
1. **Current Phase**: Which phase (0-4) are we in?
2. **Active Section**: Which section has in-progress or next uncompleted tasks?
3. **Task Count**: How many tasks completed vs remaining in current section?
4. **Dependencies**: Are there any blockers or prerequisites?
```

### 4. Session Focus Setting

Establish clear session intent:

```markdown
# Based on TASKS.md analysis, determine:
1. **Primary Goal**: What should be accomplished this session?
2. **Scope**: Single task, section completion, or exploratory work?
3. **Time Estimate**: Quick iteration (< 1 hour) or deep work (> 2 hours)?
4. **Success Criteria**: How will we know the session was successful?
```

### 5. Context Preparation

Set up for productive work:

```bash
# Navigate to project root if needed
cd /path/to/crucible

# Check for any background processes that might interfere
ps aux | grep -E "(bb|clojure|java)" | grep -v grep

# Verify Jira connection if working on integration tasks
# (Optional - only if session involves Jira work)
bb test-config.clj
```

### 6. TodoWrite Initialization

Prepare session-level task tracking:

```markdown
# Initialize TodoWrite with session goals:
1. Clear any stale todos from previous sessions
2. Add 2-4 focused todos for this session
3. Mark session start time and focus area

# Example session todos:
- [ ] Complete signature update for get-ticket function
- [ ] Write tests for new signature
- [ ] Update all call sites
- [ ] Verify functionality with bb crucible
```

## Session Readiness Report

Generate a concise readiness assessment:

```markdown
## Development Session Status

### ✅ Environment Ready:
- Babashka: [version]
- clj-kondo: [version]  
- cljstyle: [version]
- Git status: [clean/has changes]

### 📋 Current Focus:
- **Phase**: [0-4] - [Phase Name]
- **Section**: [Section Name]
- **Tasks Remaining**: [X] uncompleted in current section
- **Session Goal**: [Primary objective]

### 🎯 Session Plan:
- **Scope**: [Single task/Section completion/Exploration]
- **Estimated Time**: [Duration]
- **Success Criteria**: [How to measure completion]

### 🔄 Next Commands:
- Use `/next-tasks` to analyze implementation approach
- Use `/focus-task [task-name]` to dive into specific work
- Use `/cycle` for guided workflow assistance

### ⚠️ Notes:
- [Any special considerations, dependencies, or blockers]
```

## Common Session Types

### Type 1: Signature Refactoring Session

**Focus**: Phase 1 signature implementation
**Typical Duration**: 2-3 hours
**Commands**: `/next-tasks` → `/focus-task` → `/commit-work` → `/check-last-commit`

### Type 2: Function Decomposition Session

**Focus**: Phase 2 structural changes
**Typical Duration**: 3-4 hours
**Commands**: `/next-tasks` → `/focus-task` → multiple `/quick-commit` cycles → `/commit-work`

### Type 3: Bug Fix Session

**Focus**: Quick fixes and maintenance
**Typical Duration**: 30-60 minutes
**Commands**: `/focus-task` → `/quick-commit` → `/quick-check`

### Type 4: Exploration Session

**Focus**: Understanding codebase or planning
**Typical Duration**: 1-2 hours
**Commands**: `/next-tasks` → research → update TASKS.md

## Troubleshooting

### Environment Issues

- **Missing tools**: Install via Nix or check PATH
- **Git conflicts**: Resolve before starting focused work
- **Dirty working tree**: Commit or stash changes

### Context Issues

- **Unclear next steps**: Run `/next-tasks` for analysis
- **TASKS.md out of date**: Update based on recent discoveries
- **Multiple competing priorities**: Choose one focus area per session

### Session Planning Issues

- **Scope too large**: Break into smaller, focused sessions
- **Unclear success criteria**: Define specific, measurable outcomes
- **Time pressure**: Choose appropriate session type and scope

## Integration with Development Cycle

### Linux Development Environment

- Full tooling available for comprehensive work
- Ideal for signature refactoring and complex changes
- Use heavy workflow commands (`/commit-work`, `/check-last-commit`)

### Pre-Mac Testing Preparation

- Ensure all changes are committed and tested
- Run full test suite if available
- Verify cross-platform compatibility concerns

### Session Handoff

- Document any in-progress work in TASKS.md
- Commit work-in-progress with clear commit messages
- Leave session notes for future reference

## Example Usage

```bash
# Start of development day
/start-work

# Example output:
# ✅ Environment Ready: bb 1.3.x, clj-kondo 2023.x.x, git clean
# 📋 Current Focus: Phase 1.2 - Signature Implementation (3 tasks remaining)
# 🎯 Session Plan: Complete get-ticket signature update (2-3 hours)
# 🔄 Next: Use /next-tasks to analyze implementation approach
```

This command sets the foundation for productive, focused development sessions by ensuring all context is clear before diving into implementation work.
