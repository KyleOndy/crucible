# Jira Custom Fields Guide for Crucible

This guide explains how to work with complex custom fields in Jira using the Crucible library.

## Overview

Jira custom fields can contain complex data structures beyond simple text values. They often require specific formatting with IDs, nested objects, or arrays of values.

## Custom Field Types and Structures

### 1. Simple Text/Number Fields

```clojure
{:customfield_10001 "Simple text value"
 :customfield_10002 42
 :customfield_10003 true}
```

### 2. Single Select (Dropdown) Fields

When reading from Jira:
```clojure
{:customfield_10001 {:self "https://jira.com/rest/api/2/customFieldOption/10100"
                      :value "High Priority"
                      :id "10100"}}
```

When writing to Jira (only ID needed):
```clojure
{:customfield_10001 {:id "10100"}}
```

### 3. Multi-Select Fields

```clojure
;; Reading from Jira
{:customfield_10002 [{:value "Backend" :id "10201"}
                      {:value "Frontend" :id "10202"}]}

;; Writing to Jira
{:customfield_10002 [{:id "10201"} {:id "10202"}]}
```

### 4. Cascading Select (Parent/Child)

```clojure
;; Full structure
{:customfield_10003 {:id "10301"          ; Parent value
                      :value "Hardware"
                      :child {:id "10311"   ; Child value
                             :value "Laptop"}}}

;; Writing (only IDs needed)
{:customfield_10003 {:id "10301"
                      :child {:id "10311"}}}
```

### 5. User Picker Fields

```clojure
;; Modern Jira (using Atlassian account IDs)
{:customfield_10004 {:accountId "557058:12345678-1234-1234-1234-123456789012"}}

;; Legacy (using username)
{:customfield_10004 {:name "john.doe"}}
```

### 6. Version Picker

```clojure
{:customfield_10005 {:id "10400"}}
;; or by name
{:customfield_10005 {:name "2.0.0"}}
```

### 7. Labels

```clojure
{:customfield_10006 ["backend" "urgent" "customer-reported"]}
```

### 8. Sprint Field

```clojure
;; Sprint ID (for adding to sprint)
{:customfield_10007 123}
```

## Using Custom Fields in Crucible

### Creating Issues with Custom Fields

The `create-issue` function now automatically formats custom fields:

```clojure
(require '[lib.jira :as jira])

(def issue-data
  {:fields {:project {:key "PROJ"}
            :issuetype {:name "Task"}
            :summary "Fix login bug"
            :description "Users cannot log in with special characters"
            
            ;; Custom fields - automatically formatted
            :customfield_10001 {:id "10100"}                    ; Priority dropdown
            :customfield_10002 [{:id "10201"} {:id "10202"}]   ; Multi-select
            :customfield_10003 {:id "10301" :child {:id "10311"}} ; Cascading
            :customfield_10004 {:accountId "557058:..."}        ; User picker
            :customfield_10005 ["urgent" "bug"]}})             ; Labels

(jira/create-issue jira-config issue-data)
```

### Using Helper Functions

#### Format Individual Field Values

```clojure
(require '[lib.jira :as jira])

;; Format a single field value
(jira/format-custom-field-value {:id "10100" :value "High"})
;; => {:id "10100"}

(jira/format-custom-field-value [{:id "10201"} {:id "10202"}])
;; => [{:id "10201"} {:id "10202"}]
```

#### Prepare Multiple Custom Fields

```clojure
(def custom-fields
  {"10001" "Simple text"                          ; Auto-prefixed
   :customfield_10002 {:id "10100"}              ; Keyword works
   "customfield_10003" [{:id "10201"}]           ; Already prefixed
   "epic-link" "PROJ-123"})                      ; Named field

;; With field name mappings
(def field-mappings
  {:epic-link "customfield_10008"
   :story-points "customfield_10009"})

(jira/prepare-custom-fields custom-fields field-mappings)
;; => {"customfield_10001" "Simple text"
;;     "customfield_10002" {:id "10100"}
;;     "customfield_10003" [{:id "10201"}]
;;     "customfield_10008" "PROJ-123"}
```

### Discovering Custom Field IDs

#### Get Field Metadata

