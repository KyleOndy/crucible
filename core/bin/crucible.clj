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
  (str "Crucible - SRE productivity system\n\n"
       "Commands:\n"
       "  help              Show this help\n"
       "  check             Health check\n"
       "  inspect-ticket <id> View ticket fields\n"
       "  jira-check [ticket] Check Jira config\n"
       "  l                 Open daily log\n"
       "  pipe [command]    Pipe stdin to log\n"
       "  qs <summary>      Create Jira story\n\n"
       "Quick Story Options:\n"
       "  -e, --editor      Open editor\n"
       "  -f, --file <file> From file\n"
       "  --dry-run         Preview only\n"
       "  --ai              Enable AI\n"
       "  --no-ai           Disable AI\n"
       "  --ai-only         AI test only\n"
       "  --list-drafts     Show drafts\n"
       "  --recover <file>  Recover draft\n"
       "  --clean-drafts    Clean old drafts\n"))

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
  "Launch editor with the given file path. Returns the editor's exit code."
  [file-path]
  (let [editor (System/getenv "EDITOR")]
    (if editor
      (let [result @(process/process [editor (str file-path)] {:inherit true})]
        (:exit result))
      (do
        (println "Error: EDITOR environment variable not set")
        (System/exit 1)))))

(defn ensure-draft-directory
  "Ensure draft directory exists and return path"
  []
  (let [draft-dir (str (fs/cwd) "/temp/ticket-drafts")]
    (fs/create-dirs draft-dir)
    draft-dir))

(defn save-draft-copy
  "Save copy of ticket content to draft directory, return draft path"
  [content]
  (let [draft-dir (ensure-draft-directory)
        now (LocalDateTime/now)
        formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmm-ss")
        draft-filename (str "draft-" (.format now formatter) ".md")
        draft-path (str draft-dir "/" draft-filename)]
    (spit draft-path content)
    draft-path))

(defn cleanup-draft
  "Remove draft file if it exists"
  [draft-path]
  (when (and draft-path (fs/exists? draft-path))
    (fs/delete draft-path)))

