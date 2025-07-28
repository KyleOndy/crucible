# Crucible Implementation Tasks

## Overview

Implementation roadmap for Crucible - an AI-powered SRE productivity system built with Babashka.

## Phase 0: Development Environment Setup

### Task 0.1: nREPL Server Setup

**Priority:** High | **Estimated Time:** 30 minutes | **Dependencies:** None | **Status:** Completed

- [x] Create bb.edn with nREPL server task and dependencies
- [x] Create deps.edn for Clojure project configuration
- [x] Configure .gitignore for REPL artifacts
- [x] Test nREPL server startup and .nrepl-port creation

**Acceptance Criteria:**

- `bb nrepl` starts nREPL server and creates .nrepl-port file
- MCP can connect to nREPL server for code evaluation
- Proper cleanup on server shutdown

**Usage:**

```bash
# Start nREPL server for MCP integration
bb nrepl

# Clean up REPL artifacts
bb clean
```

## Phase 1: Project Setup & Core Infrastructure

### Task 1.1: Project Structure Setup

**Priority:** High | **Estimated Time:** 1-2 hours | **Dependencies:** None

- [ ] Create core directory structure:

  ```
  crucible/
  ├── core/
  │   ├── bin/crucible.clj
  │   ├── lib/
  │   └── templates/
  ├── workspace/
  ├── bb.edn
  └── .gitignore
  ```

- [ ] Set up .gitignore to exclude `workspace/` and sensitive files
- [ ] Create initial bb.edn with basic dependencies

**Acceptance Criteria:**

- Directory structure matches specification
- Workspace directory is properly ignored by git
- bb.edn includes required dependencies (clj-http, babashka.cli, babashka.process)

### Task 1.2: Basic CLI Framework

**Priority:** High | **Estimated Time:** 2-3 hours | **Dependencies:** 1.1

- [ ] Create main CLI dispatcher in `core/bin/crucible.clj`
- [ ] Set up command routing using babashka.cli
- [ ] Implement basic help system
- [ ] Create shell alias setup instructions

**Acceptance Criteria:**

- `c help` shows available commands
- Command routing works for subcommands
- Error handling for unknown commands
- Clean exit codes

### Task 1.3: Configuration Management

**Priority:** Medium | **Estimated Time:** 1-2 hours | **Dependencies:** 1.2

- [ ] Create `core/lib/config.clj` for configuration loading
- [ ] Support environment variable loading (for pass integration)
- [ ] Basic validation for required configuration
- [ ] Default configuration values

**Acceptance Criteria:**

- Can load environment variables for API credentials
- Graceful handling of missing configuration
- Clear error messages for configuration issues

## Phase 2: Enhanced Logging System

### Task 2.1: Daily Log Management

**Priority:** High | **Estimated Time:** 2-3 hours | **Dependencies:** 1.2

- [ ] Implement `c l` command to open daily log
- [ ] Create daily log file naming convention (YYYY-MM-DD.md)
- [ ] Set up workspace/logs/ directory structure
- [ ] Integrate with $EDITOR environment variable

**Acceptance Criteria:**

- `c l` opens today's log file in default editor
- Log files created in `workspace/logs/daily/` with correct naming
- Handles missing editor gracefully

### Task 2.2: Log Piping Functionality

**Priority:** High | **Estimated Time:** 3-4 hours | **Dependencies:** 2.1

- [ ] Implement `c pipe` command to read from stdin
- [ ] Add metadata capture (timestamp, working directory, command context)
- [ ] Format piped content as markdown code blocks
- [ ] Append to current daily log

**Acceptance Criteria:**

- `kubectl get pods | c pipe` works correctly
- Metadata is properly formatted and informative
- Content is appended to daily log with proper markdown formatting
- Handles empty stdin gracefully

### Task 2.3: Log Review Workflows

**Priority:** Medium | **Estimated Time:** 2-3 hours | **Dependencies:** 2.1

- [ ] Implement `c review daily` command
- [ ] Implement `c review weekly` command  
- [ ] Implement `c review monthly` command
- [ ] Create templates for weekly/monthly summaries

**Acceptance Criteria:**

- Review commands open appropriate log files
- Weekly/monthly logs are created in correct directories
- Templates provide structure for reviews

## Phase 3: Ticket Management System

### Task 3.1: Jira API Integration

**Priority:** High | **Estimated Time:** 3-4 hours | **Dependencies:** 1.3

- [ ] Create `core/lib/jira.clj` for API interactions
- [ ] Implement authentication using environment variables
- [ ] Create functions to fetch ticket data (title, description, status)
- [ ] Handle API errors gracefully

**Acceptance Criteria:**

- Can authenticate with Jira using credentials from environment
- Successfully fetch basic ticket information
- Proper error handling for network/auth failures
- Clean data structures for ticket information

### Task 3.2: Ticket Directory Management

**Priority:** High | **Estimated Time:** 2-3 hours | **Dependencies:** 3.1

- [ ] Implement ticket directory creation (`workspace/tickets/PROJ/PROJ-1234/`)
- [ ] Create ticket README.md template
- [ ] Populate README.md with ticket data
- [ ] Set up ticket workspace structure (notes.md, scripts/, artifacts/)

**Acceptance Criteria:**

- Directories created with correct structure
- README.md contains ticket title, description, and metadata
- Template is well-formatted and useful

