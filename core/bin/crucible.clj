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
       "  ai-check          Check AI configuration and test connectivity\n"
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
  ([]
   (create-ticket-template nil))
  ([title]
   (str (if title (str title "\n") "")
        "\n"
        "# Enter ticket title on first line" (when title " (or edit above)")
        "\n"
        "# Enter description below (markdown supported)\n"
        "# Lines starting with # are comments (ignored)\n"
        "# Save and exit to create ticket, exit without saving to cancel\n")))

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
  ([]
   (open-ticket-editor nil))
  ([title]
   (let [temp-file (fs/create-temp-file {:prefix "crucible-ticket-"
                                         :suffix ".md"})
         template (create-ticket-template title)]
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
         (throw e))))))

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

          (or (= arg "-d") (= arg "--desc"))
          (if (seq rest-args)
            (do
              (swap! flags assoc :desc (first rest-args))
              (reset! arg-iter (rest rest-args)))
            (do
              (println "Error: -d/--desc requires a description argument")
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
        {:keys [editor dry-run file ai no-ai ai-only desc]} flags]

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
                         (open-ticket-editor summary)

                         ;; Command line input
                         summary
                         {:title summary :description (or desc "")}

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
            (println "   or: crucible qs \"Your story summary\" -d \"Description here\"")
            (println "   or: crucible qs -e  (open editor)")
            (println "   or: crucible qs -f filename.md  (from file)")
            (println "   or: crucible qs --ai-only \"test content\"  (AI enhancement only)")
            (System/exit 1))))

      ;; Load config for AI settings and sprint detection
      (let [config (config/load-config)
            jira-config (:jira config)
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
          (let [{:keys [title description]} final-data
                content-changed? (not= initial-data enhanced-data)]
            (if content-changed?
              (println "AI enhanced the content:")
              (println "AI returned unchanged content:"))
            (println)
            (println (str "Title: " title))
            (println (str "Description: " (if (str/blank? description) "(empty)" description)))
            (when content-changed?
              (println "\n(See diff above for changes)")))
          (println "====================")
          (System/exit 0))

        ;; Run sprint detection for both dry-run and normal modes
        (let [sprint-info (when (and (:auto-add-to-sprint jira-config)
                                     (:base-url jira-config)
                                     (:default-project jira-config))
                            (let [debug? (:sprint-debug jira-config false)
                                  project-key (:default-project jira-config)
                                  fallback-boards (:fallback-board-ids jira-config)
                                  sprint-pattern (:sprint-name-pattern jira-config)]

                              (when debug? (println "--- SPRINT DETECTION DEBUG ---"))
                              (when debug? (println (str "  Project: " project-key)))
                              (when debug? (println (str "  Fallback boards: " fallback-boards)))
                              (when debug? (println (str "  Name pattern: " sprint-pattern)))

                              (let [sprint-data (jira/find-sprints jira-config
                                                                   {:project-key project-key
                                                                    :debug debug?
                                                                    :fallback-board-ids fallback-boards
                                                                    :sprint-name-pattern sprint-pattern})]
                                ;; DEBUG: Add detailed logging of what enhanced-sprint-detection actually returned
                                (when debug?
                                  (println "--- FIND-SPRINTS RETURN VALUE DEBUG ---")
                                  (println (str "  Received sprint-data: " sprint-data))
                                  (println (str "  sprint-data type: " (type sprint-data)))
                                  (println (str "  sprint-data nil?: " (nil? sprint-data))))

                                ;; DEBUG: Add detailed logging of sprint-data structure
                                (when debug?
                                  (println "--- SPRINT DATA STRUCTURE DEBUG ---")
                                  (println (str "  sprint-data: " sprint-data))
                                  (when sprint-data
                                    (println (str "  :sprints key: " (:sprints sprint-data)))
                                    (println (str "  :sprints count: " (count (:sprints sprint-data))))
                                    (println (str "  :board-count: " (:board-count sprint-data)))
                                    (println (str "  :detection-method: " (:detection-method sprint-data)))))

                                ;; Process sprint data and return result (FIX: ensure this is the return value)
                                (let [sprint-result
                                      (when sprint-data
                                        (let [sprints (:sprints sprint-data)
                                              board-count (:board-count sprint-data)
                                              method (:detection-method sprint-data)]
                                          ;; DEBUG: Add more logging right before the cond
                                          (when debug?
                                            (println "--- SPRINT PROCESSING LOGIC DEBUG ---")
                                            (println (str "  sprints variable: " sprints))
                                            (println (str "  sprints count: " (count sprints)))
                                            (println (str "  method variable: " method)))

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
                                              nil))))]

                                  ;; DEBUG: Log the final sprint result that will be returned
                                  (when debug?
                                    (println "--- FINAL SPRINT RESULT DEBUG ---")
                                    (println (str "  sprint-result: " sprint-result))
                                    (println (str "  sprint-result nil?: " (nil? sprint-result))))

                                  ;; Show troubleshooting if no sprint data
                                  (when (and (not sprint-data) debug?)
                                    (println "--- TROUBLESHOOTING ---")
                                    (println "  Sprint detection completely failed. Try:")
                                    (println "  1. c jira-check - verify basic connectivity")
                                    (println "  2. Set :sprint-debug true in config for detailed logging")
                                    (println "  3. Manually find board IDs and set :fallback-board-ids [123 456]")
                                    (println "  4. Set :auto-add-to-sprint false to disable sprint detection"))

                                  ;; Return the sprint result (this is the key fix!)
                                  sprint-result))))
              {:keys [title description]} final-data]

          ;; DEBUG: Log what sprint-info actually contains
          (let [debug? (:sprint-debug jira-config false)]
            (when debug?
              (println "--- SPRINT-INFO FINAL ASSIGNMENT DEBUG ---")
              (println (str "  Final sprint-info: " sprint-info))
              (println (str "  sprint-info nil?: " (nil? sprint-info)))))

          ;; Handle dry-run mode (now includes sprint detection results)
          (when dry-run
            (println "=== DRY RUN ===")
            (println (str "Title: " title))
            (println (str "Description:\n" description))
            (when file
              (println (str "Source file: " file)))
            (when sprint-info
              (let [sprint (:sprint sprint-info)]
                (println (str "Sprint: Would be added to \"" (:name sprint) "\" (ID: " (:id sprint) ")"))))
            (when (and (:auto-add-to-sprint jira-config) (not sprint-info))
              (println "Sprint: No active sprint found (would not be added to sprint)"))
            (System/exit 0))

          ;; Proceed with normal ticket creation
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
                      sprint-added? (when sprint-info
                                      (jira/add-issue-to-sprint
                                       jira-config
                                       (:id (:sprint sprint-info))
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
                  (System/exit 1))))))))))

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
                             :xdg-config "User config")
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
  ;; Workspace Status
  ;; Workspace Status
  (println "Workspace Status:")
  (try
    (let [config (config/load-config)
          workspace-config (:workspace config)
          dir-check (config/check-workspace-directories workspace-config)]
      (println (str "  Configured root directory: " (:root-dir workspace-config) " "
                    (if (fs/exists? (:root-dir workspace-config)) "[EXISTS]" "[NOT FOUND]")))
      (println (str "  Configured logs directory: " (:logs-dir workspace-config) " "
                    (if (fs/exists? (:logs-dir workspace-config)) "[EXISTS]" "[NOT FOUND]")))
      (println (str "  Configured tickets directory: " (:tickets-dir workspace-config) " "
                    (if (fs/exists? (:tickets-dir workspace-config)) "[EXISTS]" "[NOT FOUND]")))
      (println (str "  Configured docs directory: " (:docs-dir workspace-config) " "
                    (if (fs/exists? (:docs-dir workspace-config)) "[EXISTS]" "[NOT FOUND]")))

      ;; Offer to create missing directories
      (when (> (:missing-dirs dir-check) 0)
        (println)
        (println (str "  [INFO] " (:missing-dirs dir-check) " of " (:total-dirs dir-check) " workspace directories are missing"))
        (println "  Missing directories:")
        (doseq [{:keys [description path]} (:missing-list dir-check)]
          (println (str "    - " description ": " path)))
        (println)
        (print "  Create missing directories? [y/N]: ")
        (flush)
        (let [response (read-line)]
          (when (and response (or (= (str/lower-case response) "y")
                                  (= (str/lower-case response) "yes")))
            (println "  Creating directories...")
            (let [creation-results (config/ensure-workspace-directories workspace-config)]
              (doseq [[dir-key result] creation-results]
                (cond
                  (:created result)
                  (println (str "    [CREATED] " (:description result) ": " (:path result)))

                  (and (not (:created result)) (:exists result))
                  nil ; Already existed, don't print anything

                  :else
                  (println (str "    [ERROR] Failed to create " (:path result)
                                (when (:error result) (str ": " (:error result)))))))
              (println "  Directory creation complete."))))))
    (catch Exception e
      (println "  [ERROR] Could not load workspace configuration")
      (println (str "  Error: " (.getMessage e)))))
  (println)

  ;; Configuration Summary
  ;; Configuration Summary
  (println "Configuration Summary:")
  (try
    (let [config (config/load-config)
          jira-config (:jira config)
          workspace-config (:workspace config)]
      (println "  Configuration loaded successfully")
      (println (str "  Default project: " (or (:default-project jira-config) "[NOT SET]")))
      (println (str "  Auto-add to sprint: " (:auto-add-to-sprint jira-config)))
      (println (str "  Sprint debug: " (:sprint-debug jira-config)))
      (when (:fallback-board-ids jira-config)
        (println (str "  Fallback board IDs: " (:fallback-board-ids jira-config))))
      (println "  Workspace configuration:")
      (println (str "    Root dir: " (:root-dir workspace-config)))
      (println (str "    Logs dir: " (:logs-dir workspace-config)))
      (println (str "    Tickets dir: " (:tickets-dir workspace-config)))
      (println (str "    Docs dir: " (:docs-dir workspace-config))))
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

(defn ai-check-command
  "Check AI configuration and test connectivity"
  []
  (println "AI Configuration Check")
  (println "=====================")
  (println)

  ;; Load configuration
  (let [config (config/load-config)
        ai-config (:ai config)
        enabled? (:enabled ai-config false)
        gateway-url (:gateway-url ai-config)
        api-key (:api-key ai-config)
        model (:model ai-config "gpt-4")
        max-tokens (:max-tokens ai-config 1024)
        timeout-ms (:timeout-ms ai-config 5000)
        prompt (:prompt ai-config)
        template (:message-template ai-config)
        debug? (:debug ai-config false)]

    ;; Display configuration status
    (println "Configuration Status:")
    (println (str "  Enabled: " (if enabled?
                                  (config/green "[YES]")
                                  (config/yellow "[NO]"))))
    (println (str "  Debug Mode: " (if debug?
                                     (config/green "[ON]")
                                     (config/yellow "[OFF]"))))
    (println (str "  Gateway URL: " (if gateway-url
                                      (str gateway-url " " (config/green "[SET]"))
                                      (config/red "[NOT SET]"))))
    (println (str "  API Key: " (if api-key
                                  (str "****" (subs api-key (max 0 (- (count api-key) 4))) " " (config/green "[SET]"))
                                  (config/red "[NOT SET]"))))
    (println (str "  Model: " model))
    (println (str "  Max Tokens: " max-tokens))
    (println (str "  Timeout: " timeout-ms "ms"))
    (println)

    ;; Debug mode tip
    (when-not debug?
      (println (config/yellow "Tip: Enable debug mode to see detailed request/response information"))
      (println (config/yellow "     Add :debug true to :ai section in config"))
      (println))

    ;; Show prompt if configured
    (when prompt
      (println "Custom Prompt:")
      (println (str "  " (subs prompt 0 (min 80 (count prompt)))
                    (when (> (count prompt) 80) "...")))
      (println))

    ;; Check if AI can be used
    (if (and gateway-url api-key)
      (do
        (println "Gateway Test:")
        (print "  Testing connectivity... ")
        (flush)
        (let [test-result (ai/test-gateway ai-config)]
          (if (:success test-result)
            (println (config/green "SUCCESS"))
            (do
              (println (config/red (str "FAILED - " (:message test-result))))
              (when (= 400 (:status test-result))
                (println)
                (println (config/yellow "  Note: 400 error indicates the gateway rejected the request"))
                (println (config/yellow "        Common causes: invalid endpoint, authentication, or format"))
                (println (config/yellow "        Enable debug mode for detailed diagnostics")))))
          (println))

        ;; Test enhancement
        (println "Enhancement Test:")
        (println "  Testing with sample content...")
        (let [sample {:title "fix login bug"
                      :description "the login doesnt work when user enters wrong password"}
              enhanced (ai/enhance-content sample ai-config)]
          (if (and (not= (:title sample) (:title enhanced))
                   (not= (:description sample) (:description enhanced)))
            (do
              (println (config/green "  AI enhancement working!"))
              (println)
              (println "  Original:")
              (println (str "    Title: " (:title sample)))
              (println (str "    Description: " (:description sample)))
              (println)
              (println "  Enhanced:")
              (println (str "    Title: " (:title enhanced)))
              (println (str "    Description: " (:description enhanced))))
            (do
              (println (config/yellow "  AI enhancement returned unchanged content"))
              (println "  This may indicate an issue with the gateway or configuration")
              (when-not debug?
                (println "  Enable debug mode to see the actual API request and response")))))
        (println)

        ;; Overall status
        (println "Overall Status:")
        (if (:success (ai/test-gateway ai-config))
          (do
            (println (config/green "  ✓ AI is properly configured and ready to use"))
            (println)
            (println "Usage:")
            (println "  Enable for all tickets:  Set :ai :enabled true in config")
            (println "  One-time use:           c qs \"summary\" --ai")
            (println "  Test without creating:   c qs \"summary\" --ai-only"))
          (do
            (println (config/red "  ✗ AI configuration has issues - see errors above"))
            (when-not debug?
              (println)
              (println "To troubleshoot:")
              (println "  1. Enable debug mode: Add :debug true to :ai section")
              (println "  2. Run 'c ai-check' again to see detailed diagnostics")))))

      ;; Configuration missing
      (do
        (println (config/red "Missing Configuration:"))
        (when-not gateway-url
          (println "  - Gateway URL is required")
          (println "    Set :ai :gateway-url \"https://your-gateway.com/api\" in config"))
        (when-not api-key
          (println "  - API key is required")
          (println "    Set :ai :api-key \"your-key\" in config"))
        (println)
        (println "Example configuration in ~/.config/crucible/config.edn:")
        (println "{:ai {:enabled true")
        (println "      :gateway-url \"https://api.example.com/v1/enhance\"")
        (println "      :api-key \"sk-...\"")
        (println "      :model \"gpt-4\"")
        (println "      :max-tokens 1024")
        (println "      :debug true}}")))))

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
    "ai-check" (ai-check-command)
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
