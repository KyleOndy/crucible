# Error Handling Conventions

This document outlines the standard error handling patterns for the Crucible codebase, implemented through the `lib.result` namespace.

## Core Philosophy

- **Replace `System/exit` calls** with structured data returns for better testability and composition
- **Consistent result patterns** across all functions that can fail
- **Structured error information** with types, messages, and context
- **Composable error handling** that enables functional programming patterns

## Standard Result Patterns

### Success Results

All successful operations return:

```clojure
{:success true :result data}
```

**Examples:**
```clojure
;; Simple data
(result/success "PROJ-123")
;; => {:success true :result "PROJ-123"}

;; Complex data
(result/success {:ticket-id "PROJ-123" :title "Fix bug"})
;; => {:success true :result {:ticket-id "PROJ-123" :title "Fix bug"}}

;; Nil is valid
(result/success nil)
;; => {:success true :result nil}
```

### Failure Results

All failed operations return:

```clojure
{:success false :error {:type :keyword :message "..." :context {...}}}
```

**Examples:**
```clojure
;; Basic error
(result/failure :validation "Invalid ticket ID format")
;; => {:success false :error {:type :validation :message "Invalid ticket ID format"}}

;; Error with context
(result/failure :validation "Invalid format" {:input "ABC" :expected "PROJ-123"})
;; => {:success false :error {...}}
```

## Error Types

Use these standard error type keywords:

| Error Type | Description | Exit Code |
|------------|-------------|-----------|
| `:validation` | Input validation failures | 2 |
| `:authentication` | Auth/authorization failures | 4 |
| `:network` | Network connectivity issues | 5 |
| `:not-found` | Resource not found | 6 |
| `:configuration` | Configuration problems | 3 |
| `:file-io` | File system operations | 7 |
| `:external-api` | External service failures | 1 |
| `:parse` | Data parsing/format errors | 1 |
| `:internal` | Internal application errors | 1 |

**Usage:**
```clojure
;; Use predefined constants
(result/failure (:validation result/error-types) "Invalid input")

;; Or use keywords directly
(result/failure :authentication "Invalid credentials")
```

## Function Design Patterns

### Pure vs I/O Functions

**Pure functions** return data directly (cannot fail):
```clojure
(defn parse-ticket-id [ticket-id]
  ;; Returns parsed data or nil, never fails
  (when-let [match (re-matches #"([A-Z]+)-(\d+)" ticket-id)]
    {:project (second match) :number (Integer/parseInt (nth match 2))}))
```

**Fallible operations** return result maps:
```clojure
(defn get-ticket [jira-config ticket-id]
  ;; Can fail due to network, auth, not found, etc.
  (result/chain ticket-id
                #(result/validate-required "ticket-id" %)
                #(if-let [parsed (parse-ticket-id %)]
                   (result/success parsed)
                   (result/failure :validation "Invalid ticket ID format"))
                #(fetch-from-jira jira-config %)))
```

**Effectful commands** return result maps with effects:
```clojure
(defn log-command [message]
  (let [result (write-to-log message)]
    (result/with-effects result [:wrote-file :printed-output])))
```

### Migration from System/exit

**Before:**
```clojure
(defn inspect-ticket-command [args]
  (when-not (first args)
    (println "Error: ticket ID required")
    (System/exit 1))
  ;; ... rest of function
  )
```

**After:**
```clojure
(defn inspect-ticket-command [args]
  (let [result (result/chain (first args)
                            #(result/validate-required "ticket-id" %)
                            #(inspect-ticket %))]
    (if (result/success? result)
      (println "Success:" (:result result))
      (do (println "Error:" (get-in result [:error :message]))
          (System/exit (result/exit-code-for-error (get-in result [:error :type])))))))
```

## Composition Patterns

### Chaining Operations

Use `result/chain` for sequential operations that depend on each other:

```clojure
(defn process-ticket [ticket-id]
  (result/chain ticket-id
                validate-ticket-id
                fetch-ticket-data
                validate-permissions
                enrich-ticket-data))
```

### Collecting Results

Use `result/collect-results` when you need all operations to succeed:

```clojure
(defn validate-multiple-tickets [ticket-ids]
  (->> ticket-ids
       (map validate-single-ticket)
       result/collect-results))
```

### Safe Execution

Use `result/safely` to catch exceptions:

```clojure
(defn parse-number [s]
  (result/safely #(Integer/parseInt s) :parse))
```

### Validation Patterns

Use `result/validate` and `result/validate-required` for input validation:

