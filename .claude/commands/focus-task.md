---
name: focus-task
description: Bridge between planning and execution by setting up focused context for specific task implementation
---

# Focus Task Command

Transform abstract task planning into concrete implementation readiness by extracting specific tasks from TASKS.md, setting up TodoWrite tracking, and preparing the implementation context needed for focused development work.

## Usage

```bash
/focus-task [task-identifier]
```

**Examples:**

- `/focus-task "Update get-ticket signature"`
- `/focus-task "Split jira.clj into 5 namespaces"`
- `/focus-task phase-1.2` (focuses on entire section)

## Workflow

### 1. Task Identification & Extraction

Locate the specified task in TASKS.md:

```markdown
# Parse TASKS.md to find:
1. **Exact Task Match**: Find task by description or identifier
2. **Context Extraction**: Parent section, phase, dependencies
3. **Scope Assessment**: Single task vs. section vs. subtasks
4. **Completion Status**: Verify task is not already completed

# Handle different task types:
- Single task: "Update get-ticket signature"
- Task with subtasks: Parent task + all subtasks
- Section focus: All uncompleted tasks in section
- Phase focus: All uncompleted tasks in phase
```

### 2. Implementation Context Analysis

Analyze what the task involves:

```bash
# For code-related tasks, identify:
1. **Files to Modify**: Which files will be changed
2. **Dependencies**: What functions/modules are involved
3. **Call Sites**: Where changes will have impact
4. **Test Requirements**: What needs to be tested

# Example analysis for "Update get-ticket signature":
# - Primary file: core/jira.clj
# - Function: get-ticket
# - Call sites: commands.clj:line-123, daily-log.clj:line-456
# - Tests needed: New signature, error handling, backwards compatibility
```

### 3. TodoWrite Session Setup

Create focused session tracking:

```markdown
# Initialize TodoWrite with task-specific todos:
1. Clear any stale todos from previous sessions
2. Break down the main task into 3-6 actionable steps
3. Set up TDD cycle if applicable
4. Add verification steps

# Example TodoWrite setup for signature update:
- [ ] Write tests for new get-ticket signature
- [ ] Implement new signature with error handling
- [ ] Update call sites in commands.clj
- [ ] Update call sites in daily-log.clj  
- [ ] Run full test suite
- [ ] Verify with bb crucible work-on TEST-123
```

### 4. TDD Cycle Preparation

Set up Test-Driven Development workflow:

```markdown
# For tasks involving code changes:
1. **Test File Identification**: tests/test_[module].py or equivalent
2. **Test Strategy**: Happy path, edge cases, error scenarios
3. **Mock Requirements**: External dependencies to mock
4. **Test Data**: Sample inputs and expected outputs

# Example TDD setup:
# File: tests/test_jira.clj (or create if doesn't exist)
# Tests to write:
# - test-get-ticket-success-returns-result-map
# - test-get-ticket-not-found-returns-error-map
# - test-get-ticket-network-error-returns-error-map
# - test-get-ticket-invalid-id-returns-validation-error
```

### 5. Implementation Roadmap

Create step-by-step implementation plan:

```markdown
# Detailed implementation sequence:

## Step 1: Write Failing Tests
**File**: [test-file-path]
**Duration**: 15-30 minutes
**Goal**: Red phase of TDD cycle

## Step 2: Minimal Implementation
**File**: [source-file-path]
**Duration**: 30-60 minutes  
**Goal**: Green phase - make tests pass

## Step 3: Refactor & Optimize
**Duration**: 15-30 minutes
**Goal**: Clean up implementation without breaking tests

## Step 4: Update Call Sites
**Files**: [list-of-dependent-files]
**Duration**: 45-90 minutes
**Goal**: Update all usage to new signature

## Step 5: Integration Testing
**Duration**: 15-30 minutes
**Goal**: Verify end-to-end functionality works

## Step 6: Documentation
**Duration**: 10-15 minutes
**Goal**: Update docstrings, comments if needed
```

### 6. Context Preparation

Set up the development environment for focused work:

```bash
# Navigate to relevant files
ls -la core/[relevant-files]

# Check current state
git status
git diff [relevant-files]

# Verify dependencies are available
clj-kondo --lint core/[relevant-files]

# Check for any existing tests
ls -la tests/ | grep -i [module-name]

# Review current implementation
grep -n [function-name] core/[relevant-files]
```

### 7. Dependency & Impact Analysis

Understand the broader implications:

```bash
# Find all usages of functions being modified
grep -r [function-name] core/

# Check for configuration dependencies
grep -r [function-name] *.edn

# Identify integration points
grep -r [function-name] tests/

# Check for documentation references
grep -r [function-name] docs/ *.md
```

## Task Types & Specific Setups

### Function Signature Updates

