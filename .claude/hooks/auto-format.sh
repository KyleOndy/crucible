#!/usr/bin/env bash
# Auto-format Clojure files before tool execution
# This hook runs bb format:fix to ensure files are properly formatted

set -euo pipefail

# Colors for output
BLUE='\033[0;34m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Log function
log() {
    echo -e "${BLUE}[auto-format]${NC} $1" >&2
}

success() {
    echo -e "${GREEN}[auto-format]${NC} $1" >&2
}

warn() {
    echo -e "${YELLOW}[auto-format WARN]${NC} $1" >&2
}

# Check if we're in a git repository with Clojure files
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    # Not in a git repo, skip
    exit 0
fi

# Check if bb.edn exists (indicates this is a Babashka project)
if [[ ! -f "bb.edn" ]]; then
    # Not a Babashka project, skip
    exit 0
fi

# Check if we have any Clojure files
CLOJURE_FILES=$(find . -name "*.clj" -o -name "*.cljs" -o -name "*.cljc" -o -name "*.edn" 2>/dev/null | head -1)
if [[ -z "$CLOJURE_FILES" ]]; then
    # No Clojure files, skip
    exit 0
fi

# Check if bb format:fix task exists
if ! bb tasks | grep -q "format:fix"; then
    warn "bb format:fix task not found, skipping auto-format"
    exit 0
fi

log "Auto-formatting Clojure files..."

# Run bb format:fix and capture output
if bb format:fix >/dev/null 2>&1; then
    success "Auto-formatting completed"
else
    warn "Auto-formatting failed, continuing anyway"
fi

exit 0