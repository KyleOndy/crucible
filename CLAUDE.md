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

## Backward Compatibility Policy

**This is a single-user project** - backward compatibility is not a concern:

- **Breaking changes are acceptable**: Feel free to redesign commands and workflows
- **No deprecation needed**: Old functionality can be removed immediately
- **Experiment freely**: Try radical new approaches without migration paths
- **Update workflows as needed**: The user will adapt to changes
- **Focus on improvement**: Prioritize better solutions over compatibility

When implementing new features or refactoring existing ones, choose the best design without constraining yourself to existing interfaces or behaviors.

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

## Implementation Guidelines

### Code Organization

- **Core Logic**: `core/` directory for all Clojure source
- **Libraries**: `core/lib/` for reusable components
- **Scripts**: `core/bin/` for executable entry points
- **Docs**: `docs/` for user-facing documentation

#### Clojure File Size Guidelines

- **50-300 lines**: Ideal range for most files
- **300-500 lines**: Acceptable for complex modules
- **500+ lines**: Consider refactoring into smaller, focused modules
- Single responsibility per namespace
- Keep related functions together
- Aim for high cohesion within files
- Minimize dependencies between files

#### Claude Code vs Application Boundary

**Important Distinction**: Understand the clear boundary between development tooling and application code:

- **`.claude/` directory**: Contains Claude Code configurations and local development guidance for working _on_ the Crucible app
  - Claude Code command specifications (like `/cycle`, `/help`)
  - Local development workflows and tooling
  - IDE/editor configurations specific to this project

- **Everything else**: The actual Crucible application code and features
  - `core/` - Application logic and libraries  
  - `bb crucible` commands - Actual application commands
  - User-facing functionality

When Claude Code documentation or command specs are shared, these are NOT requests to implement features in Crucible - they're about the development environment and workflow tooling.

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

## Functional Programming & Clojure Guidelines

### Rich Hickey's Core Philosophy

The foundation of effective Clojure programming rests on Rich Hickey's design principles that prioritize simplicity and data-centric thinking.

#### Simplicity Over Ease

Following Rich Hickey's "Simple Made Easy" philosophy:

- **Simple**: Choose solutions with fewer interconnected parts
- **Easy**: Avoid the trap of familiar but complex approaches  
- **Principle**: "Simplicity is the ultimate sophistication" - prefer straightforward solutions that are easy to reason about

#### Data-Centric Programming

"Data is data" - Rich Hickey's fundamental insight:

- Use **few general data structures** (maps, vectors, sets) to hold data
- Apply **many general functions** to manipulate these structures
- Avoid creating complex object hierarchies - model your domain with simple data

```clojure
;; Good - data-centric approach
{:ticket/id "PROJ-123"
 :ticket/title "Fix authentication bug"
 :ticket/status :open
 :ticket/assignee "kyle"}

;; Avoid - complex object hierarchies  
;; (defrecord Ticket [id title status assignee])
```

#### Immutability by Default

Clojure's persistent data structures provide:

- **Safety**: No unexpected mutations from other code
- **Predictability**: Functions can't change your data
- **Concurrency**: Immutable data is thread-safe by default

### Functional Programming Mindset Shifts

#### Transformations Over Instructions

Think in data pipelines rather than sequential commands:

```clojure
;; Functional pipeline approach
(->> tickets
     (filter #(= (:status %) :open))
     (map :assignee)
     (frequencies)
     (sort-by val))

;; Avoid imperative loops
;; (for [ticket tickets] (when (= (:status ticket) :open) ...))
```

#### Functions as Values

Leverage first-class functions for composition and flexibility:

```clojure
;; Functions as arguments
(defn process-tickets [tickets transform-fn filter-fn]
  (->> tickets
       (filter filter-fn)
       (map transform-fn)))

;; Function composition
(def open-ticket-titles 
  (comp
    (partial map :title)
    (partial filter #(= (:status %) :open))))
```

#### Recursive Thinking with Efficient Implementation

**Mental Model**: Think recursively about problems
**Implementation**: Use core library functions that handle recursion efficiently

```clojure
;; Good - recursive thinking, efficient implementation
(defn sum-ticket-estimates [tickets]
  (reduce + (map :estimate tickets)))

;; Avoid - manual recursion unless necessary
;; (defn sum-estimates [tickets]
;;   (if (empty? tickets)
;;     0
;;     (+ (:estimate (first tickets))
;;        (sum-estimates (rest tickets)))))
```

