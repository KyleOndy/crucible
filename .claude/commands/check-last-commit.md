---
name: check-last-commit
description: Verify that the last commit's claimed completions were actually implemented correctly
---

# Check Last Commit Command

Verify that the work claimed in the last commit message matches the actual implementation and that all marked tasks in TASKS.md were properly completed.

## Workflow

### 1. Analyze Last Commit

Extract information from the most recent commit:

```bash
# Get commit hash and message
git log -1 --format="%H %s"

# Get full commit message
git log -1 --format="%B"

# Get list of changed files
git diff-tree --no-commit-id --name-only -r HEAD

# Get detailed changes
git show HEAD --stat
```

### 2. Parse Claimed Completions

From the commit message, identify:

1. **Phase and Section**: Which part of TASKS.md was worked on
2. **Claimed Features**: What was supposedly implemented
3. **Test Claims**: Number and types of tests mentioned
4. **Technical Decisions**: Architecture choices made

### 3. Verify TASKS.md Updates

Check that tasks are properly marked:

```python
# Read TASKS.md and verify:
1. Tasks mentioned in commit are marked with [x]
2. Parent tasks are only marked complete if ALL subtasks are done
3. No tasks are marked complete that weren't actually implemented
```

### 4. Verify File Existence and Content

For each claimed implementation:

1. **Check File Exists**: Verify mentioned files were created/modified
2. **Check Implementation**: Verify the code matches the claimed functionality
3. **Check Imports**: Ensure all imports are valid
4. **Check Dependencies**: Verify requirements.txt has needed packages

### 5. Verify Test Coverage

If tests were claimed:

```bash
# Run the tests
pytest -v

# Check specific test files mentioned
pytest tests/test_[module].py -v

# Verify test count matches claims
pytest --collect-only | grep "test session starts" -A 1

# Check test names match TDD pattern (test written before implementation)
git log --follow -p -- tests/test_*.py | grep "def test_"
```

### 6. Check TDD Compliance

Verify Test-Driven Development was followed:

1. **Test Files First**: Check if test files were created before implementation files
2. **Test Patterns**: Verify tests follow the pattern:
   - Happy path tests
   - Edge case tests  
   - Error handling tests
3. **Commit Order**: In git history, tests should appear before implementation

### 7. Verify Functionality

For each major feature:

1. **Config Management**: If config.py was added, verify:
   - YAML loading works
   - Environment override works
   - Validation is present

2. **Database Models**: If models were added, verify:
   - Schema matches specification
   - Relationships are correct
   - Migrations work

3. **API Endpoints**: If endpoints were added, verify:
   - Routes are registered
   - Input validation exists
   - Error handling is present

### 8. Generate Verification Report

Create a detailed report:

```markdown
## Commit Verification Report

### Commit: [hash] [message]

### ✅ Verified Successfully:
- [Item 1]: [Details]
- [Item 2]: [Details]

### ⚠️ Warnings:
- [Warning 1]: [Details]
- [Warning 2]: [Details]

### ❌ Issues Found:
- [Issue 1]: [Details and fix suggestion]
- [Issue 2]: [Details and fix suggestion]

### Test Results:
- Tests Claimed: X
- Tests Found: Y
- Tests Passing: Z
- Coverage: XX%

### TDD Compliance:
- Tests Written First: Yes/No
- Test Categories Present: [unit/integration/e2e]
- Edge Cases Covered: Yes/No

### Recommendations:
1. [Action item 1]
2. [Action item 2]
```

## Common Issues to Check

1. **Incomplete Implementations**:
   - Stubbed functions without real logic
   - TODO comments left in code
   - Missing error handling

2. **Test Issues**:
   - Tests that always pass (assert True)
   - Missing edge case tests
   - Tests not matching implementation

3. **Documentation Gaps**:
   - Undocumented configuration options
   - Missing docstrings
   - Outdated README

4. **Security Concerns**:
   - Hardcoded credentials
   - Missing input validation
   - Exposed internal paths

## Fix Suggestions

If issues are found, provide specific fixes:

1. **Missing Tests**: Generate the missing test cases
2. **Incomplete Tasks**: Show what code needs to be added
3. **Wrong Markings**: Show the correct TASKS.md updates
4. **Failed Tests**: Debug and suggest fixes

## Example Usage

When executing this command, I will:

1. Examine the last commit in detail
2. Cross-reference with TASKS.md
3. Run all relevant tests
4. Check code quality and completeness
5. Generate a comprehensive verification report
6. Suggest fixes for any issues found

This ensures that commits accurately reflect the work done and maintain the project's quality standards.

## Workflow Integration

### When to Use This Command

**After `/commit-work`:**

- Comprehensive verification of major commits
- Validate claimed implementations match reality
- Ensure TASKS.md updates are accurate

**When Issues Suspected:**

- Tests failing after commit
- Functionality not working as expected
- Commit message claims don't match implementation

**Before Platform Switch:**

- Verify Linux commits before Mac testing
- Ensure cross-platform compatibility
- Validate comprehensive feature completeness

### Command Flow Patterns

**Major Feature Verification:**

```bash
/commit-work → /check-last-commit → [fix issues] → /quick-commit → /quick-check
```

**Session Wrap-up:**

```bash
/commit-work → /check-last-commit → /start-work (next session)
```

**Issue Discovery:**

```bash
/check-last-commit → [reveals problems] → /focus-task → [fix] → /quick-commit
```

### Next Command Recommendations

**If Verification Passes:**

- Use `/start-work` for next development session
- Use `/next-tasks` to continue with upcoming work
- Use `/cycle` for guidance on next priorities

**If Issues Found:**

- Use `/focus-task` to address specific problems
- Use `/quick-commit` for fixes
- Re-run `/check-last-commit` after fixes
- Consider reverting commit if issues are severe

**If Partial Implementation:**

- Complete missing pieces with focused work
- Update TASKS.md to reflect actual completion status
- Use `/commit-work` again for comprehensive update

### Integration with Development Cycle

**Quality Assurance:**

- Maintains high standards for committed work
- Prevents accumulation of technical debt
- Ensures claims match implementation

**Cross-Platform Preparation:**

- Validates work before Mac testing
- Identifies platform-specific concerns
- Ensures robust functionality
