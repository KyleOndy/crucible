# AI-Enhanced Jira Integration: Step-by-Step Implementation Guide

## Overview

This document provides detailed implementation steps with success criteria for adding AI-enhanced ticket creation to Crucible. Each phase can be tested independently before proceeding to the next.

### Architecture Summary

- **Editor Workflow**: Git-style ticket creation (first line = title, rest = description)
- **AI Enhancement**: Optional preprocessing via SMG gateway to rewrite/spell-check content
- **Markdown Support**: Convert markdown to Atlassian Document Format (ADF) for rich text in Jira
- **Graceful Degradation**: Every feature has fallbacks, never blocks ticket creation

### High-Level Flow

```
User Input → Editor (optional) → AI Enhancement (optional) → Markdown→ADF → Jira API
```

### Key Design Principles

1. **Optional at every step** - AI, editor, ADF conversion all configurable
2. **Fail-safe** - Never prevent ticket creation due to enhancement failures  
3. **Cross-platform** - Developed on Linux, used on macOS
4. **Familiar UX** - Editor workflow mimics git commit style

---

## Phase 1: Basic Editor Workflow (No AI, Plain Text)

### Goal
Enable ticket creation via editor with git-style format (first line = title, rest = description).

### Prerequisites
- Existing `c qs` command working
- `$EDITOR` environment variable set
- Basic Jira configuration in place

### Implementation Steps

#### Step 1.1: Add Editor Functions to crucible.clj

**Location**: After the `launch-editor` function in `core/bin/crucible.clj`

```clojure
(defn create-ticket-template
  "Create template for editor"
  []
  (str "\n"
       "\n"
       "# Enter ticket title on first line\n"
       "# Enter description below (markdown supported)\n"
       "# Lines starting with # are comments (ignored)\n"
       "# Save and exit to create ticket, exit without saving to cancel\n"))

(defn parse-editor-content
  "Parse content from editor into title and description"
  [content]
  (let [lines (str/split-lines content)
        non-comment-lines (filter #(not (str/starts-with? % "#")) lines)
        non-empty-lines (filter #(not (str/blank? %)) non-comment-lines)]
    (when (seq non-empty-lines)
      {:title (first non-empty-lines)
       :description (str/join "\n" (rest non-empty-lines))})))

(defn open-ticket-editor
  "Open editor for ticket creation, return parsed content"
  []
  (let [temp-file (fs/create-temp-file {:prefix "crucible-ticket-" 
                                        :suffix ".md"})
        template (create-ticket-template)]
    (try
      (spit temp-file template)
      (launch-editor temp-file)
      (let [content (slurp temp-file)
            parsed (parse-editor-content content)]
        (fs/delete temp-file)
        parsed)
      (catch Exception e
        (when (fs/exists? temp-file)
          (fs/delete temp-file))
        (throw e)))))

(defn parse-flags
  "Simple flag parsing for commands. Returns {:args [...] :flags {...}}"
  [args]
  (let [flags (atom {})
        remaining-args (atom [])]
    (doseq [arg args]
      (cond
        (or (= arg "-e") (= arg "--editor"))
        (swap! flags assoc :editor true)
        
        (= arg "--dry-run")
        (swap! flags assoc :dry-run true)
        
        (str/starts-with? arg "-")
        (println (str "Warning: Unknown flag: " arg))
        
        :else
        (swap! remaining-args conj arg)))
    {:args @remaining-args :flags @flags}))
```

#### Step 1.2: Update quick-story-command

Replace the existing `quick-story-command` function:

```clojure
(defn quick-story-command
  "Create a quick Jira story with minimal input or via editor"
  [args]
  (let [{:keys [args flags]} (parse-flags args)
        summary (first args)
        {:keys [editor dry-run]} flags]
    
    ;; Get ticket data from editor or command line
    (let [ticket-data (if editor
                        (open-ticket-editor)
                        (when summary
                          {:title summary :description ""}))]
      
      (when-not ticket-data
        (if editor
          (do
            (println "Editor cancelled or no content provided")
            (System/exit 0))
          (do
            (println "Error: story summary required")
            (println "Usage: crucible quick-story \"Your story summary\"")
            (println "   or: crucible qs \"Your story summary\"")
            (println "   or: crucible qs -e  (open editor)")
            (System/exit 1))))
      
      (let [{:keys [title description]} ticket-data]
        
        ;; Handle dry-run mode
        (when dry-run
          (println "=== DRY RUN ===")
          (println (str "Title: " title))
          (println (str "Description: " description))
          (System/exit 0))
        
        ;; Rest of existing Jira creation logic...
        ;; [Keep all the existing config validation and ticket creation code]
        ))))
```

#### Step 1.3: Update Command Dispatcher

In `dispatch-command`, change:
```clojure
("quick-story" "qs") (quick-story-command args)  ; Pass all args, not just first
```

#### Step 1.4: Update Help Text

Add to the help text in `help-text` function:

```
Quick Story Options:
  -e, --editor      Open editor for ticket creation (git commit style)
  --dry-run         Preview ticket without creating

Editor Mode:
  When using -e/--editor, enter:
  - First line: ticket title
  - Remaining lines: description (markdown supported)
  - Lines starting with # are ignored as comments
  - Save and exit to create ticket, exit without saving to cancel
```

