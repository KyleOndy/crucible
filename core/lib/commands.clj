(ns lib.commands
  "Individual command implementations for Crucible CLI"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [lib.ai :as ai]
            [lib.commands.doctor :as doctor]
            [lib.commands.logging :as logging]
            [lib.commands.pipe :as pipe]
            [lib.config :as config]
            [lib.daily-log :as daily-log]
            [lib.jira :as jira]
            [lib.process-detection :as process-detection])
  (:import (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(defn log-command
  [subcommand & args]
  (case subcommand
    "daily" (if (seq args)
              (daily-log/open-log-for-relative-date (first args))
              (daily-log/open-daily-log))
    (println (str "Unknown log subcommand: " subcommand))))

;; Re-exported from logging namespace
(def process-stdin-input logging/process-stdin-input)

;; Re-exported from logging namespace
(def detect-command-info logging/detect-command-info)

;; Re-exported from logging namespace
(def format-log-entry logging/format-log-entry)

;; Re-exported from logging namespace
(def insert-log-content logging/insert-log-content)

;; =============================================================================
;; Pipe Command Components
;; =============================================================================

;; =============================================================================
;; Pipe Command Components (Re-exported from logging namespace)
;; =============================================================================

;; Re-exported from logging namespace
(def process-pipe-input logging/process-pipe-input)

;; Re-export from pipe namespace for backward compatibility
(def pipe-command pipe/pipe-command)

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
          (do (println (str "Error: " (:error result))) (System/exit 1))
          (let [data (:data result)
                fields (:fields data)
                ;; Separate standard and custom fields
                standard-fields #{:summary :description :issuetype :status
                                  :priority :assignee :reporter :created
                                  :updated :project :components :labels
                                  :fixVersions :versions}
                custom-fields (filter #(str/starts-with? (name %) "customfield")
                                (keys fields))]
            (println (str "\n=== Ticket: " (:key data) " ==="))
            (println "\nStandard Fields:")
            (doseq [field-key standard-fields]
              (when-let [value (get fields field-key)]
                (let [display-value
                        (cond (map? value) (or (:displayName value)
                                               (:name value)
                                               (:value value)
                                               (str value))
                              (sequential? value)
                                (str/join ", "
                                          (map #(or (:name %) (str %)) value))
                              :else (str value))]
                  (when (and display-value (not= display-value ""))
                    (println (str "  " (name field-key) ": " display-value))))))
            (when (seq custom-fields)
              (println "\nCustom Fields:")
              (doseq [field-key (sort custom-fields)]
                (let [value (get fields field-key)
                      field-name (name field-key)]
                  (when value
                    (let [display-value
                            (cond (map? value) (or (:value value)
                                                   (:displayName value)
                                                   (str value))
                                  (sequential? value)
                                    (str/join ", "
                                              (map #(or (:value %) (str %))
                                                value))
                                  :else (str value))]
                      (println (str "  " field-name ": " display-value)))))))
            (println "\n=== Configuration Helper ===")
            (println
              "To use these custom fields in your config, add to crucible.edn:")
            (println "```clojure")
            (println ":jira {:custom-fields {")
            (when (seq custom-fields)
              (doseq [field-key (take 3 (sort custom-fields))]
                (let [value (get fields field-key)
                      sample-value (cond (map? value) (or (:value value)
                                                          "\"Value Here\"")
                                         (number? value) value
                                         :else "\"Value Here\"")]
                  (println (str "  :" (name field-key) " " sample-value)))))
            (println "}}")
            (println "```")))))))

;; =============================================================================
;; Doctor Command Components
;; =============================================================================

(defn- check-configuration-files
  "Check configuration file status and return status information.
   Returns a vector of maps with :level :category :message keys."
  [config-status]
  (when-not (map? config-status)
    (throw (ex-info "Config status must be a map"
                    {:type :invalid-input, :value config-status})))
  (let [{:keys [project-config xdg-config]} config-status
        make-status
          (fn [config config-type]
            (->
              {:category "Config"}
              (cond->
                (not (:exists config))
                  (assoc :level
                    :info :message
                    (str config-type " config " (:path config) " (not found)"))
                (not (:readable config)) (assoc :level
                                           :error :message
                                           (str config-type
                                                " config "
                                                (:path config)
                                                " (not readable)"))
                :else
                  (assoc :level
                    :ok :message
                    (str config-type " config " (:path config) " (loaded)")))))]
    [(make-status project-config "Project") (make-status xdg-config "User")]))

