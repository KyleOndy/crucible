---
name: commit-work
description: Create a detailed commit with all work done in this conversation
---

# Commit Work Command

Create a comprehensive git commit that captures all work done in this conversation, including technical decisions, completed tasks, and test coverage.

## Workflow

### 1. Analyze Current Changes

First, check what has been modified:

```bash
# Check current git status
git status

# Review detailed changes
git diff

# Check staged changes if any
git diff --staged
```

### 2. Cross-Reference with TASKS.md

Read TASKS.md and identify which tasks have been completed based on the changes:

1. Compare file changes with task descriptions
2. Identify which phase and section was worked on
3. List specific completed subtasks

### 3. Verify Test Coverage

If tests were written, verify they pass:

```bash
# Run pytest to ensure all tests pass
pytest -v

# Check test coverage if significant code was added
pytest --cov=backend --cov-report=term-missing
```

### 4. Update TASKS.md

Mark completed tasks with `[x]`:

1. Find tasks that correspond to the implemented features
2. Change `- [ ]` to `- [x]` for completed items
3. Ensure parent tasks are marked complete only if all subtasks are done

### 5. Extract Key Decisions

Review the conversation to identify:

- Architecture decisions made
- Technology choices
- Implementation approaches chosen
- Trade-offs considered
- Security considerations addressed
- Performance optimizations applied

### 6. Generate Commit Message

Create a detailed commit message following this format:

```
Complete Phase X.Y: [Section Name]

[Brief summary of what was implemented]

Features:
- [Feature 1 with technical detail]
- [Feature 2 with technical detail]
- [Feature 3 with technical detail]

Technical Decisions:
- [Decision 1 and reasoning]
- [Decision 2 and reasoning]

Tests:
- [Number of tests added/passing]
- [Coverage if relevant]
- [Test categories: unit, integration, e2e]

[Any additional context about the implementation]

🤖 Generated with [Claude Code](https://claude.ai/code)

Co-Authored-By: Claude <noreply@anthropic.com>
```

### 7. Create the Commit

Stage all changes and commit:

```bash
# Add all changes including TASKS.md updates
git add -A

# Create the commit with the detailed message
git commit -m "$(cat <<'EOF'
[Generated commit message here]
EOF
)"

# Show the commit for verification
git show --stat HEAD
```

## Important Considerations

1. **TDD Compliance**: Verify that tests were written before implementation if following TDD
2. **Breaking Changes**: Note any breaking changes in the commit message
3. **Dependencies**: Mention new dependencies added to requirements.txt or flake.nix
4. **Configuration**: Note changes to config.yaml or example.config.yaml
5. **Documentation**: Include updates to README.md or CLAUDE.md if modified

## Example Usage

When executing this command, I will:

1. Analyze all file changes in the repository
2. Match changes to tasks in TASKS.md
3. Update task checkboxes appropriately
4. Generate a comprehensive commit message
5. Create the commit with proper attribution
6. Display the commit for your review

The commit will capture not just what changed, but why it changed and how it fits into the overall project plan.

## Workflow Integration

### When to Use This Command

**After Major Implementation Work:**

- Completed entire tasks or sections from TASKS.md
- Multiple files changed with related functionality
- Significant feature additions or refactoring
- When using TDD with comprehensive test coverage

**Instead of `/quick-commit` when:**

- Changes span multiple files
- Breaking changes or signature modifications
- Need detailed technical decision documentation
- Complex implementation requiring explanation

### Command Flow Patterns

**Typical Sequence:**

```bash
/focus-task → [extensive work] → /commit-work → /check-last-commit
```

**After Planning:**

```bash
/next-tasks → /focus-task → [major implementation] → /commit-work
```

**Session Completion:**

```bash
[work session] → /commit-work → /start-work (next session)
```

### Next Command Recommendations

**After Successful Commit:**

- Use `/check-last-commit` for comprehensive verification
- Use `/start-work` to begin next development session
- Use `/cycle` for guided next steps

**If Commit Process Reveals Issues:**

- Fix issues and re-run `/commit-work`
- Use `/quick-check` for rapid validation of fixes
- Consider breaking large changes into smaller commits

### Integration with Development Cycle

**Linux Development:**

- Primary use for comprehensive feature commits
- Full analysis and documentation creation
- Preparation for Mac testing

**Pre-Mac Sync:**

- Ensure all major work is committed with this command
- Verify comprehensive commit messages for Mac context
- Include cross-platform considerations in technical decisions