(defn get-available-drafts
  "List available draft files with metadata"
  []
  (let [draft-dir (ensure-draft-directory)]
    (if (fs/exists? draft-dir)
      (->> (fs/list-dir draft-dir)
           (filter #(str/ends-with? (str %) ".md"))
           (map (fn [path]
                  (let [filename (fs/file-name path)
                        size (fs/size path)
                        modified (fs/last-modified-time path)]
                    {:path (str path)
                     :filename filename
                     :size size
                     :modified modified})))
           (sort-by :modified)
           reverse)
      [])))

(defn clean-old-drafts
  "Remove draft files older than specified days (default 7)"
  [& {:keys [days] :or {days 7}}]
  (let [draft-dir (ensure-draft-directory)
        cutoff-time (-> (LocalDateTime/now)
                        (.minusDays days)
                        (.toInstant (java.time.ZoneOffset/UTC)))]
    (when (fs/exists? draft-dir)
      (doseq [file (fs/list-dir draft-dir)]
        (when (str/ends-with? (str file) ".md")
          (let [modified-time (fs/last-modified-time file)]
            (when (.isBefore (.toInstant modified-time) cutoff-time)
              (fs/delete file))))))))

(defn load-draft-content
  "Load content from draft file"
  [draft-path]
  (when (fs/exists? draft-path)
    (slurp draft-path)))

(defn create-ticket-template
  "Create template for editor"
  ([]
   (create-ticket-template nil nil))
  ([title]
   (create-ticket-template title nil))
  ([title description]
   (str (if title (str title "\n") "")
        "\n"
        "# Enter ticket title on first line" (when title " (or edit above)")
        "\n"
        (if description
          (str description "\n\n")
          "")
        "# Enter description " (if description "above (or edit above)" "below") " (markdown supported)\n"
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

(defn parse-draft-content
  "Parse content from draft file into title and description"
  [content]
  (when content
    (parse-editor-content content)))

(defn open-ticket-editor
  "Open editor for ticket creation, return parsed content with draft path.
   Returns nil if editor exits with non-zero code."
  ([]
   (open-ticket-editor nil nil))
  ([title]
   (open-ticket-editor title nil))
  ([title description]
   (let [temp-file (fs/create-temp-file {:prefix "crucible-ticket-"
                                         :suffix ".md"})
         template (create-ticket-template title description)]
     (try
       (spit (str temp-file) template)
       (let [exit-code (launch-editor temp-file)]
         (if (= 0 exit-code)
           (let [content (slurp (str temp-file))
                 parsed (parse-editor-content content)]
             (fs/delete temp-file)
             (if parsed
               ;; Save draft copy for recovery and add draft path to result
               (let [draft-path (save-draft-copy content)]
                 (assoc parsed :draft-path draft-path))
               ;; No valid content, don't save draft
               nil))
           ;; Non-zero exit code, user cancelled
           (do
             (fs/delete temp-file)
             nil)))
       (catch Exception e
         (when (fs/exists? temp-file)
           (fs/delete temp-file))
         (throw e))))))

(defn review-enhanced-ticket
  "Open editor to review AI-enhanced ticket content.
   Returns the parsed content if user approves (exit 0), nil if cancelled."
  [{:keys [title description]}]
  (let [temp-file (fs/create-temp-file {:prefix "crucible-review-"
                                        :suffix ".md"})
        content (str "# AI-Enhanced Ticket Review\n\n"
                     "# The ticket below has been enhanced by AI.\n"
                     "# Review and make any final edits.\n"
                     "# Save and exit (exit code 0) to create the ticket.\n"
                     "# Exit without saving (exit code non-0) to cancel.\n\n"
                     "---\n\n"
                     title "\n\n"
                     (if (str/blank? description)
                       "<!-- No description -->"
                       description))]
    (try
      (spit (str temp-file) content)
      (let [exit-code (launch-editor temp-file)]
        (if (= 0 exit-code)
          (let [edited-content (slurp (str temp-file))
                parsed (parse-editor-content edited-content)]
            (fs/delete temp-file)
            parsed)
          ;; Non-zero exit code, user cancelled
          (do
            (fs/delete temp-file)
            nil)))
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
            (System/exit 1))

          (or (= arg "-d") (= arg "--desc"))
          (if (seq rest-args)
            (do
              (swap! flags assoc :desc (first rest-args))
              (reset! arg-iter (rest rest-args)))
            (System/exit 1))

          (= arg "--list-drafts")
          (do
            (swap! flags assoc :list-drafts true)
            (reset! arg-iter rest-args))

          (= arg "--clean-drafts")
          (do
            (swap! flags assoc :clean-drafts true)
            (reset! arg-iter rest-args))

          (= arg "--recover")
          (if (seq rest-args)
            (do
              (swap! flags assoc :recover (first rest-args))
              (reset! arg-iter (rest rest-args)))
            (System/exit 1))

          (str/starts-with? arg "-")
          (do

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
        {:keys [editor dry-run file ai no-ai ai-only desc list-drafts clean-drafts recover]} flags]

    ;; Handle draft management commands first
    (cond
      ;; List available drafts
      list-drafts
      (let [drafts (get-available-drafts)]
        (if (seq drafts)
          (do
            (doseq [draft drafts] (println (:filename draft))))
          (println "No ticket drafts found")))

      ;; Clean old drafts
      clean-drafts
      (clean-old-drafts)

      ;; Recover from draft - create ticket using draft content
      recover
      (let [draft-dir (ensure-draft-directory)
            draft-path (if (str/starts-with? recover "/")
                         recover
                         (str draft-dir "/" recover))
            content (load-draft-content draft-path)]
        (if content
          (let [parsed (parse-draft-content content)]
            (if parsed
              (do
                (println (str "Recovering draft from: " (fs/file-name draft-path)))
                ;; Use recovered data as initial-data and continue normally
                ;; but mark it so we don't save another draft
                (let [recovered-data (assoc parsed :recovered-from-draft true)]
                  (quick-story-command (concat [(:title recovered-data)]
                                               (when (not (str/blank? (:description recovered-data))) ["-d" (:description recovered-data)])))))
              (System/exit 1)))
          (System/exit 1)))

      ;; Normal ticket creation logic
      :else
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
                             (System/exit 1))

                           ;; Editor input
                           editor
                           (open-ticket-editor summary desc)

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
              (println "   or: crucible qs --list-drafts  (show available drafts)")
              (println "   or: crucible qs --recover <filename>  (recover from draft)")
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

              ;; For editor mode with AI, open review editor
              final-data (if (and editor ai-enabled (not= initial-data enhanced-data))
                           (let [reviewed (review-enhanced-ticket enhanced-data)]
                             (if reviewed
                               reviewed
                               (do
                                 (println "Review cancelled - ticket creation aborted")
                                 (System/exit 0))))
                           enhanced-data)]

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
            (when-not (:base-url jira-config) (System/exit 1))

            (when-not (:default-project jira-config) (System/exit 1))

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
                               issue-data)

                  ;; Add default fix version if configured
                  default-fix-version-id (:default-fix-version-id jira-config)
                  issue-data (if default-fix-version-id
                               (assoc-in issue-data [:fields :fixVersions]
                                         [{:id default-fix-version-id}])
                               issue-data)

                  ;; Add custom fields from configuration
                  custom-fields (:custom-fields jira-config {})

                  ;; Add default story points if configured and not already in custom fields
                  default-story-points (:default-story-points jira-config)
                  custom-fields-with-story-points
                  (if (and default-story-points
                           (not (some #(str/includes? (str %) "story") (keys custom-fields))))
                    ;; Story points field is commonly customfield_10002, but this should be configurable
                    ;; For now, add it to custom-fields if story-points-field is configured
                    (if-let [story-points-field (:story-points-field jira-config)]
                      (assoc custom-fields story-points-field default-story-points)
                      custom-fields)
                    custom-fields)

                  issue-data (if (seq custom-fields-with-story-points)
                               (update issue-data :fields merge custom-fields-with-story-points)
                               issue-data)]

              ;; Create the issue
              (println (cond
                         file "Creating story from file..."
                         ai-enabled "Creating AI-enhanced story..."
                         :else "Creating story..."))
              (let [result (jira/create-issue jira-config issue-data)
                    draft-path (:draft-path final-data)]
                (if (:success result)
                  (do
                    ;; Success - clean up draft file and show results
                    (cleanup-draft draft-path)
                    (let [issue-key (:key result)
                          sprint-added? (when sprint-info
                                          (jira/add-issue-to-sprint
                                           jira-config
                                           (:id (:sprint sprint-info))
                                           issue-key))]
                      (println (str "Created " issue-key))))
                  (do
                    ;; Failure - preserve draft and show recovery info
                    (println (str "Error: " (:error result)))
                    (System/exit 1)))))))))))