(defn- check-environment-variables
  "Check environment variables and return status information.
   Returns a vector of maps with :level :category :message keys."
  [env-status]
  (when-not (map? env-status)
    (throw (ex-info "Environment status must be a map"
                    {:type :invalid-input, :value env-status})))
  (let [env-vars ["CRUCIBLE_JIRA_URL" "CRUCIBLE_JIRA_USER" "CRUCIBLE_JIRA_TOKEN"
                  "CRUCIBLE_WORKSPACE_DIR" "EDITOR"]
        set-count (->> env-vars
                       (filter #(:set (get env-status %)))
                       count)
        total-count (count env-vars)]
    [{:level (if (= set-count total-count) :ok :warning),
      :category "Config",
      :message
        (str "Environment variables " set-count "/" total-count " set")}]))

(defn- check-password-manager
  "Check if password manager is available.
   Returns a vector of maps with :level :category :message keys."
  []
  (let [pass-available (try (let [result (process/shell {:out :string,
                                                         :err :string}
                                                        "which"
                                                        "pass")]
                              (= 0 (:exit result)))
                            (catch Exception _ false))]
    [{:level (if pass-available :ok :warning),
      :category "Config",
      :message (if pass-available
                 "Password manager pass available"
                 "Password manager pass not found")}]))

(defn- check-workspace-directories
  "Check workspace directories and return status information.
   Returns a vector of maps with :level :category :message keys."
  [workspace-config workspace-check]
  (when-not (map? workspace-config)
    (throw (ex-info "Workspace config must be a map"
                    {:type :invalid-input, :value workspace-config})))
  (when-not (map? workspace-check)
    (throw (ex-info "Workspace check must be a map"
                    {:type :invalid-input, :value workspace-check})))
  (let [base-statuses
          (->> [[:root-dir "Root"] [:logs-dir "Logs"] [:tickets-dir "Tickets"]
                [:docs-dir "Docs"]]
               (mapv (fn [[dir-key label]]
                       {:level :ok,
                        :category "Workspace",
                        :message
                          (str label " " (get workspace-config dir-key))})))
        missing-statuses (->> (:missing-list workspace-check)
                              (mapv (fn [missing]
                                      {:level :warning,
                                       :category "Workspace",
                                       :message (str (:description missing)
                                                     " "
                                                     (:path missing)
                                                     " (missing)")})))]
    (concat base-statuses missing-statuses)))

(defn- check-jira-connection
  "Check Jira connection and return status information.
   Returns a vector of maps with :level :category :message keys."
  [jira-config]
  (if (and (:base-url jira-config)
           (:username jira-config)
           (:api-token jira-config))
    (let [conn-result (jira/test-connection jira-config)
          base-status {:level (if (:success conn-result) :ok :error),
                       :category "Jira",
                       :message (:message conn-result)}
          project-status (when (and (:success conn-result)
                                    (:default-project jira-config))
                           {:level :ok,
                            :category "Jira",
                            :message (str "Default project "
                                          (:default-project jira-config)
                                          " configured")})]
      (if project-status [base-status project-status] [base-status]))
    [{:level :error,
      :category "Jira",
      :message "Missing configuration (base-url, username, or api-token)"}]))

(defn- check-sprint-information
  "Check current sprint information and return status information.
   Returns a vector of maps with :level :category :message keys."
  [jira-config]
  (when (and (:base-url jira-config)
             (:username jira-config)
             (:api-token jira-config))
    (try
      (if-let [sprint-info (jira/get-current-sprint-info jira-config)]
        (let [base-statuses [{:level :ok,
                              :category "Sprint",
                              :message (str "Active sprint: "
                                            (:name sprint-info)
                                            " (ID: "
                                            (:id sprint-info)
                                            ")")}
                             {:level :info,
                              :category "Sprint",
                              :message (str "Days remaining: "
                                            (:days-remaining sprint-info))}
                             {:level :info,
                              :category "Sprint",
                              :message (str "Assigned tickets: "
                                            (count (:assigned-tickets
                                                     sprint-info)))}]
              debug-status (when (:debug jira-config)
                             {:level :info,
                              :category "Sprint",
                              :message (str "JQL Query: " (:jql sprint-info))})
              filter-status
                (let [exclude-statuses (:sprint-exclude-statuses jira-config)
                      show-done (:sprint-show-done-tickets jira-config)]
                  (cond show-done {:level :info,
                                   :category "Sprint",
                                   :message
                                     "Showing all tickets including Done"}
                        exclude-statuses
                          {:level :info,
                           :category "Sprint",
                           :message (str "Excluding statuses: "
                                         (str/join ", " exclude-statuses))}))]
          (filter some? (concat base-statuses [debug-status filter-status])))
        [{:level :warning,
          :category "Sprint",
          :message "No active sprint found"}])
      (catch Exception e
        [{:level :warning,
          :category "Sprint",
          :message (str "Could not fetch sprint info: " (.getMessage e))}]))))

(defn- check-ai-configuration
  "Check AI configuration and connectivity.
   Returns a vector of maps with :level :category :message keys."
  [ai-config]
  (if (:enabled ai-config)
    (if (and (:gateway-url ai-config) (:api-key ai-config))
      (let [start-time (System/currentTimeMillis)
            test-result (ai/call-ai-model "What is a crucible?" ai-config)
            duration (- (System/currentTimeMillis) start-time)
            model (:model ai-config "unknown")]
        (if (:success test-result)
          [{:level :ok,
            :category "AI",
            :message
              (str "Authentication successful (" model ", " duration "ms)")}]
          (case (:error test-result)
            :unauthorized [{:level :error,
                            :category "AI",
                            :message "Authentication failed - check API key"}]
            :timeout
              [{:level :error, :category "AI", :message "Gateway timeout"}]
            :rate-limited [{:level :warning,
                            :category "AI",
                            :message "Rate limited but accessible"}]
            [{:level :error,
              :category "AI",
              :message (:message test-result)}])))
      [{:level :warning,
        :category "AI",
        :message "Enabled but missing gateway-url or api-key"}])
    [{:level :info, :category "AI", :message "Disabled in configuration"}]))

(defn- check-editor-configuration
  "Check editor configuration and availability.
   Returns a vector of maps with :level :category :message keys."
  [config]
  (if-let [editor (or (:editor config) (System/getenv "EDITOR"))]
    (let [editor-path
            (try (let [result (process/shell {:out :string, :err :string}
                                             "which"
                                             editor)]
                   (if (= 0 (:exit result)) (str/trim (:out result)) nil))
                 (catch Exception _ nil))]
      (if editor-path
        [{:level :ok,
          :category "Editor",
          :message (str editor " (" editor-path ")")}]
        [{:level :error,
          :category "Editor",
          :message (str editor " (not found in PATH)")}]))
    [{:level :warning,
      :category "Editor",
      :message "No editor configured (set EDITOR or :editor in config)"}]))

;; Re-export from doctor namespace for backward compatibility
(def doctor-command doctor/doctor-command)

(defn init-prompt-command
  "Initialize AI prompt by copying default template to user workspace"
  [args]
  (let [config (config/load-config)
        workspace-config (:workspace config)
        ;; Use the root-dir directly from workspace config
        root-dir (config/expand-path (or (:root-dir workspace-config) "."))
        ;; Default prompts directory to "prompts" if not specified
        prompts-dir-name (or (:prompts-dir workspace-config) "prompts")
        prompts-dir (fs/path root-dir prompts-dir-name)
        target-file (fs/path prompts-dir "jira-enhancement.txt")
        source-file (fs/path "core" "prompts" "jira-enhancement.txt")]
    (try
      ;; Check if source template exists
      (when-not (fs/exists? source-file)
        (println
          (config/format-error
            "Default prompt template not found. This might be a development build issue."))
        (System/exit 1))
      ;; Ensure prompts directory exists
      (when-not (fs/exists? prompts-dir) (fs/create-dirs prompts-dir))
      ;; Check if target file already exists
      (when (fs/exists? target-file)
        (print (str "AI prompt file already exists at "
                    target-file
                    ". Overwrite? (y/N): "))
        (flush)
        (let [response (read-line)]
          (when-not (= "y" (str/lower-case (str/trim (or response ""))))
            (println "Operation cancelled.")
            (System/exit 0))))
      ;; Copy the template file
      (fs/copy source-file target-file {:replace-existing true})
      ;; Success message with configuration instructions
      (println (config/format-success
                 "AI prompt template initialized successfully!"))
      (println)
      (println "Template copied to:")
      (println (str "  " target-file))
      (println)
      (println "To use this prompt, add the following to your configuration:")
      (println (config/format-info (str "  :ai {:prompt-file \""
                                        prompts-dir-name
                                        "/jira-enhancement.txt\"}")))
      (println)
      (println
        "You can now edit the prompt file to customize AI enhancement behavior.")
      (catch Exception e
        (println (config/format-error (str "Failed to initialize AI prompt: "
                                           (.getMessage e))))
        (System/exit 1)))))