```markdown
# Focus Setup:
- Identify function location and current signature
- Find all call sites across codebase
- Plan signature transition strategy
- Set up tests for old and new behavior
- Plan rollout sequence (tests → implementation → call sites)
```

### File Decomposition Tasks

```markdown
# Focus Setup:
- Analyze current file structure and responsibilities
- Design new namespace organization
- Plan import/export strategy
- Set up migration sequence
- Identify integration testing needs
```

### Bug Fix Tasks

```markdown
# Focus Setup:
- Reproduce the bug scenario
- Write test that demonstrates the bug
- Identify root cause location
- Plan minimal fix approach
- Set up regression testing
```

### Configuration/Integration Tasks

```markdown
# Focus Setup:
- Understand current integration points
- Plan configuration changes
- Set up test environment
- Identify validation requirements
- Plan rollback strategy
```

## Session Focus Report

Generate comprehensive focus report:

```markdown
## Task Focus: [Task Name]

### 📋 Task Details:
- **Source**: Phase [X.Y] - [Section Name]
- **Type**: [Signature Update/File Split/Bug Fix/Feature]
- **Complexity**: [S/M/L]
- **Estimated Duration**: [time estimate]

### 🎯 Implementation Plan:
1. [Step 1]: [Duration] - [Goal]
2. [Step 2]: [Duration] - [Goal]
3. [Step 3]: [Duration] - [Goal]
[...]

### 📁 Files Involved:
- **Primary**: [main file to modify]
- **Secondary**: [dependent files]
- **Tests**: [test files to create/modify]
- **Config**: [config files if applicable]

### 🧪 TDD Strategy:
- **Test File**: [path]
- **Test Categories**: [happy path/edge cases/errors]
- **Mock Requirements**: [external dependencies]

### ⚡ TodoWrite Session:
[X] TodoWrite initialized with [N] focused tasks
[X] TDD cycle prepared
[X] Implementation roadmap created

### 🔄 Ready to Start:
All context prepared. Begin with:
1. Open [primary-file] for editing
2. Start TDD cycle with test writing
3. Use TodoWrite to track progress

### ➡️ Next Commands:
- Begin implementation work
- Use `/quick-commit` for incremental progress
- Use `/commit-work` when task is complete
- Use `/quick-check` to verify changes
```

## Integration with TASKS.md

### Task Status Tracking

```markdown
# During focus setup:
1. Mark task as "in progress" if not already
2. Add estimated completion time
3. Note any dependencies discovered
4. Update parent task status if applicable

# Task format in TASKS.md:
- [x] Completed task
- [ ] Uncompleted task  
- [>] In progress task (optional notation)
- [?] Blocked task (optional notation)
```

### Scope Adjustments

```markdown
# If task scope changes during analysis:
1. Update task description in TASKS.md
2. Add/remove subtasks as needed
3. Adjust complexity estimates
4. Note scope changes in session tracking
```

## Common Focus Scenarios

### Scenario 1: Single Function Update

```bash
/focus-task "Update get-ticket signature"

# Result: 
# - TodoWrite with 5-6 focused steps
# - TDD setup for get-ticket function
# - Call site analysis complete
# - Ready to start with test writing
```

### Scenario 2: File Decomposition

```bash  
/focus-task "Split jira.clj into 5 namespaces"

# Result:
# - TodoWrite with namespace-by-namespace breakdown
# - Import/export dependency map
# - Migration sequence planned
# - Ready to start with first namespace
```

### Scenario 3: Section Completion

```bash
/focus-task phase-1.2

# Result:
# - TodoWrite with all uncompleted tasks in Phase 1.2
# - Dependencies between tasks identified
# - Optimal completion sequence planned
# - Ready to start with first task
```

## Error Handling

### Task Not Found

```markdown
❌ Task Focus Failed

Issue: Could not locate task "[task-identifier]" in TASKS.md

Suggestions:
1. Check task description spelling
2. Use /next-tasks to see available tasks
3. Verify task hasn't been completed already
4. Use section focus instead: /focus-task phase-1.2
```

### Ambiguous Task

```markdown
⚠️ Multiple Matches Found

Found [N] tasks matching "[identifier]":
1. [Phase X.Y]: [Full task description]
2. [Phase A.B]: [Full task description]

Please specify:
- /focus-task "[full-exact-description]"
- /focus-task phase-X.Y (for section focus)
```

### Missing Dependencies

```markdown
⚠️ Dependencies Required

Task "[task-name]" depends on:
- [ ] [Prerequisite task 1]
- [ ] [Prerequisite task 2]

Recommendations:
1. Complete prerequisites first
2. Use /focus-task on prerequisite tasks
3. Or proceed with caution and note dependencies
```

This command transforms high-level task planning into concrete, actionable development sessions with all necessary context prepared for focused implementation work.