```clojure
;; Get all fields
(jira/get-field-metadata jira-config {})

;; Get fields for specific project
(jira/get-field-metadata jira-config {:project-key "PROJ"})

;; Get fields for specific issue type
(jira/get-field-metadata jira-config {:project-key "PROJ"
                                      :issue-type "10001"})
```

#### Get Create Metadata (with allowed values)

```clojure
;; Get metadata for creating a specific issue type
(def metadata (jira/get-create-metadata jira-config "PROJ" "Task"))

;; View custom fields and their allowed values
(:custom-fields metadata)

;; View specific field details
(get-in metadata [:fields "customfield_10001"])
;; => {:required true
;;     :schema {:type "option" :custom "com.atlassian.jira.plugin.system.customfieldtypes:select"}
;;     :name "Priority Level"
;;     :allowedValues [{:id "10100" :value "High"}
;;                     {:id "10101" :value "Medium"}
;;                     {:id "10102" :value "Low"}]}
```

## Common Patterns

### 1. Creating Issue with Sprint

```clojure
(let [sprint-result (jira/find-sprints jira-config {:project-key "PROJ"})
      sprint-id (-> sprint-result :sprints first :id)]
  (jira/create-issue jira-config
    {:fields {:project {:key "PROJ"}
              :issuetype {:name "Task"}
              :summary "Sprint task"
              :customfield_10007 sprint-id}}))  ; Sprint field
```

### 2. Setting Epic Link

```clojure
(jira/create-issue jira-config
  {:fields {:project {:key "PROJ"}
            :issuetype {:name "Story"}
            :summary "Story in epic"
            :customfield_10008 "PROJ-100"}})  ; Epic link field
```

### 3. Complex Multi-Field Example

```clojure
(def complex-issue
  {:fields {:project {:key "PROJ"}
            :issuetype {:name "Bug"}
            :summary "Production issue"
            :priority {:id "1"}  ; Standard field
            
            ;; Various custom fields
            :customfield_10001 {:id "10100"}                       ; Severity
            :customfield_10002 [{:id "10201"} {:id "10202"}]      ; Affected systems
            :customfield_10003 {:id "10301" :child {:id "10311"}} ; Category
            :customfield_10004 {:accountId "557058:..."}          ; QA assignee
            :customfield_10005 ["production" "urgent"]            ; Labels
            :customfield_10006 "2024.1.0"                        ; Affected version
            :customfield_10007 123                               ; Sprint ID
            :customfield_10008 "PROJ-50"}})                      ; Epic link

(jira/create-issue jira-config complex-issue)
```

## Troubleshooting

### Common Errors

1. **"Field 'customfield_10001' cannot be set"**
   - Field might not be available for the issue type
   - Check field permissions for your user
   - Use `get-create-metadata` to see available fields

2. **"Option id '10100' is not valid"**
   - The ID doesn't match any allowed values
   - Use `get-create-metadata` to find valid option IDs
   - Check if field is context-specific to project

3. **"Field 'customfield_10001' is required"**
   - Add the required field to your issue data
   - Check field schema with `get-create-metadata`

### Debugging Tips

1. **Fetch full ticket data to see field structure:**
   ```clojure
   (jira/get-ticket-full jira-config "PROJ-123")
   ```

2. **Enable debug mode for detailed API responses:**
   ```clojure
   (jira/create-issue jira-config issue-data)
   ;; Check :details key in error response for full API error
   ```

3. **Test field values incrementally:**
   - Start with required fields only
   - Add custom fields one at a time
   - Verify each field works before adding more

## Integration with Quick Start (qs)

To use custom fields with the `qs` command, you'll need to modify the quick start functionality to accept custom field parameters. The current implementation supports basic fields, but can be extended to handle custom fields through configuration or command-line options.

Example configuration in `.crucible.edn`:
```clojure
{:jira {:custom-field-defaults
        {:customfield_10001 {:id "10100"}  ; Default priority
         :customfield_10007 :auto-detect}}} ; Auto-detect sprint
```

## Best Practices

1. **Cache Field Metadata**: Field IDs and allowed values don't change often
2. **Use Field Mappings**: Create human-readable names for commonly used fields
3. **Validate Early**: Check field values before sending to API
4. **Handle Errors Gracefully**: Parse `:details` from error responses
5. **Document Field Meanings**: Keep notes on what each custom field represents
6. **Test in Sandbox**: Test complex field structures in a test Jira instance first