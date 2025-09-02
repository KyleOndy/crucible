#!/usr/bin/env bb

;; Load library files
(load-file "core/lib/ai.clj")
(load-file "core/lib/config.clj")
(load-file "core/lib/jira.clj")

(ns crucible
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [lib.ai :as ai]
   [lib.config :as config]
   [lib.jira :as jira])
  (:import
   (java.time
    LocalDate
    LocalDateTime)
   (java.time.format
    DateTimeFormatter)
   (java.util
    Locale)))

(def cli-spec
  {:help {:desc "Show help"
          :alias :h}})

(defn help-text
  []
  (str "Crucible - AI-powered SRE productivity system\n\n"
       "Usage: c <command> [options]\n\n"
       "Commands:\n"
       "  help              Show this help\n"
       "  check             System configuration and status check\n"
       "  jira-check [ticket]  Check Jira configuration and connectivity\n"
       "  l                 Open today's daily log (alias for 'log daily')\n"
       "  log daily         Open today's daily log\n"
       "  pipe [command]    Pipe stdin to daily log (optionally log the command)\n"
       "  quick-story <summary>  Create a quick Jira story\n"
       "  qs <summary>      Alias for quick-story\n\n"
       "Quick Story Options:\n"
       "  -e, --editor      Open editor for ticket creation (git commit style)\n"
       "  -f, --file <file> Create ticket from markdown file\n"
       "  --dry-run         Preview ticket without creating\n"
       "  --ai              Enable AI enhancement (overrides config)\n"
       "  --no-ai           Disable AI enhancement (overrides config)\n"
       "  --ai-only         Test AI enhancement without creating ticket\n\n"
       "Quick Setup:\n"
       "  1. Run: ./setup.sh\n"
       "  2. See docs/setup-guide.md for detailed instructions\n"
       "  3. System check: c check\n"
       "  4. Test Jira integration: c jira-check\n\n"
       "Use 'bb <command>' for convenience aliases (from crucible directory):\n"
       "  bb jira-check     Alias for 'c jira-check'\n"
       "  bb l              Alias for 'c log daily'\n"
       "  bb pipe           Alias for 'c pipe'\n"
       "  bb qs <summary>   Alias for 'c quick-story'\n\n"
       "Jira Examples:\n"
       "  c jira-check                      Test your Jira configuration\n"
       "  c jira-check PROJ-1234           Test with a specific ticket\n"
       "  c qs \"Fix login timeout issue\"    Create a quick story\n"
       "  c qs -e                           Open editor for ticket creation\n"
       "  c qs -f test-features.md          Create ticket from markdown file\n"
       "  c qs -e --dry-run                 Preview ticket from editor\n"
       "  c qs \"fix bug\" --ai               Create AI-enhanced story\n"
       "  c qs \"fix bug\" --ai-only          Test AI enhancement only\n"
       "  c qs -e --ai                      Editor + AI enhancement\n"
       "  c quick-story \"Add rate limiting\"  Create a story with full command\n\n"
       "Editor Mode:\n"
       "  When using -e/--editor, enter:\n"
       "  - First line: ticket title\n"
       "  - Remaining lines: description (markdown supported)\n"
       "  - Lines starting with # are ignored as comments\n"
       "  - Save and exit to create ticket, exit without saving to cancel\n\n"
       "AI Enhancement:\n"
       "  When enabled, sends title/description to AI gateway for:\n"
       "  - Grammar and spelling correction\n"
       "  - Clarity and professional tone\n"
       "  - Preserving original meaning and intent\n"
       "  Configure in crucible.edn: {:ai {:enabled true :gateway-url \"...\" :api-key \"...\"}}\n"
       "  Use --ai-only to test prompts without creating Jira tickets\n\n"
       "Markdown Support in Descriptions:\n"
       "  **Bold text** → Bold formatting in Jira\n"
       "  *Italic text* → Italic formatting in Jira\n"
       "  `inline code` → Code formatting in Jira\n"
       "  # Header → Large heading\n"
       "  ## Subtitle → Medium heading\n"
       "  - List item → Bullet point\n"
       "  [Link text](https://example.com) → Clickable link\n"
       "  https://example.com → Auto-linked URL\n"
       "  PROJ-123 → Auto-linked to Jira ticket\n"
       "  Double newlines create separate paragraphs\n\n"
       "Pipe Examples:\n"
       "  kubectl get pods | c pipe\n"
       "  ls -la | c pipe \"ls -la\"\n"
       "  kubectl get pods | c pipe \"kubectl get pods\"\n\n"
       "Tee-like Behavior (logs AND passes output through):\n"
       "  kubectl get pods | c pipe \"kubectl get pods\" | grep Running\n"
       "  ps aux | c pipe \"ps aux\" | head -5\n"
       "  cat file.txt | c pipe \"cat file.txt\" | sort | uniq\n\n"
       "For automatic command logging, add this function to your shell profile:\n"
       "  cpipe() { eval \"$*\" | c pipe \"$*\"; }\n"
       "Then use: cpipe kubectl get pods\n\n"
       "cpipe Setup Instructions:\n"
       "  Bash/Zsh: Add cpipe function to ~/.bashrc or ~/.zshrc\n"
       "  Fish: Add 'function cpipe; eval $argv | c pipe \"$argv\"; end' to ~/.config/fish/functions/cpipe.fish\n"
       "  Then restart your shell or run: source ~/.bashrc (or ~/.zshrc)\n\n"
       "Documentation:\n"
       "  docs/jira-guide.md    Complete Jira setup and usage guide\n"
       "  docs/setup-guide.md   System setup and configuration\n"))