```clojure
(defn create-ticket [data]
  (result/chain data
                #(result/validate-required "title" (:title %))
                #(result/validate (fn [title] (< (count title) 100)) 
                                 "Title too long" (:title %))
                #(create-jira-ticket %)))
```

## Error Context Guidelines

### Provide Useful Context

**Good:**
```clojure
(result/failure :validation 
                "Invalid ticket ID format" 
                {:input ticket-id 
                 :expected "PROJ-123"
                 :pattern #"[A-Z]+-\d+"})
```

**Bad:**
```clojure
(result/failure :validation "Invalid input")
```

### Include Debugging Information

For external API failures:
```clojure
(result/failure :external-api 
                "Jira API call failed"
                {:status-code 500
                 :response-body response
                 :request-url url})
```

For parsing errors:
```clojure
(result/failure :parse 
                "Invalid JSON format"
                {:input raw-string
                 :position parse-error-position})
```

## CLI Integration

### Exit Code Handling

Use `result/exit-code-for-error` to map error types to appropriate exit codes:

```clojure
(defn main-command [args]
  (let [result (process-command args)]
    (if (result/success? result)
      (do (println "Success!")
          (System/exit 0))
      (do (println "Error:" (get-in result [:error :message]))
          (System/exit (result/exit-code-for-error 
                        (get-in result [:error :type])))))))
```

### Effect Tracking

Track side effects for better debugging and testing:

```clojure
(defn command-with-effects [args]
  (-> (process-command args)
      (result/with-effects [:printed-output :wrote-file :called-api])))
```

## Testing Patterns

### Test Success and Failure Cases

```clojure
(deftest test-get-ticket
  (testing "successful ticket retrieval"
    (let [result (get-ticket test-config "PROJ-123")]
      (is (result/success? result))
      (is (= "PROJ-123" (get-in result [:result :key])))))
  
  (testing "invalid ticket ID"
    (let [result (get-ticket test-config "invalid")]
      (is (result/failure? result))
      (is (= :validation (get-in result [:error :type]))))))
```

### Mock External Dependencies

```clojure
(deftest test-with-mock-api
  (with-redefs [jira-api/get-ticket (fn [_ _] 
                                     (result/failure :network "Connection timeout"))]
    (let [result (get-ticket test-config "PROJ-123")]
      (is (result/failure? result))
      (is (= :network (get-in result [:error :type]))))))
```

## Migration Strategy

### Phase 1: Create Result-Based Versions

Keep existing functions, create new versions that return results:

```clojure
;; Existing function (keep for compatibility)
(defn get-ticket-old [config id]
  (when-not id (System/exit 1))
  ;; ... implementation
  )

;; New result-based version
(defn get-ticket [config id]
  (result/chain id
                #(result/validate-required "ticket-id" %)
                #(fetch-ticket-data config %)))
```

### Phase 2: Update Call Sites

Replace calls to old functions with new result-based versions:

```clojure
;; Before
(get-ticket-old config id)

;; After
(let [result (get-ticket config id)]
  (if (result/success? result)
    (use-ticket-data (:result result))
    (handle-error (:error result))))
```

### Phase 3: Remove Old Functions

Once all call sites are updated, remove the old functions.

## Best Practices

1. **Fail Fast**: Validate inputs early and return structured errors
2. **Preserve Context**: Include enough context for debugging
3. **Use Standard Types**: Stick to the predefined error types
4. **Compose Functions**: Use `result/chain` and `result/collect-results`
5. **Test Both Paths**: Always test both success and failure cases
6. **Document Errors**: Include error types in function docstrings
7. **Avoid Nested Results**: Don't return `{:success true :result {:success false ...}}`

## Anti-Patterns

❌ **Don't mix result patterns with exceptions:**
```clojure
;; Bad
(defn bad-function [input]
  (if (valid? input)
    (result/success (process input))
    (throw (Exception. "Invalid input"))))
```

✅ **Use consistent result patterns:**
```clojure
;; Good
(defn good-function [input]
  (if (valid? input)
    (result/success (process input))
    (result/failure :validation "Invalid input")))
```

❌ **Don't ignore error context:**
```clojure
;; Bad
(result/failure :error "Something went wrong")
```

✅ **Provide useful context:**
```clojure
;; Good
(result/failure :network "Connection timeout" {:host "api.example.com" :timeout 5000})
```

---

This error handling system provides a consistent, testable, and composable foundation for all error scenarios in the Crucible codebase.