### Practical Implementation Patterns

#### Core Library Mastery

Master these essential transformation functions:

- **`map`**: Transform each element in a collection
- **`filter`**: Select elements matching a predicate  
- **`reduce`**: Combine elements into a single result
- **`comp`**: Compose functions for reusable transformations
- **`partial`**: Create specialized versions of general functions

#### Threading Macros for Readability

Use threading macros to create readable data transformation pipelines:

```clojure
;; Thread-first (->) for single item transformations
(-> user-input
    str/trim
    str/lower-case
    keyword)

;; Thread-last (->>) for collection transformations  
(->> tickets
     (filter open?)
     (group-by :assignee)
     (map (fn [[assignee tickets]] 
            [assignee (count tickets)])))
```

#### Pure Function Design

Design functions with predictable inputs and outputs:

```clojure
;; Pure function - same input always produces same output
(defn calculate-story-points [ticket]
  (* (:complexity ticket) (:priority ticket)))

;; Avoid side effects in pure functions
(defn format-ticket-summary [ticket]
  ;; Don't: (println "Formatting ticket")  ; side effect
  (str (:id ticket) " - " (:title ticket)))
```

#### Local Bindings with `let`

Use `let` for readability and performance:

```clojure
(defn process-ticket [ticket]
  (let [base-points (:story-points ticket)
        complexity-multiplier (get-complexity-multiplier ticket)
        adjusted-points (* base-points complexity-multiplier)]
    {:ticket-id (:id ticket)
     :original-points base-points  
     :adjusted-points adjusted-points}))
```

### State Management Guidelines

Based on official Clojure documentation, choose the right concurrency primitive:

#### Atoms - Uncoordinated Synchronous State

**Use for**: Single pieces of independent state that need thread-safe updates

```clojure
;; Good use case - independent counter
(def ticket-counter (atom 0))

(defn increment-ticket-count []
  (swap! ticket-counter inc))

;; Config that changes independently
(def app-config (atom {:debug false :timeout 5000}))
```

#### Refs - Coordinated Synchronous State (STM)

**Use for**: Multiple pieces of state that must change together atomically

```clojure
;; Good use case - transferring story points between sprints
(def current-sprint (ref []))
(def next-sprint (ref []))

(defn move-ticket-to-next-sprint [ticket]
  (dosync
    (alter current-sprint #(remove (partial = ticket) %))
    (alter next-sprint #(conj % ticket))))
```

#### Agents - Uncoordinated Asynchronous State

**Use for**: Long-running operations or I/O where you don't need immediate results

```clojure
;; Good use case - background log processing
(def log-processor (agent []))

(defn add-log-entry [entry]
  (send log-processor #(conj % entry)))
```

### Error Handling (Pragmatic Approach)

Following Clojure's philosophy of simplicity over theoretical purity:

#### Leverage Clojure's Nil-Punning

Many Clojure functions handle `nil` gracefully:

```clojure
;; Clojure's nil-safe operations
(-> ticket
    :assignee     ; returns nil if no assignee
    str/upper-case ; safely handles nil
    (or "UNASSIGNED"))

;; Safe navigation with get-in
(get-in ticket [:metadata :created-by :name] "Unknown")
```

#### Practical Exception Handling

Use try/catch for recoverable errors, design functions to avoid exceptions:

```clojure
;; Design functions to return data about errors
(defn parse-ticket-id [id-string]
  (if-let [match (re-matches #"([A-Z]+)-(\d+)" id-string)]
    {:project (second match) :number (Integer/parseInt (nth match 2))}
    {:error :invalid-format :input id-string}))

;; Use try/catch for external system interactions
(defn fetch-ticket [id]
  (try
    {:result (jira-api/get-ticket id)}
    (catch Exception e
      {:error :api-failure :message (.getMessage e)})))
```

#### Early Validation

Validate inputs early and propagate errors clearly:

```clojure
(defn create-work-session [ticket-id]
  (cond
    (str/blank? ticket-id)
    {:error "Ticket ID cannot be blank"}
    
    (not (valid-ticket-format? ticket-id))
    {:error "Invalid ticket ID format"}
    
    :else
    (try
      {:result (initialize-work-session ticket-id)}
      (catch Exception e
        {:error (str "Failed to create session: " (.getMessage e))}))))
```

### Testing Best Practices

#### Property-Based Testing with test.check