(defn get-date-info
  "Returns map with formatted date information for template substitution"
  []
  (let [today (LocalDate/now)
        day-formatter (DateTimeFormatter/ofPattern "EEEE" Locale/ENGLISH)
        full-formatter (DateTimeFormatter/ofPattern "EEEE, MMMM d, yyyy" Locale/ENGLISH)]
    {:date (.toString today)
     :day-name (.format today day-formatter)
     :full-date (.format today full-formatter)}))

(defn process-template
  "Replace template variables with actual values"
  [template-content date-info]
  (-> template-content
      (str/replace "{{DATE}}" (:date date-info))
      (str/replace "{{DAY_NAME}}" (:day-name date-info))
      (str/replace "{{FULL_DATE}}" (:full-date date-info))))

(defn ensure-log-directory
  "Create logs/daily directory if it doesn't exist, using configured paths"
  [config]
  (let [log-dir (fs/path (get-in config [:workspace :logs-dir]) "daily")]
    (when-not (fs/exists? log-dir)
      (fs/create-dirs log-dir))
    (str log-dir)))

(defn get-daily-log-path
  "Get the path for today's daily log file, using configured paths"
  []
  (let [config (config/load-config)
        log-dir (ensure-log-directory config)
        date-info (get-date-info)
        filename (str (:date date-info) ".md")]
    (fs/path log-dir filename)))

(defn create-daily-log-from-template
  "Create daily log file from template if it doesn't exist"
  [log-path]
  (when-not (fs/exists? log-path)
    (let [template-path "core/templates/daily-log.md"
          date-info (get-date-info)]
      (if (fs/exists? template-path)
        (let [template-content (slurp template-path)
              processed-content (process-template template-content date-info)]
          (spit (str log-path) processed-content))
        ;; Fallback if template doesn't exist
        (spit (str log-path) (str "# " (:full-date date-info) " - Daily Log\n\n"))))))

