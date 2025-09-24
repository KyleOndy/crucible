(ns lib.commands.doctor
  "System health check and diagnostic commands for Crucible CLI"
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [lib.ai :as ai]
            [lib.config :as config]
            [lib.jira :as jira]))

;; Status level configuration

(def ^:private status-levels
  "Status level configuration for doctor command output."
  {:ok {:text "OK", :color config/green},
   :warning {:text "WARNING", :color config/yellow},
   :error {:text "ERROR", :color config/red},
   :info {:text "INFO", :color config/blue}})

(defn- format-status-line
  "Format a status line with appropriate coloring based on terminal support.
   Returns a formatted string ready for printing."
  [level category message]
  (let [{:keys [text color]} (get status-levels level)
        padded-level (format "%-8s" text)]
    (if (config/terminal-supports-color?)
      (str (color padded-level) " " category ": " message)
      (str padded-level " " category ": " message))))

;; Individual health check functions

(defn- check-system-environment
  "Check system environment and return status information.
   Returns a vector of maps with :level :category :message keys."
  []
  (let [java-version (System/getProperty "java.version")
        os-name (System/getProperty "os.name")
        os-version (System/getProperty "os.version")
        working-dir (System/getProperty "user.dir")
        bb-version (try (let [result (process/shell {:out :string, :err :string}
                                                    "bb"
                                                    "--version")]
                          (if (= 0 (:exit result))
                            (first (str/split (str/trim (:out result)) #"\n"))
                            "unknown"))
                        (catch Exception _ "unknown"))]
    [{:level :ok,
      :category "System",
      :message (str "Operating System " os-name " " os-version)}
     {:level :ok,
      :category "System",
      :message (str "Java Version " java-version)}
     {:level :ok,
      :category "System",
      :message (str "Babashka Version " bb-version)}
     {:level :ok,
      :category "System",
      :message (str "Working Directory " working-dir)}]))

(defn- check-configuration-files
  "Check configuration file status and return status information.
   Returns a vector of maps with :level :category :message keys."
  [config-status]
  (let [{:keys [project-config xdg-config]} config-status
        project-status (cond (not (:exists project-config))
                               {:level :info,
                                :message (str "Project config "
                                              (:path project-config)
                                              " (not found)")}
                             (not (:readable project-config))
                               {:level :error,
                                :message (str "Project config "
                                              (:path project-config)
                                              " (not readable)")}
                             :else {:level :ok,
                                    :message (str "Project config "
                                                  (:path project-config)
                                                  " (loaded)")})
        xdg-status
          (cond (not (:exists xdg-config)) {:level :info,
                                            :message (str "User config "
                                                          (:path xdg-config)
                                                          " (not found)")}
                (not (:readable xdg-config)) {:level :error,
                                              :message (str "User config "
                                                            (:path xdg-config)
                                                            " (not readable)")}
                :else {:level :ok,
                       :message
                         (str "User config " (:path xdg-config) " (loaded)")})]
    [(assoc project-status :category "Config")
     (assoc xdg-status :category "Config")]))

(defn- check-required-configuration
  "Check that required configuration values are present in final loaded config.
   Returns a vector of maps with :level :category :message keys."
  [config]
  (let [missing-values
          (cond-> []
            (not (get-in config [:jira :base-url])) (conj "jira.base-url")
            (not (get-in config [:jira :username])) (conj "jira.username")
            (not (get-in config [:jira :api-token])) (conj "jira.api-token")
            (not (get-in config [:workspace :root-dir])) (conj
                                                           "workspace.root-dir")
            (not (:editor config)) (conj "editor"))
        message (if (empty? missing-values)
                  "All required configuration present"
                  (str "Missing required config: "
                       (str/join ", " missing-values)
                       " (add to config file or set env vars)"))
        level (if (empty? missing-values) :ok :error)]
    [{:level level, :category "Config", :message message}]))

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
  (let [base-statuses [{:level :ok,
                        :category "Workspace",
                        :message (str "Root " (:root-dir workspace-config))}
                       {:level :ok,
                        :category "Workspace",
                        :message (str "Logs " (:logs-dir workspace-config))}
                       {:level :ok,
                        :category "Workspace",
                        :message (str "Tickets "
                                      (:tickets-dir workspace-config))}
                       {:level :ok,
                        :category "Workspace",
                        :message (str "Docs " (:docs-dir workspace-config))}]
        missing-statuses (map (fn [missing]
                                {:level :warning,
                                 :category "Workspace",
                                 :message (str (:description missing)
                                               " "
                                               (:path missing)
                                               " (missing)")})
                           (:missing-list workspace-check))]
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

;; Main doctor command function

(defn doctor-command
  "Comprehensive health check for system, configuration, and dependencies.
   Performs all checks and outputs formatted status lines."
  []
  (let [config (config/load-config)
        jira-config (:jira config)
        ai-config (:ai config)
        workspace-config (:workspace config)
        config-status (config/get-config-file-status)
        workspace-check (config/check-workspace-directories workspace-config)
        ;; Collect all status information functionally
        all-statuses (concat (check-system-environment)
                             (check-configuration-files config-status)
                             (check-required-configuration config)
                             (check-password-manager)
                             (check-workspace-directories workspace-config
                                                          workspace-check)
                             (check-jira-connection jira-config)
                             (check-sprint-information jira-config)
                             (check-ai-configuration ai-config)
                             (check-editor-configuration config))]
    ;; Print all status lines
    (doseq [{:keys [level category message]} all-statuses]
      (println (format-status-line level category message)))))