### Test Commands

```bash
# Test 1: Help shows new options
c help

# Test 2: Flag parsing with dry-run
c qs "Fix authentication bug" --dry-run
# Expected: Shows title and empty description

# Test 3: Editor content parsing (test function)
bb -e "(load-file \"core/bin/crucible.clj\")
(println (crucible/parse-editor-content \"Title here\n\nDescription here\n# Comment\"))"
# Expected: {:title "Title here" :description "Description here"}

# Test 4: Template generation
bb -e "(load-file \"core/bin/crucible.clj\") (println (crucible/create-ticket-template))"
# Expected: Shows template with comment instructions
```

### Success Criteria - Phase 1

✅ Help text shows new editor options  
✅ `c qs "title" --dry-run` shows parsed content  
✅ `c qs --unknown-flag` shows warning but continues  
✅ Flag parsing correctly separates flags from arguments  
✅ Content parsing ignores comment lines (starting with #)  
✅ Content parsing uses first line as title, rest as description  
✅ Template includes helpful instructions  

### Troubleshooting

- **Editor doesn't open**: Check `echo $EDITOR`, ensure it's set
- **Parsing issues**: Add debug println to see raw content
- **Temp file not deleted**: Check exception handling in open-ticket-editor

---

## Phase 2: Markdown to ADF Conversion

### Goal
Convert markdown descriptions to ADF format for rich text in Jira Cloud.

### Prerequisites
- Phase 1 complete and working
- Node.js installed (`node --version`)
- npm available (`npm --version`)

### Background: Jira Text Formats

- **Jira Cloud API v3**: Requires **Atlassian Document Format (ADF)** - JSON structure
- **Jira Server/DC**: Uses wiki markup or plain text
- **Fallback Strategy**: Always fall back to plain text if ADF conversion fails

### Implementation Steps

#### Step 2.1: Install Dependencies

```bash
cd /path/to/crucible
npm init -y  # If no package.json exists
npm install marklassian
```

**Why Marklassian?**
- Modern, lightweight JavaScript library (12kb)
- Actively maintained
- Supports common markdown: headers, bold, italic, code blocks, lists, tables
- Works in all JavaScript environments

#### Step 2.2: Create Node.js Converter Script

**File**: `scripts/markdown-to-adf.js`

```javascript
#!/usr/bin/env node

try {
  const { markdownToAdf } = require('marklassian');
  
  let markdown = '';
  process.stdin.setEncoding('utf8');

  process.stdin.on('readable', () => {
    let chunk;
    while ((chunk = process.stdin.read()) !== null) {
      markdown += chunk;
    }
  });

  process.stdin.on('end', () => {
    try {
      if (markdown.trim() === '') {
        // Return empty ADF document for empty input
        console.log(JSON.stringify({
          version: 1,
          type: "doc",
          content: []
        }));
        process.exit(0);
      }

      const adf = markdownToAdf(markdown);
      console.log(JSON.stringify(adf));
      process.exit(0);
    } catch (error) {
      console.error(JSON.stringify({
        error: error.message,
        input: markdown
      }));
      process.exit(1);
    }
  });

} catch (error) {
  if (error.code === 'MODULE_NOT_FOUND') {
    console.error(JSON.stringify({
      error: "marklassian not installed. Run: npm install marklassian"
    }));
  } else {
    console.error(JSON.stringify({
      error: error.message
    }));
  }
  process.exit(1);
}
```

Make executable:
```bash
chmod +x scripts/markdown-to-adf.js
```

#### Step 2.3: Create Clojure Wrapper

**File**: `core/lib/markdown.clj`

```clojure
(ns lib.markdown
  (:require [babashka.process :as p]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn check-dependencies
  "Check if Node.js and marklassian are available"
  []
  (let [node-available? (try
                          (p/shell {:out :string :err :string} "node" "--version")
                          true
                          (catch Exception _ false))
        marklassian-available? (when node-available?
                                 (fs/exists? "node_modules/marklassian"))]
    {:node node-available?
     :marklassian marklassian-available?}))

(defn ensure-marklassian-installed
  "Check if marklassian is installed, install if needed"
  []
  (let [deps (check-dependencies)]
    (cond
      (not (:node deps))
      (do
        (println "Warning: Node.js not found. Falling back to plain text.")
        false)
      
      (not (:marklassian deps))
      (do
        (println "Installing marklassian...")
        (try
          (p/shell "npm" "install" "marklassian")
          (println "✓ marklassian installed successfully")
          true
          (catch Exception e
            (println (str "Failed to install marklassian: " (.getMessage e)))
            false)))
      
      :else true)))

(defn markdown->adf
  "Convert markdown to ADF using marklassian via Node.js"
  [markdown-text]
  (try
    ;; Check if converter exists and dependencies are available
    (when-not (fs/exists? "scripts/markdown-to-adf.js")
      (throw (ex-info "Markdown converter script not found" {})))
    
    (when-not (ensure-marklassian-installed)
      (throw (ex-info "Dependencies not available" {})))
    
    (let [result (p/shell {:in markdown-text 
                          :out :string
                          :err :string
                          :throw false}
                         "node" "scripts/markdown-to-adf.js")]
      (if (zero? (:exit result))
        (json/parse-string (:out result) true)
        (let [error-info (try
                           (json/parse-string (:err result) true)
                           (catch Exception _
                             {:error (:err result)}))]
          (throw (ex-info "ADF conversion failed" error-info)))))
    
    (catch Exception e
      (println (str "Markdown conversion failed: " (.getMessage e)))
      ;; Fallback to plain text ADF
      (plain-text->adf markdown-text))))

(defn plain-text->adf
  "Convert plain text to basic ADF paragraph"
  [text]
  (if (str/blank? text)
    {:version 1
     :type "doc"
     :content []}
    {:version 1
     :type "doc"
     :content [{:type "paragraph"
               :content [{:type "text"
                         :text text}]}]}))

(defn test-conversion
  "Test markdown to ADF conversion with sample content"
  []
  (let [test-markdown "# Test Header\n\n**Bold text** and *italic text*\n\n- List item 1\n- List item 2\n\n`inline code`"]
    (println "Testing markdown conversion:")
    (println "Input:" test-markdown)
    (println "Output:" (markdown->adf test-markdown))))
```

#### Step 2.4: Update Jira Integration

**File**: `core/lib/jira.clj`

Add function to handle ADF in create-issue:

```clojure
(defn prepare-description
  "Prepare description field - handle both string and ADF format"
  [description]
  (cond
    ;; If it's already ADF (has :version key), use as-is
    (and (map? description) (:version description))
    description
    
    ;; If it's a string, convert to simple ADF
    (string? description)
    (if (str/blank? description)
      nil  ; Jira accepts nil for empty descriptions
      {:version 1
       :type "doc"
       :content [{:type "paragraph"
                 :content [{:type "text"
                           :text description}]}]})
    
    ;; Otherwise, use as-is (shouldn't happen)
    :else description))
```

Then in `create-issue`, update the issue-data preparation:

```clojure
(let [issue-data {:fields {:project {:key (:default-project jira-config)}
                           :summary title
                           :issuetype {:name (:default-issue-type jira-config)}
                           :description (prepare-description description)}}]
  ;; ... rest of create-issue logic
```

#### Step 2.5: Integration with quick-story-command

**File**: `core/bin/crucible.clj`

Add require for markdown namespace:
```clojure
(load-file "core/lib/markdown.clj")
```

Update the ticket creation section in `quick-story-command`:

```clojure
;; After getting title and description, before creating issue-data:
(let [use-adf (get-in config [:jira :use-adf] true)
      final-description (if (and use-adf (not (str/blank? description)))
                          (lib.markdown/markdown->adf description)
                          description)]
  ;; Use final-description in issue-data
  (let [issue-data {:fields {:project {:key (:default-project jira-config)}
                             :summary title
                             :issuetype {:name (:default-issue-type jira-config)}
                             :description final-description}}]
    ;; ... rest of creation logic
```

#### Step 2.6: Update Configuration

**File**: `core/lib/config.clj`

Add to `default-config`:

```clojure
:jira {:base-url nil
       :username nil
       :api-token nil
       :default-project nil
       :default-issue-type "Task"
       :default-story-points 1
       :auto-assign-self true
       :auto-add-to-sprint true
       :use-adf true              ; Use ADF format for descriptions
       :fallback-to-plain true}   ; Fallback if ADF fails
```

### Test Commands

```bash
# Test 1: Node script works (if Node.js available)
echo "# Hello\n**bold** text" | node scripts/markdown-to-adf.js

# Test 2: Dependency checking
bb -e "(load-file \"core/lib/markdown.clj\") (lib.markdown/check-dependencies)"

# Test 3: Clojure wrapper test
bb -e "(load-file \"core/lib/markdown.clj\") (lib.markdown/test-conversion)"

# Test 4: Create ticket with markdown (dry-run)
c qs -e --dry-run
# In editor, enter:
# Fix authentication bug
# 
# The issue occurs when:
# - User enters **special characters**
# - Password contains `$` symbols
# 
# ## Solution
# Escape special characters properly.

# Test 5: Plain text fallback
# (Disconnect Node.js or move marklassian)
c qs -e --dry-run
# Should still work, falling back to plain text
```

### Success Criteria - Phase 2

✅ Node script converts basic markdown to ADF JSON  
✅ Dependency checking works (Node.js and marklassian)  
✅ Auto-installation of marklassian attempts to run  
✅ Clojure wrapper handles errors gracefully  
✅ Fallback to plain text works when conversion fails  
✅ Configuration controls ADF usage  
✅ Jira API receives properly formatted description field  
✅ Markdown features render in Jira Cloud:
- **Bold text**
- *Italic text*  
- `inline code`
- Code blocks
- Bullet lists
- Headers

### Troubleshooting

- **Node not found**: Ensure Node.js is installed and in PATH
- **Marklassian install fails**: Check npm permissions, try `sudo npm install -g marklassian`
- **ADF not rendering**: Verify you're using Jira Cloud (not Server/DC)
- **Invalid ADF errors**: Check JSON structure matches examples in docs

---

## Phase 3: AI Gateway Integration

### Goal
Add optional AI enhancement of ticket title and description via SMG gateway.

### Prerequisites
- Phases 1-2 working
- SMG gateway URL and API key available
- Network access to gateway from development/usage machines

### Implementation Steps

#### Step 3.1: Create AI Client

**File**: `core/lib/ai.clj`

```clojure
(ns lib.ai
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private cache (atom {}))

(defn clear-cache! 
  "Clear the AI response cache"
  []
  (reset! cache {}))

(defn cache-key
  "Generate cache key for title/description pair"
  [title description]
  (str (hash [title description])))

(defn enhance-content
  "Send content to AI gateway for enhancement"
  [{:keys [title description]} ai-config]
  (let [cache-key-val (cache-key title description)
        cached (get @cache cache-key-val)]
    
    ;; Return cached result if available and not expired
    (if (and cached 
             (< (- (System/currentTimeMillis) (:timestamp cached 0))
                (* 1000 60 (:cache-ttl-minutes ai-config 60))))
      (do
        (println "Using cached AI response")
        (:result cached))
      
      ;; Make fresh API call
      (try
        (println "Calling AI gateway...")
        (let [request-body {:prompt "Enhance this Jira ticket for clarity and professionalism. Fix spelling and grammar. Keep the same general meaning but improve readability."
                           :title title
                           :description description
                           :format "jira"}
              
              response (http/post (:gateway-url ai-config)
                                 {:headers {"Authorization" (str "Bearer " (:api-key ai-config))
                                           "Content-Type" "application/json"}
                                  :body (json/generate-string request-body)
                                  :timeout (:timeout-ms ai-config 5000)
                                  :throw false})]
          
          (cond
            (= 200 (:status response))
            (let [result (json/parse-string (:body response) true)
                  enhanced {:title (:enhanced_title result (:title result title))
                           :description (:enhanced_description result (:description result description))}]
              ;; Cache the result
              (when (:cache-enabled ai-config true)
                (swap! cache assoc cache-key-val 
                       {:result enhanced 
                        :timestamp (System/currentTimeMillis)}))
              enhanced)
            
            (= 429 (:status response))
            (do
              (println "AI gateway rate limited, using original content")
              {:title title :description description})
            
            :else
            (do
              (println (str "AI gateway returned " (:status response) ": " (:body response)))
              {:title title :description description})))
        
        (catch java.net.SocketTimeoutException _
          (println "AI gateway timeout, using original content")
          {:title title :description description})
        
        (catch Exception e
          (println (str "AI enhancement failed: " (.getMessage e)))
          {:title title :description description})))))

(defn test-gateway
  "Test AI gateway connectivity and authentication"
  [ai-config]
  (try
    (let [response (http/get (str (:gateway-url ai-config) "/health")
                            {:headers {"Authorization" (str "Bearer " (:api-key ai-config))}
                             :timeout 3000
                             :throw false})]
      {:success (< (:status response) 400)
       :status (:status response)
       :message (if (< (:status response) 400)
                  "Gateway is accessible"
                  (str "Gateway error: " (:status response)))})
    (catch Exception e
      {:success false
       :error (.getMessage e)
       :message (str "Cannot reach gateway: " (.getMessage e))})))

(defn show-diff
  "Show difference between original and enhanced content"
  [original enhanced]
  (when (not= original enhanced)
    (println "\n=== AI Enhancement Results ===")
    (when (not= (:title original) (:title enhanced))
      (println "Title:")
      (println (str "  Before: " (:title original)))
      (println (str "  After:  " (:title enhanced))))
    (when (not= (:description original) (:description enhanced))
      (println "Description:")
      (println (str "  Before: " (str/replace (:description original) "\n" "\\n")))
      (println (str "  After:  " (str/replace (:description enhanced) "\n" "\\n"))))
    (println "==============================\n")))
```

#### Step 3.2: Update Configuration

**File**: `core/lib/config.clj`

Add to `default-config`:

```clojure
:ai {:enabled false
     :gateway-url nil           ; SMG endpoint URL
     :api-key nil              ; or "pass:work/smg-key" for password manager
     :timeout-ms 5000
     :enhance-summary true
     :enhance-description true
     :cache-enabled true
     :cache-ttl-minutes 60}
```

#### Step 3.3: Add AI Flags and Integration

**File**: `core/bin/crucible.clj`

Update `parse-flags` function:

```clojure
(defn parse-flags
  "Simple flag parsing for commands. Returns {:args [...] :flags {...}}"
  [args]
  (let [flags (atom {})
        remaining-args (atom [])]
    (doseq [arg args]
      (cond
        (or (= arg "-e") (= arg "--editor"))
        (swap! flags assoc :editor true)
        
        (= arg "--dry-run")
        (swap! flags assoc :dry-run true)
        
        (= arg "--ai")
        (swap! flags assoc :ai true)
        
        (= arg "--no-ai")
        (swap! flags assoc :no-ai true)
        
        (= arg "--preview")
        (swap! flags assoc :preview true)
        
        (str/starts-with? arg "-")
        (println (str "Warning: Unknown flag: " arg))
        
        :else
        (swap! remaining-args conj arg)))
    {:args @remaining-args :flags @flags}))
```

Add require for ai namespace:
```clojure
(load-file "core/lib/ai.clj")
```

Update `quick-story-command` to integrate AI:

```clojure
(defn quick-story-command
  "Create a quick Jira story with minimal input or via editor"
  [args]
  (let [{:keys [args flags]} (parse-flags args)
        summary (first args)
        {:keys [editor dry-run ai no-ai preview]} flags
        
        config (config/load-config)
        ai-config (:ai config)
        ai-enabled (and (not no-ai)
                       (or ai (:enabled ai-config false))
                       (:gateway-url ai-config))]
    
    ;; Get initial ticket data from editor or command line
    (let [initial-data (if editor
                         (open-ticket-editor)
                         (when summary
                           {:title summary :description ""}))
          
          ;; Apply AI enhancement if enabled
          enhanced-data (if ai-enabled
                          (do
                            (println "Enhancing content with AI...")
                            (lib.ai/enhance-content initial-data ai-config))
                          initial-data)
          
          ;; If AI was used and editor requested, show enhanced version for review
          final-data (if (and ai-enabled editor preview)
                       (do
                         (lib.ai/show-diff initial-data enhanced-data)
                         (println "Opening editor with enhanced content for review...")
                         ;; Create temp file with enhanced content and open editor
                         ;; Implementation depends on requirements
                         enhanced-data)
                       enhanced-data)]
      
      ;; Validation and error handling...
      (when-not final-data
        (if editor
          (do (println "Editor cancelled or no content provided")
              (System/exit 0))
          (do (println "Error: story summary required")
              ;; ... existing error message
              (System/exit 1))))
      
      (let [{:keys [title description]} final-data]
        
        ;; Handle preview mode
        (when preview
          (println "=== PREVIEW MODE ===")
          (when (not= initial-data final-data)
            (lib.ai/show-diff initial-data final-data))
          (println (str "Final Title: " title))
          (println (str "Final Description: " description))
          (println "====================")
          (System/exit 0))
        
        ;; Handle dry-run mode  
        (when dry-run
          (println "=== DRY RUN ===")
          (println (str "Title: " title))
          (println (str "Description: " description))
          (System/exit 0))
        
        ;; Continue with existing Jira ticket creation logic...
        ;; [All the existing validation and creation code]
        ))))
```

#### Step 3.4: Update Help Text

Add to help text:

```
Quick Story Options:
  -e, --editor      Open editor for ticket creation (git commit style)
  --dry-run         Preview ticket without creating
  --ai              Enable AI enhancement (overrides config)
  --no-ai           Disable AI enhancement (overrides config)
  --preview         Show AI enhancement results without creating ticket

AI Enhancement:
  When enabled, sends title/description to AI gateway for:
  - Grammar and spelling correction
  - Clarity and professional tone
  - Preserving original meaning and intent
  - Results are cached to avoid duplicate API calls
```

### Test Commands

```bash
# Test 1: Gateway connectivity (requires config)
bb -e "(load-file \"core/lib/ai.clj\") 
       (load-file \"core/lib/config.clj\")
       (lib.ai/test-gateway (:ai (lib.config/load-config)))"

# Test 2: AI enhancement with preview
c qs "fix bug in login" --ai --preview

# Test 3: Editor + AI workflow  
c qs -e --ai --preview

# Test 4: AI disabled override
c qs "exact title" --no-ai

# Test 5: Caching test (run twice with same input)
c qs "same input" --ai --preview
c qs "same input" --ai --preview  # Should show "Using cached AI response"

# Test 6: Timeout test (set very low timeout)
# Edit config to have timeout-ms: 100
c qs "test timeout" --ai --preview
```

### Success Criteria - Phase 3

✅ Gateway connectivity test passes with valid config  
✅ AI enhancement improves text quality  
✅ `--ai` flag enables enhancement  
✅ `--no-ai` flag disables enhancement  
✅ `--preview` shows before/after comparison  
✅ Timeout handling works (test with low timeout)  
✅ Rate limiting handled gracefully (429 status)  
✅ Authentication errors handled (401 status)  
✅ Caching prevents duplicate API calls  
✅ Falls back to original content on any error  
✅ Network errors don't block ticket creation  

### Troubleshooting

- **401 Unauthorized**: Check API key format and permissions
- **Gateway timeout**: Increase timeout-ms in config or check network
- **No enhancement**: Verify gateway URL and request format
- **Cache issues**: Use `lib.ai/clear-cache!` to reset
- **Network errors**: Check proxy settings if in corporate environment

---

## Phase 4: Integration Testing

### Goal
Verify all components work together seamlessly across different scenarios.

### Test Scenarios

#### Scenario 4.1: Plain Text Path (No AI, No ADF)
```bash
# Config: :ai {:enabled false} :jira {:use-adf false}
c qs "Simple ticket without enhancements"
```

**Expected Results:**
✅ Creates ticket with plain text description  
✅ No AI calls made  
✅ No markdown conversion  
✅ Original workflow preserved  

#### Scenario 4.2: Editor Only (No AI)
```bash
c qs -e --no-ai
# In editor: 
# Fix critical authentication bug
# 
# Users cannot log in when password contains special characters.
# This affects approximately 15% of our user base.
```

**Expected Results:**
✅ Editor opens with template  
✅ Parses first line as title correctly  
✅ Multi-line description preserved  
✅ Creates ticket in Jira  
✅ No AI enhancement performed  

#### Scenario 4.3: Markdown to ADF (No AI)
```bash
c qs -e --no-ai
# In editor:
# Implement user dashboard
#
# Create a new dashboard with the following features:
# 
# ## Requirements
# - **Real-time** data updates
# - *Responsive* design for mobile
# - Support for `dark mode`
# 
# ## Technical Notes
# ```javascript
# const updateDashboard = () => {
#   fetch('/api/dashboard').then(render);
# };
# ```
```

**Expected Results:**
✅ Markdown converts to ADF successfully  
✅ Formatting appears correctly in Jira:
- Bold text (**Real-time**)
- Italic text (*Responsive*)  
- Inline code (`dark mode`)
- Code block with syntax highlighting
- Headers render as proper headings
✅ Falls back to plain text if ADF conversion fails  

#### Scenario 4.4: AI Enhancement Only (No Editor)
```bash
c qs "fix bug login users cant signin" --ai
```

**Expected Results:**
✅ AI improves grammar: "Fix login bug - users cannot sign in"  
✅ Creates ticket with enhanced title  
✅ Shows AI enhancement in progress indicator  
✅ Uses original text if AI fails  

#### Scenario 4.5: Full Workflow (Editor + AI + ADF)
```bash
c qs -e --ai
# In editor:
# user cant login anymore
# 
# the login form is broken and users r complaining
```

**Expected Results:**
✅ Opens editor for initial input  
✅ AI enhances content:
- Title: "Users cannot log in anymore"  
- Description: "The login form is broken and users are complaining"
✅ Converts enhanced markdown to ADF  
✅ Creates ticket in Jira with formatted content  

#### Scenario 4.6: Error Recovery and Fallbacks

**Test 4.6a: AI Gateway Down**
```bash
# Disconnect network or use invalid gateway URL
c qs "test content" --ai
```
✅ AI fails gracefully with timeout message  
✅ Uses original content  
✅ Ticket creation proceeds normally  

**Test 4.6b: Node.js Not Available**  
```bash
# Rename node binary temporarily
c qs -e
# Enter markdown content in editor
```
✅ Warns about Node.js not found  
✅ Falls back to plain text  
✅ Ticket creation succeeds  

**Test 4.6c: Marklassian Not Installed**
```bash
# Remove node_modules/marklassian
c qs -e
# Enter markdown in editor
```
✅ Attempts to install marklassian  
✅ Falls back to plain text if install fails  
✅ Ticket creation continues  

#### Scenario 4.7: Configuration Combinations

**Test all major config combinations:**

```bash
# Test matrix:
# AI: enabled/disabled
# ADF: enabled/disabled  
# Editor: used/not used

# 1. AI=true, ADF=true, Editor=false
c qs "test" --ai

# 2. AI=true, ADF=false, Editor=true  
c qs -e --ai

# 3. AI=false, ADF=true, Editor=true
c qs -e --no-ai

# 4. All disabled (baseline)
c qs "test" --no-ai
```

### Performance Testing

#### Timing Benchmarks
```bash
# Test response times for different paths:

# 1. Baseline (no enhancements)
time c qs "Simple ticket" --dry-run
# Target: < 1 second

# 2. With AI (successful)
time c qs "Test ticket" --ai --dry-run  
# Target: < 5 seconds

# 3. With AI (timeout)
# Set timeout-ms: 1000 in config
time c qs "Test ticket" --ai --dry-run
# Target: ~1 second (fails quickly)

# 4. Full flow with editor
# Manual timing: editor open -> content entry -> AI -> ADF -> creation
# Target: Total interaction < 30 seconds
```

#### Memory Usage
```bash
# Check memory usage doesn't grow excessively
bb -e "(load-file \"core/bin/crucible.clj\")
       (dotimes [i 10]
         (crucible/parse-editor-content \"test content\n\ndescription\")
         (System/gc)
         (println \"Iteration\" i \"Memory:\" 
                  (/ (.totalMemory (Runtime/getRuntime)) 1024 1024) \"MB\"))"
```

### Cross-Platform Validation

#### Linux Development Environment
✅ All components work in development  
✅ Tests pass  
✅ Dependencies install correctly  

#### macOS Production Environment
✅ `c qs` basic functionality works  
✅ Editor opens correctly (vim/nano/VSCode)  
✅ Node.js and npm available  
✅ Marklassian installs successfully  
✅ AI gateway accessible from Mac  
✅ Jira API calls succeed  

### Integration Checklist

#### Pre-Implementation Checklist
- [ ] Phase 1 tests all pass
- [ ] Phase 2 tests all pass  
- [ ] Phase 3 tests all pass
- [ ] All dependencies documented

#### Post-Implementation Checklist  
- [ ] All 7 test scenarios pass
- [ ] Performance meets targets
- [ ] Error recovery works
- [ ] Configuration options tested
- [ ] Cross-platform compatibility verified
- [ ] Documentation updated

#### Production Readiness
- [ ] SMG gateway credentials configured
- [ ] Jira instance accessible  
- [ ] Error monitoring in place
- [ ] Team trained on new features
- [ ] Rollback plan documented

---

## Configuration Examples

### Basic Configuration

**File**: `~/.config/crucible/config.edn` or `./crucible.edn`

```clojure
{:jira {:base-url "https://company.atlassian.net"
        :username "user@company.com"
        :api-token "pass:work/jira-token"
        :default-project "PROJ"
        :default-issue-type "Task"
        :auto-assign-self true
        :auto-add-to-sprint true
        :use-adf true
        :fallback-to-plain true}
        
 :ai {:enabled false  ; Start with AI disabled
      :gateway-url nil
      :api-key nil}}
```

### Full AI-Enabled Configuration

```clojure
{:jira {:base-url "https://company.atlassian.net"
        :username "user@company.com"
        :api-token "pass:work/jira-token"
        :default-project "PROJ"
        :default-issue-type "Story"
        :auto-assign-self true
        :auto-add-to-sprint true
        :use-adf true
        :fallback-to-plain true}
        
 :ai {:enabled true
      :gateway-url "https://smg.company.com/api/v1/enhance"
      :api-key "pass:work/smg-api-key"
      :timeout-ms 5000
      :enhance-summary true
      :enhance-description true
      :cache-enabled true
      :cache-ttl-minutes 30}}  ; Shorter cache for active development
```

### Development/Testing Configuration

```clojure
{:jira {:base-url "https://test-jira.company.com"
        :username "test-user@company.com"
        :api-token "test-token-123"
        :default-project "TEST"
        :default-issue-type "Bug"
        :use-adf false  ; Use plain text for testing
        :fallback-to-plain true}
        
 :ai {:enabled true
      :gateway-url "https://dev-smg.company.com/api/v1/enhance"
      :api-key "dev-key-123"
      :timeout-ms 10000  ; Longer timeout for dev environment
      :cache-enabled false  ; Disable cache during testing
      :enhance-summary true
      :enhance-description true}}
```

### Environment Variable Override

```bash
# Override config with environment variables
export CRUCIBLE_JIRA_URL="https://company.atlassian.net"
export CRUCIBLE_JIRA_USER="user@company.com"  
export CRUCIBLE_JIRA_TOKEN="actual-token-here"
export CRUCIBLE_AI_GATEWAY_URL="https://smg.company.com/api/v1/enhance"
export CRUCIBLE_AI_API_KEY="actual-api-key-here"

# Config file can then have minimal settings
{:jira {:default-project "PROJ"
        :default-issue-type "Task"}
 :ai {:enabled true}}
```

---

## Troubleshooting Guide

### Phase 1 Issues (Editor Workflow)

#### Problem: Editor doesn't open
**Symptoms**: Error "EDITOR environment variable not set"
**Solutions**:
```bash
# Check current editor setting
echo $EDITOR

# Set editor temporarily
export EDITOR=nano  # or vim, code, etc.

# Set permanently in shell profile
echo 'export EDITOR=nano' >> ~/.bashrc
source ~/.bashrc
```

#### Problem: Content parsing fails
**Symptoms**: Title/description not separated correctly
**Debug steps**:
```bash
# Test parsing function directly
bb -e "(load-file \"core/bin/crucible.clj\")
       (println (crucible/parse-editor-content \"Your test content here\"))"

# Check for invisible characters
cat -A /tmp/test-content.md
```

#### Problem: Flags not recognized
**Symptoms**: Unknown flag warnings or flags ignored
**Solution**: Check `parse-flags` function supports the flag you're using

### Phase 2 Issues (Markdown to ADF)

#### Problem: Node.js not found
**Symptoms**: "bash: node: command not found"
**Solutions**:
```bash
# Install Node.js (Ubuntu/Debian)
curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -
sudo apt-get install -y nodejs

# Install Node.js (macOS with Homebrew)
brew install node

# Verify installation
node --version
npm --version
```

#### Problem: Marklassian installation fails
**Symptoms**: "Failed to install marklassian" or npm errors
**Solutions**:
```bash
# Fix npm permissions (if permission errors)
mkdir ~/.npm-global
npm config set prefix '~/.npm-global'
echo 'export PATH=~/.npm-global/bin:$PATH' >> ~/.bashrc
source ~/.bashrc

# Clear npm cache and retry
npm cache clean --force
rm -rf node_modules package-lock.json
npm install marklassian

# Manual installation
npm install -g marklassian  # Global install
```

#### Problem: ADF not rendering in Jira
**Symptoms**: Markdown shows as plain text in Jira
**Root causes**:
- Using Jira Server instead of Jira Cloud
- Invalid ADF JSON structure
- Jira instance doesn't support ADF

**Solutions**:
```bash
# Verify Jira type
curl -H "Authorization: Bearer $TOKEN" "$JIRA_URL/rest/api/3/serverInfo"
# Look for "deploymentType": "Cloud"

# Test with simple ADF
c qs "Test **bold** text" --dry-run
# Check the generated JSON structure

# Force plain text mode
# Set :use-adf false in config
```

### Phase 3 Issues (AI Integration)

#### Problem: AI gateway authentication fails
**Symptoms**: "401 Unauthorized" or "403 Forbidden"
**Solutions**:
```bash
# Test API key manually
curl -H "Authorization: Bearer $API_KEY" "$GATEWAY_URL/health"

# Check key format (some require "Bearer " prefix, others don't)
# Verify key hasn't expired
# Confirm IP address is whitelisted (if applicable)
```

#### Problem: AI gateway timeouts
**Symptoms**: "AI gateway timeout, using original content"
**Solutions**:
```bash
# Increase timeout in config
{:ai {:timeout-ms 10000}}  # 10 seconds instead of 5

# Test network latency
ping smg.company.com
curl -w "@curl-format.txt" "$GATEWAY_URL/health"

# Check for proxy/firewall issues
```

#### Problem: AI enhancement makes things worse
**Symptoms**: AI changes meaning or makes errors
**Solutions**:
- Use `--preview` to review changes before creating tickets
- Adjust the AI prompt in `enhance-content` function
- Use `--no-ai` for specific tickets
- Disable AI in config temporarily

### Cross-Platform Issues

#### Problem: Path separator issues (Windows)
**Symptoms**: File not found errors, temp files not created
**Solution**: Babashka handles this automatically, but check for hardcoded paths

#### Problem: Shell command differences
**Symptoms**: Commands work on Linux but not macOS
**Solution**: Use Babashka's cross-platform functions instead of shell commands

#### Problem: Editor behavior differs
**Symptoms**: Editor opens differently on different systems  
**Solutions**:
```bash
# Test specific editor behavior
EDITOR=nano c qs -e --dry-run
EDITOR=vim c qs -e --dry-run  
EDITOR=code c qs -e --dry-run

# Set editor with arguments if needed
export EDITOR="code --wait"  # VS Code
export EDITOR="nano -w"      # Nano with word wrap
```

### Performance Issues

#### Problem: Slow startup time
**Symptoms**: `c qs` takes >2 seconds to start
**Debug**:
```bash
# Profile startup time
time c qs --help

# Check what's loading slowly
bb -e "(time (load-file \"core/lib/config.clj\"))"
bb -e "(time (load-file \"core/lib/jira.clj\"))"
```

#### Problem: Memory usage grows
**Symptoms**: System slows down with repeated use
**Solutions**:
- Clear AI cache periodically: `(lib.ai/clear-cache!)`
- Check for resource leaks in temp file handling
- Monitor with system tools: `htop`, Activity Monitor

### Network and Security Issues

#### Problem: Corporate proxy blocking requests
**Symptoms**: Cannot reach Jira or AI gateway
**Solutions**:
```bash
# Configure proxy for HTTP client
export HTTP_PROXY=http://proxy.company.com:8080
export HTTPS_PROXY=http://proxy.company.com:8080

# Test with curl first
curl --proxy $HTTP_PROXY "$JIRA_URL/rest/api/3/myself"
```

#### Problem: SSL certificate issues
**Symptoms**: SSL verification errors
**Temporary workaround**: 
```bash
# NOT recommended for production
export NODE_TLS_REJECT_UNAUTHORIZED=0
```
**Proper solution**: Install company CA certificates

---

## File Structure Reference

After full implementation, your project structure will look like:

```
crucible/
├── core/
│   ├── bin/
│   │   └── crucible.clj          # Main CLI with editor + AI integration
│   ├── lib/
│   │   ├── ai.clj                # NEW: AI gateway client
│   │   ├── config.clj            # Updated: AI configuration options  
│   │   ├── jira.clj              # Updated: ADF support in create-issue
│   │   └── markdown.clj          # NEW: Markdown to ADF conversion
│   └── templates/
│       └── daily-log.md          # Existing template
├── docs/
│   ├── ai-integration-plan.md    # THIS DOCUMENT
│   ├── jira-guide.md             # Existing Jira setup guide
│   └── setup-guide.md            # Existing setup guide
├── scripts/
│   └── markdown-to-adf.js        # NEW: Node.js conversion script
├── workspace/
│   └── logs/                     # Existing log directory
├── node_modules/                 # NEW: Node dependencies
│   └── marklassian/              # Markdown to ADF converter
├── package.json                  # NEW: Node.js package config
├── package-lock.json             # NEW: Dependency lockfile
├── crucible.edn                  # Project config (optional)
├── bb.edn                        # Existing Babashka config
└── README.md                     # Existing project README
```

### Key Files Modified
- `core/bin/crucible.clj` - Added editor functions, AI integration, flag parsing
- `core/lib/config.clj` - Added AI configuration options
- `core/lib/jira.clj` - Added ADF support in issue creation

### Key Files Added
- `core/lib/ai.clj` - AI gateway client with caching and error handling
- `core/lib/markdown.clj` - Markdown to ADF conversion wrapper
- `scripts/markdown-to-adf.js` - Node.js script for actual conversion
- `docs/ai-integration-plan.md` - This implementation guide

---

## Next Steps

### For Implementation
1. **Start with Phase 1** - Get editor workflow working first
2. **Test each phase independently** - Don't move on until current phase works
3. **Set up Jira test instance** - Use for validation without affecting production
4. **Configure AI access** - Get SMG gateway credentials and test connectivity
5. **Install Node.js dependencies** - Set up marklassian on development and usage machines

### For Production Use  
1. **Gradual rollout** - Start with AI disabled, enable for power users first
2. **Monitor performance** - Watch API response times and error rates
3. **Collect feedback** - See what enhancements are most valuable
4. **Iterate on prompts** - Improve AI enhancement quality based on usage

### For Future Enhancement
1. **Batch operations** - Create multiple tickets from one editor session
2. **Template library** - Different templates for bugs, features, etc.
3. **Integration improvements** - Better Jira field mapping, custom fields
4. **AI capabilities** - Context-aware enhancements, project-specific terminology

This implementation guide provides everything needed to build the AI-enhanced Jira integration step by step, with comprehensive testing and fallback strategies to ensure reliability.