(defn launch-editor
  "Launch editor with the given file path"
  [file-path]
  (let [editor (System/getenv "EDITOR")]
    (if editor
      (try
        @(process/process [editor (str file-path)] {:inherit true})
        (catch Exception e
          (println (str "Error launching editor: " (.getMessage e)))
          (System/exit 1)))
      (do
        (println "Error: $EDITOR environment variable not set")
        (println "Please set $EDITOR to your preferred text editor (e.g., export EDITOR=nano)")
        (System/exit 1)))))

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
      (spit (str temp-file) template)
      (launch-editor temp-file)
      (let [content (slurp (str temp-file))
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
        remaining-args (atom [])
        arg-iter (atom args)]
    (while (seq @arg-iter)
      (let [arg (first @arg-iter)
            rest-args (rest @arg-iter)]
        (cond
          (or (= arg "-e") (= arg "--editor"))
          (do
            (swap! flags assoc :editor true)
            (reset! arg-iter rest-args))

          (= arg "--dry-run")
          (do
            (swap! flags assoc :dry-run true)
            (reset! arg-iter rest-args))

          (= arg "--ai")
          (do
            (swap! flags assoc :ai true)
            (reset! arg-iter rest-args))

          (= arg "--no-ai")
          (do
            (swap! flags assoc :no-ai true)
            (reset! arg-iter rest-args))

          (= arg "--ai-only")
          (do
            (swap! flags assoc :ai-only true)
            (reset! arg-iter rest-args))

          (or (= arg "-f") (= arg "--file"))
          (if (seq rest-args)
            (do
              (swap! flags assoc :file (first rest-args))
              (reset! arg-iter (rest rest-args)))
            (do
              (println "Error: -f/--file requires a filename argument")
              (System/exit 1)))

          (str/starts-with? arg "-")
          (do
            (println (str "Warning: Unknown flag: " arg))
            (reset! arg-iter rest-args))

          :else
          (do
            (swap! remaining-args conj arg)
            (reset! arg-iter rest-args)))))
    {:args @remaining-args :flags @flags}))

(defn open-daily-log
  "Open today's daily log in the configured editor"
  []
  (let [log-path (get-daily-log-path)]
    (create-daily-log-from-template log-path)
    (launch-editor log-path)))

(defn log-command
  [subcommand]
  (case subcommand
    "daily" (open-daily-log)
    (println (str "Unknown log subcommand: " subcommand))))

