ADF Feature Test Document

This comprehensive document tests all markdown-to-ADF conversion features supported by the Crucible library. Use this with `c qs -f test-adf-features.md` to create a Jira ticket and verify proper rendering.

## Text Formatting

Basic inline formatting includes **bold text**, *italic text*, `inline code`, ~~strikethrough text~~, and [external links](https://github.com/anthropics/claude-code).

You can also mix formatting like **bold *and italic* together** or `code with **bold**` (though Jira may handle nested formatting differently).

Auto-linked URLs work too: https://docs.atlassian.com/adf/

## Headers

Headers create structured content:

# Main Title (H1)

## Section Header (H2)

### Subsection (H3)

#### Detail Level (H4)

##### Fine Detail (H5)

###### Smallest Header (H6)

## Lists

### Simple Bullet Lists

- First bullet item
- Second bullet item  
- Third bullet item with **bold** and *italic*

### Simple Ordered Lists

1. First numbered item
2. Second numbered item
3. Third numbered item with `code formatting`

### Nested Lists

Complex nested list structures:

- Top level bullet item
  - Nested bullet item 1
  - Nested bullet item 2
    - Deeply nested item
    - Another deep item
  - Back to second level
- Another top level item
  1. Nested ordered list
  2. Second ordered item
     - Mixed nesting with bullets
     - Another mixed item

### Mixed Nested Lists

- Bullet item with nested ordered:
  1. First ordered
  2. Second ordered
  3. Third ordered
- Another bullet item
  - With bullet nesting
  - Multiple levels

1. Ordered item with nested bullets:
   - First bullet
   - Second bullet  
   - Third bullet
2. Another ordered item
   1. With ordered nesting
   2. Multiple ordered levels

## Code Blocks

### Code Block Without Language

```
function example() {
    return "no syntax highlighting";
}
```

### Code Block With Language

```javascript
function highlightedExample() {
    const result = "syntax highlighted";
    return result;
}
```

```clojure
(defn clojure-example [x]
  "This shows Clojure syntax highlighting"
  (map inc [1 2 3 4]))
```

```python
def python_example():
    """Python syntax highlighting"""
    return [x * 2 for x in range(10)]
```

## Blockquotes

> This is a simple blockquote.
> 
> It can contain multiple paragraphs and **formatting**.

> Blockquotes can also contain:
> 
> - Lists within quotes
> - `Code formatting`
> - [Links](https://example.com)

## Tables

### Simple Table

| Column 1 | Column 2 | Column 3 |
|----------|----------|----------|
| Row 1    | Data A   | Value X  |
| Row 2    | Data B   | Value Y  |
| Row 3    | Data C   | Value Z  |

### Table with Formatting

| **Feature** | *Status* | `Priority` |
|-------------|----------|------------|
| **Headers** | ✅ Complete | `HIGH` |
| *Lists* | ✅ Complete | `MEDIUM` |
| ~~Images~~ | ❌ N/A | `LOW` |
| [Links](https://example.com) | ✅ Complete | `HIGH` |

### Table with Empty Cells

| Name | Middle | Last |
|------|--------|------|
| John |        | Doe  |
|      | Jane   |      |
| Bob  | K      | Smith|

## Horizontal Rules

Content above the rule.

---

Content below the rule.

## Complex Mixed Content

This section combines multiple features:

### Bug Report Example

**Issue Summary**: Login timeout occurs intermittently

**Environment**:
- Browser: Chrome 96+
- OS: macOS 12.1
- Server: Production

**Steps to Reproduce**:
1. Navigate to login page
2. Enter valid credentials
3. Click login button
4. Wait for timeout (30+ seconds)

**Expected vs Actual**:

| Expected | Actual |
|----------|--------|
| Login success within 5s | Timeout after 30s |
| Redirect to dashboard | Error message displayed |

**Technical Details**:

```bash
# Check server logs
tail -f /var/log/app/login.log | grep ERROR
```

**Code Investigation**:

The issue appears in the authentication handler:

```javascript
async function authenticate(credentials) {
    // Problem: No timeout configured
    const response = await fetch('/api/auth', {
        method: 'POST',
        body: JSON.stringify(credentials)
    });
    return response.json();
}
```

**Proposed Solution**:
- Add request timeout configuration
- Implement retry mechanism
- Add better error handling

> **Note**: This affects approximately 15% of users based on error logs.

**Priority**: HIGH - Blocking user access

---

## Edge Cases and Special Characters

Testing edge cases:

- Unicode characters: café, naïve, résumé
- Emoji: 🔥 💯 ✅ ❌ 🚀
- Special chars: @#$%^&*()_+-={}[]|\:";'<>?,./ 
- Code with specials: `SELECT * FROM users WHERE email LIKE '%@domain.com'`

### Escaping Test

Sometimes you need literal asterisks \*like this\* or backticks \`like this\`.

## Jira-Specific Features

References to other tickets: PROJ-123, ISSUE-456, BUG-789

User mentions might work: @username (depends on Jira configuration)

## Performance Test Content

Large content blocks to test parsing performance:

### Large List

- Item 1 with substantial content describing complex scenarios that might occur in real-world usage
- Item 2 with equally detailed information about implementation considerations and edge cases
- Item 3 discussing performance implications and optimization strategies
  - Nested item with technical details about algorithmic complexity
  - Another nested item covering memory usage patterns
    - Deep nesting to test parser robustness
    - Multiple levels to verify structural integrity
- Item 4 returning to top level with summary information

### Large Table

| Component | Status | Owner | Priority | Due Date | Notes |
|-----------|--------|--------|----------|----------|-------|
| Authentication | ✅ Complete | @john | HIGH | 2024-01-15 | Fully tested |
| Authorization | 🔄 In Progress | @jane | HIGH | 2024-01-20 | 80% complete |
| User Management | 📋 Planned | @bob | MEDIUM | 2024-02-01 | Spec review needed |
| Reporting | ❌ Blocked | @alice | LOW | TBD | Waiting for data schema |
| Integration | 🔄 In Progress | @charlie | MEDIUM | 2024-01-25 | API design phase |
| Testing | 📋 Planned | @dave | HIGH | 2024-01-30 | Automation setup |

---

**Document Purpose**: This markdown document serves as a comprehensive test of all ADF conversion features. When processed through the Crucible ADF library, it should render correctly in Jira with proper formatting, structure, and visual hierarchy.

**Usage**: `c qs -f test-adf-features.md` or `bb qs -f test-adf-features.md`