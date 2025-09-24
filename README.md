# Crucible

My personal daily helper for SRE work - streamlining the routine tasks that keep systems running.

## What is Crucible?

Crucible is my daily companion for SRE work, handling the repetitive administrative tasks so I can focus on solving problems. It helps capture context, manage tickets, and maintain the operational discipline that keeps systems reliable.

Built to support my daily SRE workflow across different environments.

**What it handles:**

- **Daily context**: Capture decisions, commands, and observations from daily work
- **Ticket management**: Streamlined creation and tracking of operational work  
- **Work logging**: Maintain records of what was done and why

## Personal Tool Philosophy

**This is built specifically for my workflow** - not as a general-purpose tool. Key design decisions:

- **Single user focus** - Optimized for one person's productivity patterns
- **Breaking changes welcome** - I adapt my workflow rather than maintain backwards compatibility  
- **Opinionated choices** - Default configurations match my preferences and environment
- **Personal workflow** - Built around my specific SRE work patterns

If you're looking for a general-purpose productivity tool, this probably isn't it. But if you want to see how a personal productivity system can be built, feel free to explore!

## Key Features

### Context Capture

```bash
# Capture command outputs and decisions  
kubectl get pods | grep Running | c pipe
c l  # Open today's log for notes and observations
```

### Ticket Management

```bash
# Quick ticket creation for operational work
c qs "Fix authentication timeout" 
c qs "Add monitoring for database connections"
```

### Daily Operations Support

- **Work logging**: Structured daily logs that capture context and decisions
- **Command integration**: Seamlessly capture important command outputs  
- **Ticket workflow**: Streamlined creation and tracking of operational work

## Quick Start

### 1. Prerequisites

- Babashka installed

### 2. Run the setup script

```bash
./setup.sh
```

### 3. Test Jira integration (optional)

```bash
# Verify Jira configuration
c jira doctor

# Create your first ticket  
c qs "Test Crucible integration"
```

### 4. Command capture examples

```bash
# Capture important command outputs by piping to c pipe
date | c pipe "Current timestamp"
ps aux | grep myprocess | c pipe "Process check"
docker ps | c pipe "Container status"
```

## Documentation

- **[Setup Guide](docs/setup-guide.md)** - Complete installation and configuration
- **[Jira Integration](docs/jira-guide.md)** - Jira configuration, authentication, story points, and troubleshooting  
- **[Advanced Jira](docs/custom-fields-guide.md)** - Custom fields, complex configurations, and power-user features

## Current Status

**Working Features:**

- Daily log management with editor integration
- Command output capture and logging  
- Jira ticket creation with auto-assignment
- Sprint integration and auto-add functionality
- Shell integration for command capture
- SRE workflow support

## Technical Architecture

### Development Approach

Crucible uses a unified `bb.edn` configuration that serves both development and user-facing commands:

```bash
# Development tasks (when working on Crucible)
bb nrepl         # Start nREPL server for MCP integration
bb dev:start     # Start development environment  
bb dev:stop      # Stop development environment
bb clean         # Clean REPL artifacts
bb test          # Run all unit tests
bb lint          # Run clj-kondo on all source files
bb format:check  # Check Clojure formatting

# User commands (after setup.sh)
c <command>       # Main CLI dispatcher
c l               # Open daily log
c pipe            # Capture stdin to log
c qs              # Create quick story
c work-on         # Start work on ticket
```

### Distribution Strategy

Crucible is distributed as a setup script that creates a global `c` command wrapper around the Babashka-based implementation.

### Project Structure

```
crucible/
├── bb.edn              # Unified task configuration
├── deps.edn            # Clojure dependencies  
├── core/
│   ├── bin/crucible.clj    # Main CLI dispatcher
│   ├── lib/            # Core library modules
│   └── templates/      # File templates
├── docs/               # User documentation
├── dev-docs/           # Developer documentation  
└── workspace/          # User data (gitignored)
    ├── logs/
    ├── tickets/
    └── docs/
```

## Development Context

### Development Environment

- Full development environment with nREPL integration
- Lightweight daily usage setup
- Testing cycle: develop → push branch → test → use daily

### Design Principles

- **Data-centric**: Leverages Clojure's persistent data structures and functional programming
- **Cross-platform**: Babashka provides excellent cross-platform compatibility
- **Shell integration**: Enhanced `tee`-like functionality for command logging
- **Progressive enhancement**: Features degrade gracefully when services unavailable

---

*Built with [Babashka](https://github.com/babashka/babashka) and designed for daily productivity workflows.*
