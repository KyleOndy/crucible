(ns lib.commands
  "Individual command implementations for Crucible CLI"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [lib.config :as config]
   [lib.daily-log :as daily-log]
   [lib.jira :as jira]
   [lib.process-detection :as process-detection])
  (:import
   (java.time LocalDateTime)
   (java.time.format DateTimeFormatter)))

(defn log-command
  [subcommand & args]
  (case subcommand
    "daily" (if (seq args)
              (daily-log/open-log-for-relative-date (first args))
              (daily-log/open-daily-log))
    (println (str "Unknown log subcommand: " subcommand))))

(defn pipe-command
  [& args]
  (let [stdin-content (slurp *in*)
        explicit-command (first args)
        detection-result (when (and (not explicit-command)
                                    (not (str/blank? stdin-content)))
                           (process-detection/get-parent-command))
        detected-command (:command detection-result)
        detection-method (:method detection-result)
        command-str (or explicit-command detected-command)]
    (if (str/blank? stdin-content)
      (println "No input received from stdin")
      (let [log-path (daily-log/get-daily-log-path)
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
        (daily-log/create-daily-log-from-template log-path)
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

(defn doctor-command
  "Comprehensive health check for system, configuration, and dependencies"
  []
  (let [config (config/load-config)
        jira-config (:jira config)
        ai-config (:ai config)
        workspace-config (:workspace config)
        config-status (config/get-config-file-status)
        env-status (config/get-env-var-status)
        workspace-check (config/check-workspace-directories workspace-config)]

    ;; Helper function to format status lines
    (letfn [(status-line [level category message]
              (let [padded-level (format "%-8s" level)]
                (if (config/terminal-supports-color?)
                  (case level
                    "OK" (println (config/green padded-level) (str category ": " message))
                    "WARNING" (println (config/yellow padded-level) (str category ": " message))
                    "ERROR" (println (config/red padded-level) (str category ": " message))
                    "INFO" (println (config/blue padded-level) (str category ": " message)))
                  (println padded-level category ":" message))))]

      ;; System Environment
      (let [java-version (System/getProperty "java.version")
            os-name (System/getProperty "os.name")
            os-version (System/getProperty "os.version")
            working-dir (System/getProperty "user.dir")
            bb-version (try
                         (let [result (process/shell {:out :string :err :string} "bb" "--version")]
                           (if (= 0 (:exit result))
                             (first (str/split (str/trim (:out result)) #"\n"))
                             "unknown"))
                         (catch Exception _ "unknown"))]
        (status-line "OK" "System" (str "Operating System " os-name " " os-version))
        (status-line "OK" "System" (str "Java Version " java-version))
        (status-line "OK" "System" (str "Babashka Version " bb-version))
        (status-line "OK" "System" (str "Working Directory " working-dir)))

      ;; Configuration Status
      (let [project-config (:project-config config-status)
            xdg-config (:xdg-config config-status)]
        (if (:exists project-config)
          (if (:readable project-config)
            (status-line "OK" "Config" (str "Project config " (:path project-config) " (loaded)"))
            (status-line "ERROR" "Config" (str "Project config " (:path project-config) " (not readable)")))
          (status-line "INFO" "Config" (str "Project config " (:path project-config) " (not found)")))

        (if (:exists xdg-config)
          (if (:readable xdg-config)
            (status-line "OK" "Config" (str "User config " (:path xdg-config) " (loaded)"))
            (status-line "ERROR" "Config" (str "User config " (:path xdg-config) " (not readable)")))
          (status-line "INFO" "Config" (str "User config " (:path xdg-config) " (not found)"))))

      ;; Environment Variables
      (let [env-vars ["CRUCIBLE_JIRA_URL" "CRUCIBLE_JIRA_USER" "CRUCIBLE_JIRA_TOKEN" "CRUCIBLE_WORKSPACE_DIR" "EDITOR"]
            set-vars (count (filter #(:set (get env-status %)) env-vars))
            total-vars (count env-vars)]
        (if (= set-vars total-vars)
          (status-line "OK" "Config" (str "Environment variables " set-vars "/" total-vars " set"))
          (status-line "WARNING" "Config" (str "Environment variables " set-vars "/" total-vars " set"))))

      ;; Password Manager
      (let [pass-available (try
                             (let [result (process/shell {:out :string :err :string} "which" "pass")]
                               (= 0 (:exit result)))
                             (catch Exception _ false))]
        (if pass-available
          (status-line "OK" "Config" "Password manager pass available")
          (status-line "WARNING" "Config" "Password manager pass not found")))

      ;; Workspace Directories  
      (status-line "OK" "Workspace" (str "Root " (:root-dir workspace-config)))
      (status-line "OK" "Workspace" (str "Logs " (:logs-dir workspace-config)))
      (status-line "OK" "Workspace" (str "Tickets " (:tickets-dir workspace-config)))
      (status-line "OK" "Workspace" (str "Docs " (:docs-dir workspace-config)))

      ;; Missing workspace directories
      (when (> (:missing-dirs workspace-check) 0)
        (doseq [missing (:missing-list workspace-check)]
          (status-line "WARNING" "Workspace" (str (:description missing) " " (:path missing) " (missing)"))))

      ;; Jira Connection
      (if (and (:base-url jira-config) (:username jira-config) (:api-token jira-config))
        (let [conn-result (jira/test-connection jira-config)]
          (if (:success conn-result)
            (do
              (status-line "OK" "Jira" (:message conn-result))
              (when-let [project (:default-project jira-config)]
                (status-line "OK" "Jira" (str "Default project " project " configured"))))
            (status-line "ERROR" "Jira" (:message conn-result))))
        (status-line "ERROR" "Jira" "Missing configuration (base-url, username, or api-token)"))

      ;; Sprint Information (NEW)
      (when (and (:base-url jira-config) (:username jira-config) (:api-token jira-config))
        (try
          (let [sprint-info (jira/get-current-sprint-info jira-config)]
            (if sprint-info
              (do
                (status-line "OK" "Sprint" (str "Active sprint: " (:name sprint-info) " (ID: " (:id sprint-info) ")"))
                (status-line "INFO" "Sprint" (str "Days remaining: " (:days-remaining sprint-info)))
                (status-line "INFO" "Sprint" (str "Assigned tickets: " (count (:assigned-tickets sprint-info))))
                (when (:debug jira-config)
                  (status-line "INFO" "Sprint" (str "JQL Query: " (:jql sprint-info))))

                ;; Show ticket status filter configuration
                (let [exclude-statuses (:sprint-exclude-statuses jira-config)
                      show-done (:sprint-show-done-tickets jira-config)]
                  (if show-done
                    (status-line "INFO" "Sprint" "Showing all tickets including Done")
                    (when exclude-statuses
                      (status-line "INFO" "Sprint" (str "Excluding statuses: " (str/join ", " exclude-statuses)))))))
              (status-line "WARNING" "Sprint" "No active sprint found")))
          (catch Exception e
            (status-line "WARNING" "Sprint" (str "Could not fetch sprint info: " (.getMessage e))))))

      ;; AI Configuration
      (if (:enabled ai-config)
        (if (and (:gateway-url ai-config) (:api-key ai-config))
          (status-line "OK" "AI" "Enabled and configured")
          (status-line "WARNING" "AI" "Enabled but missing gateway-url or api-key"))
        (status-line "INFO" "AI" "Disabled in configuration"))

      ;; Editor
      (let [editor (or (:editor config) (System/getenv "EDITOR"))]
        (if editor
          (let [editor-path (try
                              (let [result (process/shell {:out :string :err :string} "which" editor)]
                                (if (= 0 (:exit result))
                                  (str/trim (:out result))
                                  "not found"))
                              (catch Exception _ "not found"))]
            (if (not= editor-path "not found")
              (status-line "OK" "Editor" (str editor " (" editor-path ")"))
              (status-line "ERROR" "Editor" (str editor " (not found in PATH)"))))
          (status-line "WARNING" "Editor" "No editor configured (set EDITOR or :editor in config)"))))))