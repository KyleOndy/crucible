---
name: next-tasks
description: Analyze upcoming tasks and provide implementation plan or suggest updates
---

# Next Tasks Command

Intelligently analyze the next set of uncompleted tasks in TASKS.md, examine the current codebase, and either suggest task updates or provide a detailed implementation plan.

## Workflow

### 1. Identify Next Tasks

Find the next uncompleted section in TASKS.md:

```python
# Read TASKS.md and find:
1. First section with uncompleted tasks (- [ ])
2. Consider task dependencies
3. Identify if it's a new phase or continuation
```

### 2. Analyze Current Codebase State

Examine what exists that the next tasks will build upon:

```bash
# Check project structure
ls -la backend/
ls -la tests/

# Review existing modules
find backend -name "*.py" -type f | xargs -I {} sh -c 'echo "=== {} ===" && head -20 {}'

# Check test coverage
pytest --cov=backend --cov-report=term-missing

# Review dependencies
cat requirements.txt
grep -E "packages|buildInputs" flake.nix
```

### 3. Dependency Analysis

For each upcoming task, identify:

1. **Prerequisites**: What must exist before this task can start
2. **Dependencies**: What other modules/files this will interact with
3. **Impact**: What existing code might need modification
4. **Test Requirements**: What types of tests are needed

### 4. Task Validation

Check if task descriptions need updating:

```python
# For each task, verify:
1. Is the task still relevant given current implementation?
2. Does the task description match current architecture?
3. Are there missing subtasks that should be added?
4. Are there redundant tasks that can be removed?
```

### 5. Generate Analysis Report

Create one of two outputs:

#### Option A: Task Updates Needed

If tasks need modification:

```markdown
## Task Analysis: Updates Required

### Current Tasks Under Review:
[Phase X.Y: Section Name]

### Suggested Updates:

#### 1. [Task Name]
**Current**: [Current task description]
**Issue**: [Why it needs updating]
**Suggested**: [New task description]
**Rationale**: [Why this change improves the task]

#### 2. Additional Tasks Needed:
- [ ] [New task 1]: [Description]
- [ ] [New task 2]: [Description]

#### 3. Tasks to Remove/Combine:
- [Task to remove]: [Reason]
- [Tasks to combine]: [How to combine them]

### Questions for Discussion:
1. [Architecture question]
2. [Technology choice question]
3. [Implementation approach question]

Let's discuss these updates before proceeding with implementation.
```

#### Option B: Implementation Plan

If tasks are ready to implement:

```markdown
## Implementation Plan: [Phase X.Y - Section Name]

### Tasks to Complete:
1. [Task 1]
2. [Task 2]
3. [Task 3]

### Implementation Order:

#### Step 1: Write Tests First (TDD)
**File**: tests/test_[module].py
**Tests to write**:
- test_[feature]_happy_path()
- test_[feature]_edge_case_1()
- test_[feature]_edge_case_2()
- test_[feature]_error_handling()

**Test Strategy**:
- Use pytest fixtures for setup
- Mock external dependencies
- Test both success and failure paths

#### Step 2: Create Module Structure
**File**: backend/[module].py
**Initial structure**:
```python
import logging
from typing import Optional, List

logger = logging.getLogger(__name__)

class [ClassName]:
    def __init__(self):
        pass
```

#### Step 3: Implement Core Functionality

**Functions to implement**:

1. `function_1()`: [Purpose]
2. `function_2()`: [Purpose]
3. `function_3()`: [Purpose]

**Key Decisions**:

- [Design decision 1]
- [Design decision 2]

#### Step 4: Integration

**Files to modify**:

- backend/**init**.py: [Add exports]
- backend/main.py: [Add endpoints if applicable]
- config.yaml: [Add configuration if needed]

#### Step 5: Verification

- Run pytest to ensure all tests pass
- Check test coverage
- Run linting (ruff)
- Update TASKS.md

### Technical Considerations

#### Dependencies

- Existing: [What we'll use from existing code]
- New: [What needs to be added to requirements.txt]

#### Design Patterns

- [Pattern 1]: [Where and why]
- [Pattern 2]: [Where and why]

#### Error Handling

- [Error type 1]: [How to handle]
- [Error type 2]: [How to handle]

#### Performance

- [Consideration 1]
- [Consideration 2]

### Estimated Complexity

- **Low**: Basic CRUD operations
- **Medium**: Complex logic or integrations
- **High**: Concurrent operations or external APIs

### Ready to Start?

All prerequisites are met. We can begin implementation following TDD:

1. Write failing tests
2. Implement minimal code to pass
3. Refactor and optimize

```