(defn get-parent-command-java
  "Use Java ProcessHandle API to detect parent command (cross-platform)"
  []
  (try
    (let [current-handle (java.lang.ProcessHandle/current)
          parent-handle (.parent current-handle)]
      (when (.isPresent parent-handle)
        (let [parent (.get parent-handle)
              info (.info parent)
              command-line (.commandLine info)]
          (when (.isPresent command-line)
            (let [full-cmd (.get command-line)]
              ;; Extract just the command that's piping, not the full shell invocation
              (if (str/includes? full-cmd " | ")
                ;; If it's a pipeline, extract the part before the pipe
                (let [pipe-parts (str/split full-cmd #" \| ")
                      sending-command (str/trim (first pipe-parts))]
                  ;; Remove "bash -c" wrapper if present
                  ;; Remove shell wrapper if present and clean up
                  (let [cleaned-command (cond
                                          ;; Handle "bash -c command" format
                                          (str/starts-with? sending-command "bash -c ")
                                          (str/trim (str/replace sending-command "bash -c " ""))
                                          ;; Handle full path bash commands
                                          (re-find #"/bin/bash -c (.+)" sending-command)
                                          (str/trim (second (re-find #"/bin/bash -c (.+)" sending-command)))
                                          ;; Return as-is if no wrapper detected
                                          :else sending-command)]
                    cleaned-command))
                nil))))))
    (catch Exception _
      nil)))

(defn get-parent-command-ps
  "Try to detect the command that's piping data to us using ps (fallback method)"
  []
  (try
    (let [current-pid (.pid (java.lang.ProcessHandle/current))
          ppid (-> (process/shell {:out :string} (str "ps -o ppid= -p " current-pid))
                   :out
                   str/trim)
          parent-cmd (when (not (str/blank? ppid))
                       (-> (process/shell {:out :string} (str "ps -o args= -p " ppid))
                           :out
                           str/trim))]
      (when parent-cmd
        ;; Extract just the command that's piping, not the full shell invocation
        (if (str/includes? parent-cmd " | ")
          ;; If it's a pipeline, extract the part before the pipe
          (let [pipe-parts (str/split parent-cmd #" \| ")
                sending-command (str/trim (first pipe-parts))]
            ;; Remove "bash -c" wrapper if present
            ;; Remove shell wrapper if present and clean up
            (let [cleaned-command (cond
                                    ;; Handle "bash -c command" format
                                    (str/starts-with? sending-command "bash -c ")
                                    (str/trim (str/replace sending-command "bash -c " ""))
                                    ;; Handle full path bash commands
                                    (re-find #"/bin/bash -c (.+)" sending-command)
                                    (str/trim (second (re-find #"/bin/bash -c (.+)" sending-command)))
                                    ;; Return as-is if no wrapper detected
                                    :else sending-command)]
              cleaned-command))
          nil)))
    (catch Exception _
      nil)))

(defn get-parent-command
  "Unified parent command detection with fallback strategy"
  []
  (let [java-result (get-parent-command-java)
        ps-result (when-not java-result (get-parent-command-ps))]
    {:command (or java-result ps-result)
     :method (cond
               java-result :processhandle
               ps-result :ps
               :else :none)}))

(defn pipe-command
  [& args]
  (let [stdin-content (slurp *in*)
        explicit-command (first args)
        detection-result (when (and (not explicit-command)
                                    (not (str/blank? stdin-content)))
                           (get-parent-command))
        detected-command (:command detection-result)
        detection-method (:method detection-result)
        command-str (or explicit-command detected-command)]
    (if (str/blank? stdin-content)
      (println "No input received from stdin")
      (let [log-path (get-daily-log-path)
            timestamp (LocalDateTime/now)
            time-formatter (DateTimeFormatter/ofPattern "HH:mm:ss")
            formatted-time (.format timestamp time-formatter)
            working-dir (System/getProperty "user.dir")
            method-text (case detection-method
                          :processhandle "(auto-detected via ProcessHandle)\n"
                          :ps "(auto-detected via ps)\n"
                          :none ""
                          nil "")
            header-text (if command-str
                          (str "### Command output at " formatted-time "\n"
                               "Working directory: " working-dir "\n"
                               "Command: `" command-str "`\n"
                               (when detected-command method-text))
                          (str "### Command output at " formatted-time "\n"
                               "Working directory: " working-dir "\n"))
            content-to-append (str "\n" header-text "\n"
                                   "```bash\n"
                                   stdin-content
                                   (when-not (str/ends-with? stdin-content "\n") "\n")
                                   "```\n")]
        ;; Write to stdout (tee-like behavior)
        (print stdin-content)
        (flush)
        (create-daily-log-from-template log-path)
        ;; Read the current log content
        (let [current-content (slurp (str log-path))
              ;; Find the Commands & Outputs section and the next section
              sections-pattern #"(?m)^## Commands & Outputs.*\n(?:<!-- .*? -->\n)?((?:(?!^##).*\n)*)"
              next-section-pattern #"(?m)^## (?!Commands & Outputs)"
              matcher (re-matcher sections-pattern current-content)]
          (if (.find matcher)
            (let [section-end (.end matcher)
                  ;; Find where the next section starts (if any)
                  remaining-content (subs current-content section-end)
                  next-matcher (re-matcher next-section-pattern remaining-content)
                  insert-position (if (.find next-matcher)
                                    (+ section-end (.start next-matcher))
                                    (.length current-content))
                  before-insert (subs current-content 0 insert-position)
                  after-insert (subs current-content insert-position)
                  new-content (str before-insert content-to-append after-insert)]
              (spit (str log-path) new-content))
            ;; Fallback to appending at the end if section not found
            (spit (str log-path) content-to-append :append true)))
        ;; Enhanced logging with detection method
        (cond
          detected-command
          (let [method-name (case detection-method
                              :processhandle "ProcessHandle"
                              :ps "ps"
                              :none "unknown"
                              nil "unknown"
                              "unknown")]
            (println (str "✓ Output piped to " log-path " (detected via " method-name ": " detected-command ")")))
          :else
          (println (str "✓ Output piped to " log-path)))))))

(defn quick-story-command
  "Create a quick Jira story with minimal input, via editor, or from file"
  [args]
  (let [{:keys [args flags]} (parse-flags args)
        summary (first args)
        {:keys [editor dry-run file ai no-ai ai-only]} flags]

    ;; Get initial ticket data from file, editor, or command line
    (let [initial-data (cond
                         ;; File input
                         file
                         (if (fs/exists? file)
                           (let [content (slurp file)
                                 lines (str/split-lines content)
                                 title (first lines)
                                 description (str/join "\n" (rest lines))]
                             {:title title :description description})
                           (do
                             (println (str "Error: File not found: " file))
                             (System/exit 1)))

                         ;; Editor input
                         editor
                         (open-ticket-editor)

                         ;; Command line input
                         summary
                         {:title summary :description ""}

                         ;; No input provided
                         :else nil)]

      (when-not initial-data
        (cond
          editor
          (do
            (println "Editor cancelled or no content provided")
            (System/exit 0))

          file
          (do
            (println (str "Error reading file: " file))
            (System/exit 1))

          :else
          (do
            (println "Error: story summary required")
            (println "Usage: crucible quick-story \"Your story summary\"")
            (println "   or: crucible qs \"Your story summary\"")
            (println "   or: crucible qs -e  (open editor)")
            (println "   or: crucible qs -f filename.md  (from file)")
            (println "   or: crucible qs --ai-only \"test content\"  (AI enhancement only)")
            (System/exit 1))))

      ;; Load config for AI settings
      (let [config (config/load-config)
            ai-config (:ai config)
            ai-enabled (and (not no-ai)
                            (or ai ai-only (:enabled ai-config false))
                            (:gateway-url ai-config))

            ;; Apply AI enhancement if enabled
            enhanced-data (if ai-enabled
                            (do
                              (println "Enhancing content with AI...")
                              (ai/enhance-content initial-data ai-config))
                            initial-data)

            ;; Show AI changes if any
            _ (when (and ai-enabled (not= initial-data enhanced-data))
                (ai/show-diff initial-data enhanced-data))

            ;; Final data to use
            final-data enhanced-data]

        ;; Handle AI-only mode - exit without creating Jira ticket
        (when ai-only
          (println "\n=== AI-ONLY MODE ===")
          (println "Content enhanced, no Jira ticket created")
          (println "====================")
          (System/exit 0))

        (let [{:keys [title description]} final-data]

          ;; Handle dry-run mode
          (when dry-run
            (println "=== DRY RUN ===")
            (println (str "Title: " title))
            (println (str "Description:\n" description))
            (when file
              (println (str "Source file: " file)))
            (System/exit 0))

          ;; Proceed with normal ticket creation
          (let [jira-config (:jira config)]

            ;; Validate configuration
            (when-not (:base-url jira-config)
              (println "Error: Jira configuration missing")
              (println "Please set CRUCIBLE_JIRA_URL or configure in crucible.edn")
              (System/exit 1))

            (when-not (:default-project jira-config)
              (println "Error: No default project configured")
              (println "Please set :jira :default-project in your config file")
              (System/exit 1))

            ;; Get current user info if auto-assign is enabled
            (let [user-info (when (:auto-assign-self jira-config)
                              (jira/get-user-info jira-config))

                  ;; Build the issue data
                  issue-data {:fields {:project {:key (:default-project jira-config)}
                                       :summary title
                                       :issuetype {:name (:default-issue-type jira-config)}
                                       :description (jira/text->adf description)}}

                  ;; Add assignee if auto-assign is enabled and we have user info
                  issue-data (if (and user-info (:accountId user-info))
                               (assoc-in issue-data [:fields :assignee]
                                         {:accountId (:accountId user-info)})
                               issue-data)]

              ;; Create the issue
              (println (cond
                         file "Creating story from file..."
                         ai-enabled "Creating AI-enhanced story..."
                         :else "Creating story..."))
              (let [result (jira/create-issue jira-config issue-data)]
                (if (:success result)
                  (let [issue-key (:key result)
                        ;; Try to add to sprint if configured
                        ;; Try to add to sprint if configured - using project-wide sprint detection
                        ;; Try to add to sprint if configured - using enhanced detection with fallbacks
                        sprint-result (when (:auto-add-to-sprint jira-config)
                                        (let [debug? (:sprint-debug jira-config false)
                                              project-key (:default-project jira-config)
                                              fallback-boards (:fallback-board-ids jira-config)
                                              sprint-pattern (:sprint-name-pattern jira-config)]

                                          (when debug? (println "--- SPRINT DETECTION DEBUG ---"))
                                          (when debug? (println (str "  Project: " project-key)))
                                          (when debug? (println (str "  Fallback boards: " fallback-boards)))
                                          (when debug? (println (str "  Name pattern: " sprint-pattern)))

                                          (let [sprint-data (jira/enhanced-sprint-detection
                                                             jira-config
                                                             project-key
                                                             :debug debug?
                                                             :fallback-board-ids fallback-boards
                                                             :sprint-name-pattern sprint-pattern)]
                                            (when sprint-data
                                              (let [sprints (:sprints sprint-data)
                                                    board-count (:board-count sprint-data)
                                                    method (:detection-method sprint-data)]
                                                (cond
                                                  (= 1 (count sprints))
                                                  (do
                                                    (println (str "  Found 1 active sprint across " board-count " boards (" method ")"))
                                                    {:sprint (first sprints) :method method})

                                                  (> (count sprints) 1)
                                                  (do
                                                    (println (str "  Found " (count sprints) " active sprints, using: " (:name (first sprints)) " (" method ")"))
                                                    {:sprint (first sprints) :method method})

                                                  :else
                                                  (do
                                                    (println (str "  No active sprints found (" method ")"))
                                                    (when debug?
                                                      (println "  Debug suggestions:")
                                                      (println "     - Check if project key is correct")
                                                      (println "     - Verify user has access to project boards")
                                                      (println "     - Check if sprints are in 'active' state (not future/closed)")
                                                      (println "     - Consider setting :fallback-board-ids in config")
                                                      (println "     - Try: c jira-check to test basic connectivity"))
                                                    nil))))

                                            (when (and (not sprint-data) debug?)
                                              (println "--- TROUBLESHOOTING ---")
                                              (println "  Sprint detection completely failed. Try:")
                                              (println "  1. c jira-check - verify basic connectivity")
                                              (println "  2. Set :sprint-debug true in config for detailed logging")
                                              (println "  3. Manually find board IDs and set :fallback-board-ids [123 456]")
                                              (println "  4. Set :auto-add-to-sprint false to disable sprint detection")))))

                        sprint-added? (when sprint-result
                                        (jira/add-issue-to-sprint
                                         jira-config
                                         (:id (:sprint sprint-result))
                                         issue-key))]
                    (println (str "\nCreated " issue-key ": " title))
                    (println (str "  URL: " (str/replace (:base-url jira-config) #"/$" "") "/browse/" issue-key))
                    (when sprint-added?
                      (println "  Added to current sprint"))
                    (when user-info
                      (println (str "  Assigned to: " (:displayName user-info))))
                    (when file
                      (println (str "  Source: " file " (" (count (str/split-lines description)) " lines)")))
                    (when ai-enabled
                      (println "  Enhanced with AI"))
                    (println (str "\nStart working: c work-on " issue-key)))
                  (do
                    (println (str "Error: " (:error result)))
                    (System/exit 1)))))))))))

(defn system-check-command
  "Perform comprehensive system check and show configuration status"
  []
  (println "Crucible System Check")
  (println "====================")
  (println)

  ;; Configuration Files
  (println "Configuration Files:")
  (let [config-status (config/get-config-file-status)]
    (doseq [[config-type {:keys [path exists readable]}] config-status]
      (let [status-str (cond
                         (and exists readable) "[FOUND]"
                         exists "[FOUND - NOT READABLE]"
                         :else "[NOT FOUND]")]
        (println (str "  " (case config-type
                             :project-config "Project config"
                             :xdg-config "XDG config"
                             :legacy-config "Legacy config")
                      ": " path " " status-str)))))
  (println)

  ;; Environment Variables  
  (println "Environment Variables:")
  (let [env-status (config/get-env-var-status)]
    (doseq [[var-name {:keys [set value]}] env-status]
      (let [status-str (if set "[SET]" "[NOT SET]")
            display-value (when (and set value (not= value "*****"))
                            (str " = " value))]
        (println (str "  " var-name ": " status-str display-value)))))
  (println)

  ;; System Information
  (println "System Information:")
  (println (str "  Working directory: " (System/getProperty "user.dir")))
  (println (str "  User home: " (System/getProperty "user.home")))
  (println (str "  OS: " (System/getProperty "os.name")))
  (println (str "  Java version: " (System/getProperty "java.version")))
  (println)

  ;; Workspace Status
  (println "Workspace Status:")
  (let [workspace-dir (or (System/getenv "CRUCIBLE_WORKSPACE_DIR") "workspace")
        workspace-path (str (System/getProperty "user.dir") "/" workspace-dir)
        logs-dir (str workspace-path "/logs")
        tickets-dir (str workspace-path "/tickets")]
    (println (str "  Workspace directory: " workspace-path " "
                  (if (fs/exists? workspace-path) "[EXISTS]" "[NOT FOUND]")))
    (println (str "  Logs directory: " logs-dir " "
                  (if (fs/exists? logs-dir) "[EXISTS]" "[NOT FOUND]")))
    (println (str "  Tickets directory: " tickets-dir " "
                  (if (fs/exists? tickets-dir) "[EXISTS]" "[NOT FOUND]"))))
  (println)

  ;; Configuration Summary
  (println "Configuration Summary:")
  (try
    (let [config (config/load-config)
          jira-config (:jira config)]
      (println "  Configuration loaded successfully")
      (println (str "  Default project: " (or (:default-project jira-config) "[NOT SET]")))
      (println (str "  Auto-add to sprint: " (:auto-add-to-sprint jira-config)))
      (println (str "  Sprint debug: " (:sprint-debug jira-config)))
      (when (:fallback-board-ids jira-config)
        (println (str "  Fallback board IDs: " (:fallback-board-ids jira-config)))))
    (catch Exception e
      (println "  [ERROR] Failed to load configuration")
      (println (str "  Error: " (.getMessage e)))))
  (println)

  ;; Editor Check
  (println "Editor Check:")
  (let [editor (or (System/getenv "EDITOR") "not set")]
    (println (str "  EDITOR environment variable: " editor))
    (when (not= editor "not set")
      (try
        (let [result (process/shell {:out :string :err :string} "which" editor)]
          (if (= 0 (:exit result))
            (println (str "  Editor command available: " (str/trim (:out result))))
            (println "  [WARN] Editor command not found in PATH")))
        (catch Exception _
          (println "  [WARN] Could not check editor availability"))))))

(defn dispatch-command
  [command args]
  (case command
    "help" (println (help-text))
    ("log" "l") (if (or (= command "l") (nil? (first args)))
                  (open-daily-log)
                  (log-command (first args)))
    "pipe" (apply pipe-command args)
    ("quick-story" "qs") (quick-story-command args)
    "jira-check" (apply jira/run-jira-check args)
    ("check" "system-check") (system-check-command)
    (do
      (println (str "Unknown command: " command))
      (println)
      (println (help-text))
      (System/exit 1))))

(defn -main
  [& args]
  (let [command (first args)
        remaining-args (rest args)]
    (cond
      (or (= command "help") (= command "-h") (= command "--help")) (println (help-text))
      (or (empty? args) (nil? command)) (println (help-text))
      :else (dispatch-command command remaining-args))))

;; For bb execution
;; For bb execution
(apply -main *command-line-args*)
