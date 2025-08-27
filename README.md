# Crucible

The AI powered evolution of `foundry` - an SRE productivity system built with Babashka.

## Purpose

Crucible is an AI-powered SRE productivity system that streamlines daily operations through intelligent logging, ticket management, and documentation synchronization.

## Project Organization

Crucible uses a unified development approach with a single `bb.edn` file that serves two purposes:

### 1. Development Tasks

- `bb nrepl` - Start nREPL server for MCP integration
- `bb dev` - Start development environment  
- `bb clean` - Clean REPL artifacts

### 2. User-Facing Commands

- `bb crucible <command>` - Main CLI dispatcher
- `bb l` - Convenience alias for daily log
- `bb pipe` - Convenience alias for piping stdin to logs
- `bb work-on <ticket>` - Convenience alias for ticket workflow

## Architecture

```
crucible/
├── bb.edn              # Unified task configuration
├── deps.edn            # Clojure dependencies
├── core/
│   ├── bin/
│   │   └── crucible.clj    # Main CLI dispatcher
│   ├── lib/            # Core library modules
│   ├── templates/      # File templates
│   └── TASKS.md        # Implementation roadmap
└── workspace/          # User data (gitignored)
    ├── logs/
    ├── tickets/
    └── docs/
```

## Distribution Strategy

Crucible follows a progressive distribution approach:

1. **Development Phase**: `bb crucible <command>`
2. **Convenience Phase**: Shell aliases (`alias c='bb crucible'`)
3. **Binary Phase**: Compile to native binary with GraalVM
4. **Distribution Phase**: Install as standalone `c` or `crucible` binary

This approach allows seamless development while providing an optimal end-user experience.

## Getting Started

### Prerequisites

- [Babashka](https://github.com/babashka/babashka) installed
- Java 11+ (for development dependencies)

### Quick Setup

```bash
# 1. Check your setup status (before alias exists)
bb crucible setup-check

# 2. Set up the 'c' alias for convenience
alias c='bb crucible'
# Add to your shell config file for persistence

# 3. Verify setup (now using the alias!)
c setup-check

# 4. Start using Crucible
c help
```

For detailed setup instructions including shell-specific configurations, see [docs/setup-guide.md](docs/setup-guide.md).

### Development Setup

```bash
# Start development environment with nREPL
bb dev

# View available commands
c help  # or 'bb crucible help' before alias setup

# Test basic functionality
c log daily
c work-on TEST-123
```

### Command Output Logging

Crucible includes a powerful pipe functionality for capturing command outputs in your daily logs.
It works like an enhanced `tee` - logging to your daily log while passing output through to the next command:

```bash
# Basic piping (no command logged)
kubectl get pods | bb pipe

# With command logging
kubectl get pods | bb pipe "kubectl get pods"

# Tee-like behavior - logs AND continues pipeline
kubectl get pods | bb pipe "kubectl get pods" | grep Running | wc -l

# Automatic command logging with cpipe wrapper
cpipe() { eval "$*" | bb pipe "$*"; }
cpipe "kubectl get pods | grep Running"
```

The `cpipe` function automatically executes commands and logs both the command and output to your daily log.
See `docs/cpipe-setup.md` for detailed setup instructions for bash, zsh, and fish shells.

## Current Status

✅ **Phase 0: Project Setup** (Completed)

- [x] Development environment with nREPL integration
- [x] Unified bb.edn task structure
- [x] Core directory structure
- [x] CLI dispatcher with help system

🚧 **Phase 1: Core Infrastructure** (In Progress)

- [x] Enhanced logging system with pipe functionality
- [x] Daily log management with template system  
- [ ] Configuration management
- [ ] Basic command implementations

📋 **Upcoming**: Ticket management, documentation sync

See `core/TASKS.md` for detailed implementation roadmap.

## Key Design Decisions

### Single bb.edn Approach

After evaluating multiple approaches, we chose a unified `bb.edn` structure because:

- Maintains all project concerns in one place
- Clear separation between development and user tasks
- Natural progression from script to binary distribution
- Follows babashka ecosystem patterns
- Simplifies MCP integration for development

### Progressive Distribution

Rather than immediately creating a binary, Crucible starts as babashka scripts and graduates to native binaries. This approach:

- Enables rapid development iteration
- Provides flexibility during feature development
- Allows testing of the full user workflow early
- Supports seamless transition to production distribution
