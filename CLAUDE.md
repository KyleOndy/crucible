# Crucible Project Guidelines

## Development Workflow

### Cross-Platform Development

This project is primarily **developed on Linux** and **used on macOS**. The workflow is:

1. **Primary Development**: Linux machine (this environment)
2. **Testing & Usage**: MacBook laptop (production environment)
3. **Version Control**: Push feature branches for Mac testing

### Development Cycle

```
Linux Dev → Local Test → Push Branch → Mac Pull → Mac Test → Use on Mac
                            ↓
                   (if issues found)
                            ↓
                    Fix on Linux → Repeat
```

### Testing Protocol

- Develop and validate features locally on Linux
- Push to feature branch when ready for Mac testing
- Pull branch on Mac and verify functionality
- Primary usage happens on the Mac
- Occasionally send patches back from Mac if urgent fixes needed

## Platform Considerations

### Environment Setup

- **Shell**: Both systems use `zsh` configured via Nix
- **User Config**: Near-identical on both systems via Nix
- **Dependencies**: Managed through Nix for reproducibility

### File Paths and Storage

- **Daily Logs**: Store in `./logs/` directory (gitignored)
  - Keeps logs with the project
  - Portable across systems
  - Not committed to version control

- **Path Handling**: Use relative paths and home directory expansion
  ```clojure
  ;; Good - works on both platforms
  (str (System/getProperty "user.dir") "/logs/daily/")
  
  ;; Avoid - platform specific
  "/Users/kyle/..." ; macOS specific
  "/home/kyle/..."  ; Linux specific
  ```

### Shell Command Compatibility

Since both systems use the same zsh/Nix setup, most commands work identically. However:

- Prefer POSIX-compliant commands when possible
- Test any system-specific integrations (e.g., `pbcopy` on Mac vs `xclip` on Linux)
- Document platform differences when unavoidable

## Current Development Tasks

### c pipe Command Enhancement

**Current Limitation**: When using `command | c pipe`, the original command isn't captured in the log.

**Investigation Notes**:
- The pipe receiving process (`c pipe`) doesn't have access to the sending command
- Shell functions like `cpipe` solve this by wrapping both execution and logging
- Direct pipe usage requires manual command specification: `command | c pipe "command"`

**Potential Solutions**:
1. Enhanced shell integration (aliases/functions)
2. Process tree inspection (platform-specific, complex)
3. Wrapper commands that capture and execute
4. Keep current design, document the `cpipe` wrapper approach

## Implementation Guidelines

### Code Organization

- **Core Logic**: `core/` directory for all Clojure source
- **Libraries**: `core/lib/` for reusable components
- **Scripts**: `core/bin/` for executable entry points
- **Docs**: `docs/` for user-facing documentation

### Testing Approach

1. **Local Validation** (Linux):
   - Run basic functionality tests
   - Verify file I/O operations
   - Check Jira integration with test credentials

2. **Production Testing** (Mac):
   - Verify all user-facing commands work
   - Test daily log creation and updates
   - Validate Jira integration with real tickets
   - Ensure shell integrations function properly

### Git Workflow

- Feature branches for new development
- Test thoroughly on Linux before pushing
- No specific branch naming convention required
- Main branch should always work on Mac

## Platform-Specific Notes

### Linux Development Environment

- Full development setup with all tools
- Used for writing and initial testing
- May have additional dev dependencies not needed on Mac

### macOS Production Environment

- Minimal setup - just needs Babashka and dependencies
- Where the tool is actually used daily
- Focus on reliability and performance

### Known Differences

Currently no known platform-specific issues, but watch for:

- File system case sensitivity (Linux case-sensitive, macOS typically case-insensitive)
- Default tool availability (some GNU tools on Linux vs BSD tools on Mac)
- System paths and environment variables

## Quick Reference

### Common Commands

```bash
# Development (Linux)
bb crucible log daily     # Open daily log
bb pipe                   # Pipe stdin to log
bb crucible work-on TICK-123  # Start work on ticket

# Testing cycle
git add -A
git commit -m "Feature: description"
git push origin feature-branch

# On Mac
git pull origin feature-branch
bb crucible log daily  # Test it works
```

### File Locations

- Daily logs: `./logs/daily/YYYY-MM-DD.md`
- Config: `.crucible.edn` (when implemented)
- Temp files: System temp directory
- All paths relative to project root when possible

## Task Management with core/TASKS.md

### Core Principles

- **Always check core/TASKS.md first** before starting any development work
- **Update TASKS.md immediately** when tasks are completed or new ones
  identified
- **Use the TodoWrite tool for session tracking** but maintain TASKS.md for
  long-term planning
- **Keep tasks specific and actionable** with clear acceptance criteria

### Workflow Integration

1. **Before starting work**: Read core/TASKS.md to understand current priorities
2. **During development**: Use TodoWrite for session-level task tracking
3. **After completing work**: Update core/TASKS.md with completed tasks and any new
   discoveries
4. **Weekly review**: Move completed tasks to dated sections, reprioritize
   remaining work

### Task Formatting Standards

- Use checkbox format: `- [ ]` for incomplete, `- [x]` for complete
- Include complexity estimates: S (Small), M (Medium), L (Large)
- Add context and acceptance criteria for non-trivial tasks
- Group related tasks under clear headings
- Archive completed tasks with dates for historical reference

### Priority Guidelines

- **High**: Critical bugs, security issues, blocking dependencies
- **Medium**: Important features, performance improvements, refactoring
- **Low**: Nice-to-have features, documentation, cleanup tasks

### When to Update core/TASKS.md

- ✅ When starting a new development session
- ✅ When completing any task
- ✅ When discovering new work during implementation
- ✅ When priorities change based on user feedback
- ✅ When architecture decisions impact future work

## Notes for Future Development

- Keep platform differences minimal
- Test file operations on both platforms when in doubt
- Use Babashka's built-in cross-platform abstractions
- Document any platform-specific code clearly
- Prefer simple solutions that work everywhere
