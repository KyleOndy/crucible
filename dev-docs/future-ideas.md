# Future Development Ideas

A list of ideas for future dev work

## Daily Changes Summary

Get a list of all Jira tickets and Confluence docs that have changed since the last start of day. This would help with staying aware of activity across the organization.

**Implementation thoughts:**
- Query Jira API for tickets updated since last daily log creation timestamp
- Include tickets where user is assignee, reporter, or watcher
- Query Confluence API for recently modified pages in watched spaces
- Add to daily log template as an "Activity Summary" section
- Could cache the last check timestamp in workspace config
- Make it opt-in via config flag since API calls might be slow

## Random Ticket Review

Pick `n` tickets randomly from "watched" tickets (tickets I have created/watching/commented on) to add to the daily log for review and grooming. This helps with ongoing maintenance of the backlog.

**Implementation thoughts:**
- Build on existing `get-my-recent-activity` function in `lib.jira.activity`
- Add JQL query for: `(reporter = currentUser() OR watcher = currentUser() OR comment ~ currentUser())`
- Implement reservoir sampling to get truly random selection
- Add as a section in daily log: "Tickets for Review"
- Could weight selection by age (older tickets more likely to be selected)
- Configuration: number of tickets to select, exclusion filters (e.g., skip closed tickets)

## Story Generation Iteration

Add the ability to iterate on the generated story from `c qs` instead of it being a one-shot generation.

**Implementation thoughts:**
- Save the last generated story to a temp file or workspace
- Add `c qs --iterate` or `c qs -i` flag to load and refine the last story
- Could support multiple rounds: generate → review → refine → review → create
- Add commands like `c qs --show-last` to see the previous generation
- Consider versioning iterations for comparison
- Could integrate with the ticket editor for manual refinements between AI passes

## Ticket Template Library

Build a library of reusable ticket templates for common types of work (bug reports, feature requests, investigations, incidents).

**Implementation thoughts:**
- Store templates in `workspace/templates/` or `core/templates/tickets/`
- Support variables/placeholders that get filled in during creation
- Command like `c new-ticket --template=incident`
- Templates could include standard fields, description structure, acceptance criteria patterns
- Could learn from existing tickets to suggest new templates

## Work Session Analytics

Track time spent on tickets and provide analytics about work patterns.

**Implementation thoughts:**
- Already tracking work sessions in daily logs
- Parse logs to extract session durations
- Generate weekly/monthly summaries
- Show patterns: most productive times, average session length, ticket velocity
- Could integrate with Jira work logs for time tracking

## Smart Command Suggestions

When piping output to the log, detect patterns and suggest relevant follow-up commands.

**Implementation thoughts:**
- Detect error messages and suggest debugging commands
- Recognize ticket IDs and offer to open them or start work
- Pattern match on common outputs (test failures, build errors)
- Non-intrusive: just print suggestions, don't auto-execute
- Could learn from user's command history

## Confluence Page Creation

Similar to quick story (`c qs`), add quick documentation creation that generates and publishes Confluence pages.

**Implementation thoughts:**
- `c docs` command for creating documentation
- AI-assisted content generation based on prompts
- Support different doc types: runbooks, post-mortems, design docs
- Auto-link related Jira tickets
- Use Confluence templates API for consistent formatting

## Multi-Ticket Operations

Perform bulk operations on multiple tickets at once.

**Implementation thoughts:**
- Select tickets by JQL or from a list
- Operations: bulk assign, bulk label, bulk transition, bulk comment
- Preview changes before applying
- Useful for sprint planning and cleanup tasks
- Could integrate with the random review feature for bulk grooming

## Integration with Git

Link git commits and branches to Jira tickets automatically.

**Implementation thoughts:**
- Parse ticket ID from branch names
- Auto-add ticket ID to commit messages if not present
- Command to create branch from current ticket: `c branch`
- Show git status in daily log for tickets being worked on
- Could add commit messages to ticket comments

## Local Ticket Cache

Cache frequently accessed ticket data locally for offline access and faster operations.

**Implementation thoughts:**
- SQLite database in workspace for ticket metadata
- Sync on demand or periodically
- Enable offline ticket viewing and searching
- Faster autocomplete for ticket IDs
- Could store custom metadata not in Jira

## AI Code Review Integration

When working on a ticket, get AI assistance for code review before pushing.

**Implementation thoughts:**
- `c review` command that uses git diff
- Context-aware based on ticket type (bug fix vs feature)
- Checks for common issues based on ticket description
- Could integrate with the story generation AI for consistency
- Generate review comments in Jira ticket
