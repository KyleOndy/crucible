#!/usr/bin/env bb

;; Load library files
(load-file "core/lib/config.clj")
(load-file "core/lib/jira.clj")


(ns crucible
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.string :as str]
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
       "  log daily         Open today's daily log\n"
       "  pipe [command]    Pipe stdin to daily log (optionally log the command)\n"
       "  quick-story <summary>  Create a quick Jira story\n"
       "  qs <summary>      Alias for quick-story\n\n"
       "Quick Setup:\n"
       "  1. Run: ./setup.sh\n"
       "  2. See docs/setup-guide.md for detailed instructions\n\n"
       "Use 'bb <command>' for convenience aliases (from crucible directory):\n"
       "  bb l              Alias for 'c log daily'\n"
       "  bb pipe           Alias for 'c pipe'\n"
       "  bb qs <summary>   Alias for 'c quick-story'\n\n"
       "Quick Story Examples:\n"
       "  c qs \"Fix login timeout issue\"\n"
       "  c quick-story \"Add rate limiting to API\"\n\n"
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


(defn quick-story-command
  "Create a quick Jira story with minimal input"
  [summary]
  (if-not summary
    (do
      (println "Error: story summary required")
      (println "Usage: crucible quick-story \"Your story summary\"")
      (println "   or: crucible qs \"Your story summary\"")
      (System/exit 1))
    (let [config (config/load-config)
          jira-config (:jira config)]

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
                                 :summary summary
                                 :issuetype {:name (:default-issue-type jira-config)}
                                 :description ""}}

            ;; Add assignee if auto-assign is enabled and we have user info
            issue-data (if (and user-info (:accountId user-info))
                         (assoc-in issue-data [:fields :assignee]
                                   {:accountId (:accountId user-info)})
                         issue-data)]

        ;; Create the issue
        (println "Creating story...")
        (let [result (jira/create-issue jira-config issue-data)]
          (if (:success result)
            (let [issue-key (:key result)
                  ;; Try to add to sprint if configured
                  sprint-added? (when (:auto-add-to-sprint jira-config)
                                  (when-let [board (jira/get-board-for-project
                                                     jira-config
                                                     (:default-project jira-config))]
                                    (when-let [sprint (jira/get-current-sprint
                                                        jira-config
                                                        (:id board))]
                                      (jira/add-issue-to-sprint
                                        jira-config
                                        (:id sprint)
                                        issue-key))))]
              (println (str "\n✓ Created " issue-key ": " summary))
              (println (str "  Status: To Do"))
              (when sprint-added?
                (println "  Added to current sprint"))
              (when user-info
                (println (str "  Assigned to: " (:displayName user-info))))
              (println (str "\nStart working: c work-on " issue-key)))
            (do
              (println (str "Error: " (:error result)))
              (System/exit 1))))))))


(defn dispatch-command
  [command args]
  (case command
    "help" (println (help-text))
    "log" (log-command (first args))
    "pipe" (apply pipe-command args)
    ("quick-story" "qs") (quick-story-command (first args))
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
