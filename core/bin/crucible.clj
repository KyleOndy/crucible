#!/usr/bin/env bb

;; Load library files
(load-file "core/lib/config.clj")
(load-file "core/lib/jira.clj")
(load-file "core/lib/tickets.clj")
(load-file "core/lib/tmux.clj")


(ns crucible
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.string :as str]
    [lib.config :as config]
    [lib.jira :as jira]
    [lib.tickets :as tickets]
    [lib.tmux :as tmux])
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
       "Usage: bb crucible <command> [options]\n\n"
       "Commands:\n"
       "  help              Show this help\n"
       "  log daily         Open today's daily log\n"
       "  pipe [command]    Pipe stdin to daily log (optionally log the command)\n"
       "  work-on <ticket>  Start working on a ticket\n"
       "  sync-docs         Sync documentation from Confluence\n\n"
       "Development Commands:\n"
       "  nrepl             Start nREPL server for MCP integration\n"
       "  dev               Start development environment\n"
       "  clean             Clean REPL artifacts\n\n"
       "Use 'bb <command>' for convenience aliases:\n"
       "  bb l              Alias for 'bb crucible log daily'\n"
       "  bb pipe           Alias for 'bb crucible pipe'\n"
       "  bb work-on <id>   Alias for 'bb crucible work-on <id>'\n\n"
       "Pipe Examples:\n"
       "  kubectl get pods | bb pipe\n"
       "  ls -la | bb pipe \"ls -la\"\n"
       "  kubectl get pods | bb pipe \"kubectl get pods\"\n\n"
       "Tee-like Behavior (logs AND passes output through):\n"
       "  kubectl get pods | bb pipe \"kubectl get pods\" | grep Running\n"
       "  ps aux | bb pipe \"ps aux\" | head -5\n"
       "  cat file.txt | bb pipe \"cat file.txt\" | sort | uniq\n\n"
       "For automatic command logging, add this function to your shell profile:\n"
       "  cpipe() { eval \"$*\" | bb pipe \"$*\"; }\n"
       "Then use: cpipe kubectl get pods\n\n"
       "Setup Instructions:\n"
       "  Bash: Add to ~/.bashrc or ~/.bash_profile\n"
       "  Zsh:  Add to ~/.zshrc\n"
       "  Fish: Add 'function cpipe; eval $argv | bb pipe \"$argv\"; end' to ~/.config/fish/functions/cpipe.fish\n"
       "  Then restart your shell or run: source ~/.bashrc (or ~/.zshrc)\n"))


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
  "Create workspace/logs/daily directory if it doesn't exist"
  []
  (let [log-dir "workspace/logs/daily"]
    (when-not (fs/exists? log-dir)
      (fs/create-dirs log-dir))
    log-dir))


(defn get-daily-log-path
  "Get the path for today's daily log file"
  []
  (let [log-dir (ensure-log-directory)
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


(defn pipe-command
  [& args]
  (let [stdin-content (slurp *in*)
        command-str (first args)]
    (if (str/blank? stdin-content)
      (println "No input received from stdin")
      (let [log-path (get-daily-log-path)
            timestamp (LocalDateTime/now)
            time-formatter (DateTimeFormatter/ofPattern "HH:mm:ss")
            formatted-time (.format timestamp time-formatter)
            working-dir (System/getProperty "user.dir")
            header-text (if command-str
                          (str "### Command output at " formatted-time "\n"
                               "Working directory: " working-dir "\n"
                               "Command: `" command-str "`\n")
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
        (println (str "✓ Output piped to " log-path))))))


(defn work-on-command
  [ticket-id]
  (if-not ticket-id
    (do
      (println "Error: ticket ID required")
      (println "Usage: crucible work-on <ticket-id>")
      (System/exit 1))
    (let [config (config/load-config)]
      ;; Validate Jira configuration
      (let [validation-result (config/validate-jira-config config)]
        (when-not (:valid? validation-result)
          (println "Jira configuration error:")
          (doseq [error (:errors validation-result)]
            (println (str "  - " error)))
          (println "\nPlease check your configuration.")
          (System/exit 1)))

      ;; Test Jira connection
      (println "Connecting to Jira...")
      (let [conn-test (jira/test-connection (:jira config))]
        (when-not (:success conn-test)
          (println (str "Failed to connect to Jira: " (:message conn-test)))
          (System/exit 1)))

      ;; Fetch ticket and set up workspace
      (println (str "Fetching ticket " ticket-id "..."))
      (let [workspace-result (tickets/setup-ticket-workspace config ticket-id)]
        (if (:success workspace-result)
          (let [ticket-path (:path workspace-result)
                ticket-data (:ticket workspace-result)
                ticket-summary (jira/format-ticket-summary ticket-data)
                session-name (str (get-in config [:tmux :session-prefix]) ticket-id)]
            (println (str "\n✓ Ticket: " (:key ticket-summary) " - " (:summary ticket-summary)))
            (println (str "  Status: " (:status ticket-summary)))
            (println (str "  Assignee: " (:assignee ticket-summary)))
            (println (str "\n✓ Workspace created at: " ticket-path))

            ;; Switch to tmux session
            (if (tmux/inside-tmux?)
              (println "\nAlready inside tmux. Please manually switch to the ticket directory:")
              (do
                (println (str "\nStarting tmux session: " session-name))
                (let [tmux-result (tmux/switch-to-session session-name (str ticket-path))]
                  (when-not (:success tmux-result)
                    (println (str "Failed to start tmux session: " (:error tmux-result))))))))
          (do
            (println (str "Error: " (:error workspace-result)))
            (System/exit 1)))))))


(defn sync-docs-command
  []
  (println "Syncing documentation... (not implemented yet)"))


(defn dispatch-command
  [command args]
  (case command
    "help" (println (help-text))
    "log" (log-command (first args))
    "pipe" (apply pipe-command args)
    "work-on" (work-on-command (first args))
    "sync-docs" (sync-docs-command)
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