### 6. Priority Recommendation

Suggest task prioritization:

1. **Critical Path**: Tasks that block others
2. **Quick Wins**: Simple tasks that add value
3. **Complex Tasks**: Need more time and planning
4. **Optional**: Can be deferred if needed

### 7. Risk Assessment

Identify potential issues:

1. **Technical Risks**: Complex integrations, performance concerns
2. **Dependency Risks**: External API limitations, library compatibility
3. **Time Risks**: Tasks that might take longer than expected

## Task Categories Analysis

When analyzing tasks, consider these categories:

### Configuration Tasks
- Check for existing config structure
- Verify environment variable support
- Ensure validation is comprehensive

### Database Tasks
- Review schema design
- Check migration strategy
- Verify transaction handling

### API Tasks
- Confirm endpoint design
- Check authentication/authorization
- Verify error responses

### Frontend Tasks
- Review UI/UX requirements
- Check HTMX/Alpine.js patterns
- Verify responsive design

### Testing Tasks
- Ensure TDD is followed
- Check test coverage goals
- Verify e2e test scenarios

## Output Examples

### Example 1: Tasks Need Updates

```

The next section is Phase 1.3: Database Foundation

After analyzing the codebase, I found that we already have a config system
that includes database configuration. The tasks should be updated to:

1. Use SQLAlchemy with our existing config
2. Add support for PostgreSQL in addition to SQLite
3. Include soft delete functionality for safety

Shall we discuss these updates?

```

### Example 2: Ready to Implement

```

The next section is Phase 2.1: YouTube API Integration

All prerequisites are met:

- Configuration system is ready
- Test framework is set up
- No blocking dependencies

Here's my implementation plan:
[Detailed plan follows]

Ready to start with writing the tests first!

```

## Workflow Integration

### Command Flow Patterns:

**After `/start-work`:**
- Use `/next-tasks` to analyze upcoming work
- Understand what needs to be done in current phase/section

**Before Implementation:**
- Route to `/focus-task [task-name]` for specific task setup
- Route to `/commit-work` if already ready to commit existing work

**During Analysis:**
- Update TASKS.md if task descriptions need changes
- Use TodoWrite to track analysis findings

### Next Command Recommendations:

**If Analysis Shows Task Updates Needed:**
```markdown
Tasks require updates before implementation.

Next Steps:
1. Discuss and approve task changes
2. Update TASKS.md with new task descriptions  
3. Re-run /next-tasks to get implementation plan
4. Use /focus-task when ready to implement
```

**If Implementation Plan Generated:**

```markdown
Implementation plan ready.

Next Steps:
1. Use /focus-task "[specific-task-name]" to set up implementation context
2. Begin TDD cycle with test writing
3. Use /quick-commit for incremental progress
4. Use /commit-work when section/task complete

Alternative: Use /cycle for guided workflow assistance
```

## Usage

When executing this command, I will:

1. Scan TASKS.md for the next uncompleted section
2. Analyze the current codebase state
3. Check for dependencies and prerequisites
4. Either suggest task updates OR provide an implementation plan
5. Use TodoWrite to track the implementation if proceeding
6. Follow TDD methodology strictly

This ensures smooth progression through the project tasks while maintaining code quality and architectural consistency.

### Typical Command Sequences

```bash
# Planning and Implementation Flow:
/start-work → /next-tasks → /focus-task → [work] → /commit-work

# Quick Iteration Flow:
/next-tasks → /focus-task → [work] → /quick-commit → /quick-check

# Guided Flow:
/next-tasks → /cycle (for next step guidance)
```