(defn inspect-ticket-command
  "Inspect a Jira ticket to see all its fields including custom fields"
  [args]
  (let [ticket-id (first args)]
    (when-not ticket-id
      (println "Error: ticket ID required")
      (println "Usage: crucible inspect-ticket TICKET-123")
      (System/exit 1))

    (let [config (config/load-config)
          jira-config (:jira config)]

      ;; Validate configuration
      (when-not (:base-url jira-config)
        (println "Error: Jira configuration missing")
        (println "Please set CRUCIBLE_JIRA_URL or configure in crucible.edn")
        (System/exit 1))

      (println (str "Fetching ticket: " ticket-id "..."))
      (let [result (jira/get-ticket-full jira-config ticket-id)]
        (if-not (:success result)
          (do
            (println (str "Error: " (:error result)))
            (System/exit 1))
          (let [data (:data result)
                fields (:fields data)
                ;; Separate standard and custom fields
                standard-fields #{:summary :description :issuetype :status :priority
                                  :assignee :reporter :created :updated :project
                                  :components :labels :fixVersions :versions}
                custom-fields (filter #(str/starts-with? (name %) "customfield") (keys fields))]

            (println (str "\n=== Ticket: " (:key data) " ==="))
            (println "\nStandard Fields:")
            (doseq [field-key standard-fields]
              (when-let [value (get fields field-key)]
                (let [display-value (cond
                                      (map? value) (or (:displayName value)
                                                       (:name value)
                                                       (:value value)
                                                       (str value))
                                      (sequential? value) (str/join ", " (map #(or (:name %) (str %)) value))
                                      :else (str value))]
                  (when (and display-value (not= display-value ""))
                    (println (str "  " (name field-key) ": " display-value))))))

            (when (seq custom-fields)
              (println "\nCustom Fields:")
              (doseq [field-key (sort custom-fields)]
                (let [value (get fields field-key)
                      field-name (name field-key)]
                  (when value
                    (let [display-value (cond
                                          (map? value) (or (:value value)
                                                           (:displayName value)
                                                           (str value))
                                          (sequential? value) (str/join ", " (map #(or (:value %) (str %)) value))
                                          :else (str value))]
                      (println (str "  " field-name ": " display-value)))))))

            (println "\n=== Configuration Helper ===")
            (println "To use these custom fields in your config, add to crucible.edn:")
            (println "```clojure")
            (println ":jira {:custom-fields {")
            (when (seq custom-fields)
              (doseq [field-key (take 3 (sort custom-fields))]
                (let [value (get fields field-key)
                      sample-value (cond
                                     (map? value) (or (:value value) "\"Value Here\"")
                                     (number? value) value
                                     :else "\"Value Here\"")]
                  (println (str "  :" (name field-key) " " sample-value)))))
            (println "}}")
            (println "```")))))))

(defn health-check-command
  "Health check for system, AI, and Jira"
  []
  (let [config (config/load-config)
        jira-config (:jira config)
        ai-config (:ai config)]
    ;; System
    (println "System:")
    (println (str "  Config: " (if config "OK" "ERROR")))
    (println (str "  Jira URL: " (if (:base-url jira-config) "OK" "MISSING")))
    (println (str "  Jira Project: " (or (:default-project jira-config) "MISSING")))

    ;; AI
    (println "AI:")
    (let [ai-enabled (:enabled ai-config)
          has-url (:gateway-url ai-config)
          has-key (:api-key ai-config)]
      (println (str "  Enabled: " (if ai-enabled "YES" "NO")))
      (when ai-enabled
        (println (str "  Config: " (if (and has-url has-key) "OK" "MISSING")))))

    ;; Jira test
    (println "Jira:")
    (if (and (:base-url jira-config) (:username jira-config) (:api-token jira-config))
      (let [result (jira/test-connection jira-config)]
        (println (str "  Connection: " (if (:success result) "OK" "FAILED"))))
      (println "  Connection: MISSING CONFIG"))))

(defn inspect-ticket-command
  "Show key ticket fields"
  [args]
  (let [ticket-id (first args)]
    (if-not ticket-id
      (println "Error: ticket ID required")
      (let [config (config/load-config)
            jira-config (:jira config)
            result (jira/get-ticket-full jira-config ticket-id)]
        (if-not (:success result)
          (println (str "Error: " (:error result)))
          (let [data (:data result)
                fields (:fields data)]
            (println (str "Ticket: " (:key data)))
            (println (str "Summary: " (:summary fields)))
            (println (str "Status: " (get-in fields [:status :name])))
            (println (str "Assignee: " (get-in fields [:assignee :displayName])))
            (println (str "Project: " (get-in fields [:project :key])))))))))

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
    "inspect-ticket" (inspect-ticket-command args)
    ("check" "health-check") (health-check-command)
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