### Task 3.3: Tmux Integration

**Priority:** High | **Estimated Time:** 2-3 hours | **Dependencies:** 3.2

- [ ] Create `core/lib/tmux.clj` for session management
- [ ] Implement tmux session creation/switching
- [ ] Set working directory in tmux sessions
- [ ] Handle existing sessions gracefully

**Acceptance Criteria:**

- `c work-on PROJ-1234` creates/switches to tmux session
- Session starts in correct ticket directory
- Existing sessions are reused, not duplicated
- Clean session naming convention

### Task 3.4: Work-on Command Integration

**Priority:** High | **Estimated Time:** 2-3 hours | **Dependencies:** 3.1, 3.2, 3.3

- [ ] Combine all ticket management pieces into `c work-on` command
- [ ] Add error handling for invalid ticket IDs
- [ ] Add support for ticket ID validation
- [ ] Create helper commands (`c tickets`, `c resume`)

**Acceptance Criteria:**

- `c work-on PROJ-1234` performs full workflow
- Error messages are clear and actionable
- Helper commands work as expected

## Phase 4: Documentation Sync System

### Task 4.1: Confluence API Integration

**Priority:** Medium | **Estimated Time:** 3-4 hours | **Dependencies:** 1.3

- [ ] Create `core/lib/confluence.clj` for API interactions
- [ ] Implement authentication using environment variables
- [ ] Create functions to fetch page/space content
- [ ] Handle API pagination and rate limiting

**Acceptance Criteria:**

- Can authenticate and fetch Confluence content
- Proper error handling for API failures
- Efficient handling of large content sets

### Task 4.2: Content Processing & Storage

**Priority:** Medium | **Estimated Time:** 3-4 hours | **Dependencies:** 4.1

- [ ] Implement HTML to Markdown conversion
- [ ] Create local storage structure for docs
- [ ] Implement content indexing for search
- [ ] Handle content updates and versioning

**Acceptance Criteria:**

- HTML content converted to clean Markdown
- Docs stored in organized directory structure
- Basic search/indexing functionality

### Task 4.3: Sync Commands

**Priority:** Medium | **Estimated Time:** 2-3 hours | **Dependencies:** 4.2

- [ ] Implement `c sync-docs` command for full sync
- [ ] Add support for space-specific syncing
- [ ] Add support for individual page syncing
- [ ] Create sync status and progress reporting

**Acceptance Criteria:**

- Sync commands work reliably
- Progress feedback for long operations
- Incremental sync support

## Phase 5: Polish & Documentation

### Task 5.1: Error Handling & User Experience

**Priority:** Medium | **Estimated Time:** 2-3 hours | **Dependencies:** All previous

- [ ] Comprehensive error handling across all commands
- [ ] Improve help text and command descriptions
- [ ] Add input validation for all commands
- [ ] Create user-friendly error messages

**Acceptance Criteria:**

- All error conditions handled gracefully
- Help text is comprehensive and useful
- Commands provide clear feedback

### Task 5.2: Documentation & Setup

**Priority:** Medium | **Estimated Time:** 2-3 hours | **Dependencies:** All previous

- [ ] Update README.md with installation instructions
- [ ] Create BACKLOG.md for future features
- [ ] Document configuration requirements
- [ ] Create example workflows and usage patterns

**Acceptance Criteria:**

- Clear installation and setup instructions
- Comprehensive usage documentation
- Future features properly documented

### Task 5.3: Testing & Validation

**Priority:** Medium | **Estimated Time:** 2-3 hours | **Dependencies:** All previous

- [ ] Create test cases for core functionality
- [ ] Validate against different environments
- [ ] Performance testing for large datasets
- [ ] Create troubleshooting guide

**Acceptance Criteria:**

- Core functionality is tested and reliable
- Performance is acceptable for expected usage
- Troubleshooting documentation is complete

## Backlog Items (Future Phases)

### Enhancement Backlog

- [ ] Slack integration (pending company policy review)
- [ ] Automatic ticket archiving strategies
- [ ] Advanced tmux layout customization
- [ ] Full-text search beyond ripgrep
- [ ] Time tracking integration
- [ ] Git integration for ticket directories
- [ ] Notification system for ticket updates
- [ ] Dashboard/summary views
- [ ] Export functionality for reports
- [ ] Plugin system for extensibility

### Technical Debt & Improvements

- [ ] Performance optimization for large datasets
- [ ] Caching strategies for API calls
- [ ] Offline mode support
- [ ] Configuration file support (beyond env vars)
- [ ] Better tmux session lifecycle management
- [ ] Automated backup integration
- [ ] Security audit for credential handling

## Implementation Notes

### Development Approach

1. Start with Phase 1 tasks to establish foundation
2. Implement core logging before ticket management
3. Test each phase thoroughly before moving to next
4. Keep tasks small and focused for iterative development
5. Document decisions and trade-offs as you go

### Babashka-Specific Considerations

- Use `babashka.process` for all shell interactions
- Leverage `babashka.cli` for argument parsing
- Keep startup time minimal by avoiding heavy dependencies
- Use EDN for configuration when possible
- Consider compilation to native-image for distribution

### Testing Strategy

- Test API integrations with real services early
- Validate tmux integration across different environments
- Test file operations with various permissions
- Validate markdown processing with complex content
