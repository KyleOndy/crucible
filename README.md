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

### Development Setup
```bash
# Start development environment with nREPL
bb dev

# View available commands
bb crucible help

# Test basic functionality
bb crucible log daily
bb crucible work-on TEST-123
```

## Current Status

✅ **Phase 0: Project Setup** (Completed)
- [x] Development environment with nREPL integration
- [x] Unified bb.edn task structure
- [x] Core directory structure
- [x] CLI dispatcher with help system

🚧 **Phase 1: Core Infrastructure** (In Progress)
- [ ] Enhanced logging system
- [ ] Configuration management
- [ ] Basic command implementations

📋 **Upcoming**: Ticket management, documentation sync, tmux integration

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