Use `clojure.test.check` for comprehensive validation:

```clojure
(require '[clojure.test.check.properties :as prop])
(require '[clojure.test.check.generators :as gen])

;; Test properties that should hold for all valid inputs
(def ticket-processing-idempotent
  (prop/for-all [tickets (gen/vector ticket-gen)]
    (= (process-tickets tickets)
       (process-tickets (process-tickets tickets)))))
```

#### Pure Function Testing Advantages

Pure functions are easier and more reliable to test:

```clojure
;; Easy to test - no setup or mocking needed
(deftest test-calculate-story-points
  (is (= 6 (calculate-story-points {:complexity 2 :priority 3})))
  (is (= 0 (calculate-story-points {:complexity 0 :priority 5}))))
```

#### Integration Testing for Side Effects

Isolate side effects for focused testing:

```clojure
;; Separate pure logic from I/O
(defn ticket-summary-data [ticket]  ; pure function
  {:id (:id ticket)
   :status (:status ticket)
   :points (:story-points ticket)})

(defn save-ticket-summary [ticket]  ; I/O function
  (let [summary-data (ticket-summary-data ticket)]
    (write-to-file summary-data)))
```

### REPL-Driven Development

#### Interactive Workflow Integration

Integrate REPL exploration with the Linux → Mac development cycle:

1. **Explore in REPL**: Test functions and data transformations interactively
2. **Extract to Code**: Move successful explorations to source files
3. **Test on Linux**: Validate with full context and dependencies
4. **Deploy to Mac**: Push stable, REPL-tested code

#### Debugging with REPL

Use REPL for step-by-step exploration:

```clojure
;; Debug complex transformations step by step
(def sample-tickets [...])

;; Test each step in the pipeline
(->> sample-tickets
     (filter open?)           ; check this step
     (take 3))                ; verify intermediate results

(->> sample-tickets
     (filter open?)
     (group-by :assignee)     ; check this step  
     (take 2))                ; examine grouping
```

#### Prototype Validation

Quickly test ideas before committing to implementation:

```clojure
;; REPL exploration of API response processing
(def sample-api-response {...})

;; Try different parsing approaches
(-> sample-api-response :fields :summary :content)
(get-in sample-api-response [:fields :summary :content])
(some-> sample-api-response :fields :summary :content str/trim)
```

### Performance Considerations

#### Measure Before Optimizing

Follow Rich Hickey's emphasis on simplicity - optimize only when necessary:

```clojure
;; Start with readable, simple code
(defn process-all-tickets [tickets]
  (->> tickets
       (map enrich-ticket)
       (filter ready-for-sprint)
       (sort-by :priority)))

;; Optimize only if measurements show performance issues
;; Consider transients for bulk operations:
;; (persistent! (reduce conj! (transient []) large-collection))
```

#### Lazy Sequences

Understand when sequences are realized:

```clojure
;; Lazy - doesn't process until consumed
(def processed-tickets
  (->> all-tickets
       (map expensive-enrichment)
       (filter complex-predicate)))

;; Realizes the sequence
(take 10 processed-tickets)
```

#### Avoid Premature Optimization

Focus on correct, readable code first:

- Use core library functions (they're optimized)
- Profile before optimizing
- Consider algorithmic improvements before micro-optimizations
- Remember: "Make it work, make it right, make it fast"

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

## Troubleshooting

For comprehensive troubleshooting guidance, debug flags, and diagnostic techniques, see [dev-docs/troubleshooting.md](dev-docs/troubleshooting.md).

The troubleshooting guide covers:

- **Debug flags** for AI, Jira, and Sprint services
- **Common error scenarios** with specific diagnostic commands
- **Progressive troubleshooting** approaches
- **Configuration verification** steps

## Developer Documentation

The `dev-docs/` directory contains documentation for Claude Code and other AI assistants working on Crucible:

- **`dev-docs/troubleshooting.md`** - Comprehensive debugging guide with specific diagnostic commands and debug flags for troubleshooting authentication, API integration, and service connectivity issues
- **`dev-docs/error-handling.md`** - Error handling patterns, conventions, and best practices for maintaining consistent error handling across the Crucible codebase

## Notes for Future Development

- Keep platform differences minimal
- Test file operations on both platforms when in doubt
- Use Babashka's built-in cross-platform abstractions
- Document any platform-specific code clearly
- Prefer simple solutions that work everywhere
