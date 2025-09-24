(ns lib.config
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def default-ai-prompt
  "Default AI prompt when neither :prompt nor :prompt-file is configured"
  "TASK: Rewrite title and description for clarity and professionalism. OUTPUT: Exactly two lines: Title: [enhanced title] Description: [enhanced description]. FORBIDDEN: explanations, rationale, technical details, implementation notes, business impact, examples, or any other text.")

(def default-config
  {:jira {:base-url nil,
          :username nil,
          :api-token nil,
          :default-project nil,
          :default-issue-type "Task",
          :default-story-points 1,
          :story-points-field nil, ; Custom field ID for story points (e.g.
                                   ; "customfield_10002")
          :default-fix-version-id nil, ; Fix version ID to set for all
                                       ; tickets (e.g. "10100")
          :auto-assign-self true,
          :auto-add-to-sprint true,
          :debug false, ; Enable debug logging for Jira API calls and
                        ; sprint detection
          :fallback-board-ids nil,
          :sprint-name-pattern nil,
          :sprint-exclude-statuses ["Done"], ; Statuses to exclude from
                                             ; sprint tickets list
          :sprint-show-done-tickets false, ; Override to show all tickets
                                           ; including Done
          :custom-fields {}}, ; Map of custom field IDs to values
   ;; Unified sprint detection configuration
   :sprint {:enabled true,
            :debug false,
            :auto-add-to-ticket true,
            :fallback-board-ids [],
            :name-pattern nil,
            :detection-strategies [:project-wide :fallback-boards
                                   :pattern-matching],
            :timeout-ms 10000},
   :ai {:enabled false,
        :debug false, ; Enable debug logging for AI API calls
        :gateway-url nil,
        :api-key nil,
        :model "gpt-4",
        :max-tokens 1024,
        :timeout-ms 5000,
        :prompt nil, ; Default prompt (set via prompt-file or falls back to
                     ; built-in)
        :prompt-file nil, ; Path to external prompt file (alternative to
                          ; :prompt)
        ;; Message template for API requests - customize roles and content
        ;; as needed. Available variables: {prompt}, {title},
        ;; {description},
        ;; {title_and_description}
        :message-template [{:role "assistant", :content "{prompt}"}
                           {:role "user",
                            :content
                            "Title: {title}\nDescription: {description}"}]},
   :workspace {:root-dir "workspace",
               :logs-dir "logs",
               :tickets-dir "tickets",
               :docs-dir "docs",
               :prompts-dir "prompts"},
   :daily-workflow {:start-day {:enabled true,
                                :max-look-back-days 7, ; How far back to
                                                       ; look for last log
                                :gather-jira-context true, ; Pull recent Jira
                                                           ; activity
                                :carry-forward-tasks true, ; Include
                                                           ; uncompleted
                                                           ; tasks
                                :show-sprint-status true, ; Include current
                                                          ; sprint info
                                :jira-activity-days 5, ; How many days of
                                                       ; Jira history to
                                                       ; fetch
                                :jira-exclude-own-activity true, ; Hide your
                                                                 ; own changes
                                :jira-activity-types ["status" "assignee"
                                                      "priority" "resolution"], ; Activity
                                                                                ; types
                                                                                ; to
                                                                                ; show
                                :jira-max-activities 10}}, ; Maximum number
                                                           ; of activities to
                                                           ; display ; How
                                                           ; many days of
                                                           ; Jira history to
                                                           ; fetch
   :editor nil})

(defn expand-path
  "Expand ~ to user home directory"
  [path]
  (when path
    (when-not (string? path)
      (throw (ex-info "Path must be a string"
                      {:type :invalid-input :value path})))
    (if (str/starts-with? path "~")
      (str (System/getProperty "user.home") (subs path 1))
      path)))

(defn check-pass-command-availability
  "I/O function to check if pass command is available"
  []
  (try (let [result (process/shell {:out :string, :err :string} "which" "pass")]
         (if (= 0 (:exit result))
           {:success true, :result :available}
           {:error :pass-not-installed,
            :message "Pass command not found in PATH"}))
       (catch Exception e
         {:error :pass-check-failed,
          :message "Failed to check pass command availability",
          :context {:exception (.getMessage e)}})))

(defn execute-pass-command
  "I/O function to execute pass command and retrieve password"
  [pass-path]
  (try (let [result (process/shell {:out :string, :err :string, :continue true}
                                   "pass"
                                   "show"
                                   pass-path)]
         (if (= 0 (:exit result))
           {:success true, :result (str/trim (:out result))}
           {:error :pass-command-failed,
            :context {:exit-code (:exit result),
                      :stderr (:err result),
                      :pass-path pass-path}}))
       (catch Exception e
         {:error :pass-execution-failed,
          :message "Unexpected error executing pass command",
          :context {:pass-path pass-path, :exception (.getMessage e)}})))

(defn format-pass-error-message
  "Pure function to format detailed error messages for pass failures"
  [error-context]
  (let [{:keys [pass-path stderr]} error-context]
    (cond (str/includes? stderr "is not in the password store")
          (str "\n"
               (str "ERROR: Password entry not found: " pass-path)
               "\n\n"
               "The entry '" pass-path
               "' does not exist in your password store.\n\n"
               "To check available entries:\n"
               "  pass ls\n\n" "To add this entry:\n"
               "  pass insert " pass-path
               "\n\n" "Or update your config to use a different entry.")
          (str/includes? stderr "gpg: decryption failed")
          (str "\n"
               "ERROR: GPG decryption failed" "\n\n"
               "Could not decrypt the password entry.\n" "Possible causes:\n"
               "  • GPG key not available or expired\n"
               "  • GPG agent not running\n"
               "  • Wrong GPG key used\n\n" "Try:\n"
               "  gpg --list-secret-keys\n"
               "  gpgconf --kill gpg-agent && gpgconf --launch gpg-agent")
          (str/includes? stderr "not initialized")
          (str "\n" "ERROR: Password store not initialized"
               "\n\n" "Your password store has not been initialized.\n\n"
               "To initialize:\n" "  pass init <your-gpg-id>\n\n"
               "To find your GPG ID:\n" "  gpg --list-secret-keys")
          :else (str "\n"
                     (str "ERROR: Failed to retrieve password from pass: "
                          pass-path)
                     "\n\n"
                     "Pass command output:\n"
                     (when-not (str/blank? stderr)
                       (str "  " (str/replace stderr "\n" "\n  ") "\n\n"))
                     "To debug:\n"
                     "  pass show "
                     pass-path))))

(defn resolve-pass-value
  "Resolve a pass: prefixed value using decomposed I/O functions"
  [value]
  (if (and (string? value) (str/starts-with? value "pass:"))
    (let [pass-path (subs value 5)
          ;; I/O: Check if pass command is available
          availability-check (check-pass-command-availability)]
      (if (:success availability-check)
        ;; I/O: Execute pass command
        (let [pass-result (execute-pass-command pass-path)]
          (if (:success pass-result)
            (:result pass-result)
            ;; Handle pass execution errors
            (let [error-context (get pass-result :context {})
                  formatted-message
                  (case (:error pass-result)
                    :pass-command-failed (format-pass-error-message
                                          error-context)
                    :pass-execution-failed
                    (str "\n"
                         "ERROR: Unexpected error using pass"
                         "\n\n"
                         "Failed to retrieve: "
                         pass-path
                         "\n"
                         "Error: "
                         (get error-context :exception "Unknown error"))
                      ;; Default fallback
                    (str "Failed to retrieve password from: " pass-path))]
              (throw (ex-info formatted-message
                              (merge error-context {:pass-path pass-path}))))))
        ;; Handle availability check errors
        (let
         [error-message
          (case (:error availability-check)
            :pass-not-installed
            (str
             "\nERROR: 'pass' command not found\n\n"
             "The password manager 'pass' is not installed or not in PATH.\n"
             "Please install it first:\n"
             "  • macOS:  brew install pass\n"
             "  • Linux:  apt install pass  (or your distro's package manager)\n"
             "  • Then:   pass init <your-gpg-id>\n\n"
             "Learn more: https://www.passwordstore.org/")
               ;; Default fallback
            (str "Failed to check pass command availability: "
                 (get availability-check :message "Unknown error")))]
          (throw (ex-info error-message
                          {:pass-path pass-path,
                           :error-type (:error availability-check)})))))
    value))

(defn resolve-pass-references
  "Recursively resolve all pass: references in the config"
  [config]
  (cond (map? config)
        (into {} (map (fn [[k v]] [k (resolve-pass-references v)]) config))
        (string? config) (resolve-pass-value config)
        :else config))

(defn deep-merge
  "Deep merge two maps, with m2 values taking precedence"
  [m1 m2]
  (cond
    ;; If both are maps, recursively merge
    (and (map? m1) (map? m2)) (merge-with deep-merge m1 m2)
    ;; If m2 is not nil, it takes precedence
    (some? m2) m2
    ;; Otherwise use m1
    :else m1))

(defn read-file-content
  "I/O function to read file content with structured error handling"
  [path]
  (if (fs/exists? path)
    (try {:success true, :result (slurp path)}
         (catch Exception e
           {:error :file-read-failed,
            :message "Failed to read file",
            :context {:path path, :exception (.getMessage e)}}))
    {:success true, :result nil}))

(defn parse-edn-content
  "Pure function to parse EDN content"
  [content path]
  (when content
    (try {:success true, :result (edn/read-string content)}
         (catch Exception e
           {:error :edn-parse-failed,
            :message "Failed to parse EDN content",
            :context {:path path, :exception (.getMessage e)}}))))

(defn load-edn-file
  "Load and parse an EDN file using decomposed I/O functions, returning nil if it doesn't exist"
  [path]
  (let [;; I/O: Read file content
        read-result (read-file-content path)]
    (if (:success read-result)
      (if-let [content (:result read-result)]
        ;; Pure: Parse EDN content
        (let [parse-result (parse-edn-content content path)]
          (if (:success parse-result)
            (:result parse-result)
            ;; Handle parse errors
            (let [error-context (:context parse-result)]
              (throw (ex-info (str "Failed to parse config file: " path)
                              {:path path,
                               :error (:exception error-context)})))))
        ;; File doesn't exist, return nil
        nil)
      ;; Handle read errors
      (let [error-context (:context read-result)]
        (throw (ex-info (:message read-result) error-context))))))

(defn load-prompt-file
  "Load prompt text from external file using decomposed I/O functions"
  [prompt-file-path]
  (when prompt-file-path
    (let [expanded-path (expand-path prompt-file-path)
          ;; I/O: Read file content (reusing existing function)
          read-result (read-file-content expanded-path)]
      (if (:success read-result)
        (if-let [content (:result read-result)]
          content
          ;; File doesn't exist
          (throw (ex-info (str "Prompt file not found: " expanded-path)
                          {:path expanded-path})))
        ;; Handle read errors
        (let [error-context (:context read-result)]
          (throw (ex-info (str "Failed to read prompt file: " expanded-path)
                          {:path expanded-path,
                           :error (:exception error-context)})))))))

(defn load-home-config
  "Load config from user's home directory (XDG standard location)"
  []
  (let [xdg-config-home (or (System/getenv "XDG_CONFIG_HOME")
                            (str (System/getProperty "user.home") "/.config"))
        xdg-config-path (str (fs/path xdg-config-home "crucible" "config.edn"))]
    (load-edn-file xdg-config-path)))

(defn load-project-config
  "Load config from current project directory"
  []
  (load-edn-file "crucible.edn"))

(defn get-env-override
  "Get environment variable override for a config path"
  [env-map [path-key & path-rest]]
  (case path-key
    :jira (case (first path-rest)
            :base-url (get env-map "CRUCIBLE_JIRA_URL")
            :username (get env-map "CRUCIBLE_JIRA_USER")
            :api-token (get env-map "CRUCIBLE_JIRA_TOKEN")
            nil)
    :workspace (when (= (first path-rest) :root-dir)
                 (get env-map "CRUCIBLE_WORKSPACE_DIR"))
    :editor (get env-map "EDITOR")
    nil))

(defn apply-env-overrides
  "Apply environment variable overrides to config"
  [config]
  (let [env-map (into {} (System/getenv))]
    (-> config
        (update-in [:jira :base-url]
                   #(or (get-env-override env-map [:jira :base-url]) %))
        (update-in [:jira :username]
                   #(or (get-env-override env-map [:jira :username]) %))
        (update-in [:jira :api-token]
                   #(or (get-env-override env-map [:jira :api-token]) %))
        (update-in [:workspace :root-dir]
                   #(or (get-env-override env-map [:workspace :root-dir]) %))
        (update :editor #(or (get-env-override env-map [:editor]) %)))))

(defn expand-workspace-paths
  "Expand workspace paths to absolute paths"
  [config]
  (let [root-dir (expand-path (get-in config [:workspace :root-dir]))]
    (-> config
        (assoc-in [:workspace :root-dir] root-dir)
        (update-in [:workspace :logs-dir] #(str (fs/path root-dir %)))
        (update-in [:workspace :tickets-dir] #(str (fs/path root-dir %)))
        (update-in [:workspace :docs-dir] #(str (fs/path root-dir %))))))

(defn normalize-sprint-config
  "Normalize sprint configuration to use new unified format with backward compatibility.
   Migrates legacy :jira sprint settings to new :sprint section."
  [config]
  (let [jira-config (:jira config)
        sprint-config (:sprint config)
        ;; Extract legacy values from jira config
        legacy-auto-add (:auto-add-to-sprint jira-config)
        legacy-debug (:sprint-debug jira-config)
        legacy-fallback-boards (:fallback-board-ids jira-config)
        legacy-name-pattern (:sprint-name-pattern jira-config)
        ;; Create normalized sprint config (new format takes precedence)
        normalized-sprint
        (-> sprint-config
            ;; Use legacy values as defaults if new config doesn't specify them
            (update :enabled #(if (nil? %) (boolean legacy-auto-add) %))
            (update :debug #(if (nil? %) (boolean legacy-debug) %))
            (update :auto-add-to-ticket
                    #(if (nil? %) (boolean legacy-auto-add) %))
            (update :fallback-board-ids
                    #(if (empty? %) (or legacy-fallback-boards []) %))
            (update :name-pattern #(if (nil? %) legacy-name-pattern %)))]
    ;; Return config with normalized sprint section
    (assoc config :sprint normalized-sprint)))

(defn resolve-prompt-files
  "Resolve external prompt files in AI config"
  [config]
  (if-let [ai-config (:ai config)]
    (let [prompt-file (:prompt-file ai-config)
          prompt (:prompt ai-config)]
      (cond
        ;; Load from external file (prompt-file takes precedence)
        (and prompt-file (not (str/blank? prompt-file)))
        (try (let [loaded-prompt (load-prompt-file prompt-file)]
               (-> config
                   (assoc-in [:ai :prompt] loaded-prompt)
                   (assoc-in [:ai :prompt-file] prompt-file))) ; Keep file
                                                                 ; path for
                                                                 ; debugging
             (catch Exception e
               (throw (ex-info (str "Error loading prompt file: "
                                    (.getMessage e))
                               {:prompt-file prompt-file, :cause e}))))
        ;; Use configured prompt or default
        prompt config
        ;; Neither prompt nor prompt-file configured - use default
        :else (assoc-in config [:ai :prompt] default-ai-prompt)))
    config))

(defn load-config
  "Load configuration from all sources with proper precedence"
  []
  (-> default-config
      (deep-merge (load-home-config))
      (deep-merge (load-project-config))
      apply-env-overrides
      resolve-pass-references
      expand-workspace-paths
      normalize-sprint-config
      resolve-prompt-files))

(defn validate-jira-config
  "Validate that required Jira configuration is present"
  [config]
  (let [jira-config (:jira config)
        errors
        (cond-> []
          (not (:base-url jira-config))
          (conj "Missing Jira base URL (set in config file or CRUCIBLE_JIRA_URL)")
          (not (:username jira-config))
          (conj "Missing Jira username (set in config file or CRUCIBLE_JIRA_USER)")
          (not (:api-token jira-config))
          (conj "Missing Jira API token (set in config file or CRUCIBLE_JIRA_TOKEN)"))]
    (when (seq errors) errors)))

(defn config-locations
  "Return a string describing where config files are loaded from"
  []
  (str "Configuration is loaded from (in order of precedence):\n"
       "  1. ./crucible.edn (project-specific)\n"
       "  2. ~/.config/crucible/config.edn (user config)\n"
       "  3. Environment variables (CRUCIBLE_*)\n" "  4. Built-in defaults"))

(defn get-config-file-status
  "Get the actual status of config files and their paths"
  []
  (let [project-path "crucible.edn"
        xdg-config-home (or (System/getenv "XDG_CONFIG_HOME")
                            (str (System/getProperty "user.home") "/.config"))
        xdg-path (str (fs/path xdg-config-home "crucible" "config.edn"))]
    {:project-config {:path project-path,
                      :exists (fs/exists? project-path),
                      :readable (and (fs/exists? project-path)
                                     (fs/readable? project-path))},
     :xdg-config {:path xdg-path,
                  :exists (fs/exists? xdg-path),
                  :readable (and (fs/exists? xdg-path)
                                 (fs/readable? xdg-path))}}))

(defn get-env-var-status
  "Get status of relevant environment variables"
  []
  (let [env-vars ["CRUCIBLE_JIRA_URL" "CRUCIBLE_JIRA_USERNAME"
                  "CRUCIBLE_JIRA_API_TOKEN" "CRUCIBLE_WORKSPACE_DIR" "EDITOR"]]
    (into {}
          (map (fn [var] [var
                          {:set (some? (System/getenv var)),
                           :value (when-let [val (System/getenv var)]
                                    (if (str/includes? var "TOKEN")
                                      "*****" ; Hide sensitive values
                                      val))}])
               env-vars))))

(defn ensure-single-directory
  "I/O function to ensure a single directory exists with structured result"
  [dir-path]
  (if (fs/exists? dir-path)
    {:success true, :result {:created false, :exists true, :path dir-path}}
    (try (fs/create-dirs dir-path)
         {:success true, :result {:created true, :exists true, :path dir-path}}
         (catch Exception e
           {:error :directory-creation-failed,
            :context {:path dir-path, :exception (.getMessage e)}}))))

(defn build-directory-results
  "Pure function to build directory creation results map"
  [directories workspace-config directory-operations-fn]
  (->> directories
       (reduce (fn [results [dir-key description]]
                 (let [dir-path (get workspace-config dir-key)
                       operation-result (directory-operations-fn dir-path)]
                   (if (:success operation-result)
                     (assoc results
                            dir-key (merge (:result operation-result)
                                           {:description description}))
                     (assoc results
                            dir-key {:created false,
                                     :exists false,
                                     :path dir-path,
                                     :description description,
                                     :error (get-in operation-result
                                                    [:context :exception]
                                                    "Unknown error")}))))
               {})))

(defn ensure-workspace-directories
  "Create missing workspace directories using decomposed I/O functions"
  [workspace-config]
  (let [directories [[:root-dir "Root workspace directory"]
                     [:logs-dir "Logs directory"]
                     [:tickets-dir "Tickets directory"]
                     [:docs-dir "Documentation directory"]
                     [:prompts-dir "Prompts directory"]]]
    ;; Use pure function to build results, passing I/O function as
    ;; parameter
    (build-directory-results directories
                             workspace-config
                             ensure-single-directory)))

(defn check-workspace-directories
  "Check which workspace directories are missing and return summary"
  [workspace-config]
  (let [directories [[:root-dir "Root workspace directory"]
                     [:logs-dir "Logs directory"]
                     [:tickets-dir "Tickets directory"]
                     [:docs-dir "Documentation directory"]]
        missing (->> directories
                     (filter (fn [[dir-key _]]
                               (not (fs/exists? (get workspace-config dir-key))))))]
    {:total-dirs (count directories),
     :missing-dirs (count missing),
     :missing-list (->> missing
                        (map (fn [[dir-key desc]]
                               {:key dir-key,
                                :path (get workspace-config dir-key),
                                :description desc})))}))

(defn terminal-supports-color?
  "Check if the terminal supports color output"
  []
  (and (not (System/getenv "NO_COLOR")) ; Respect NO_COLOR standard
       (or (System/getenv "FORCE_COLOR") ; FORCE_COLOR overrides detection
           (and (System/console) ; Check if we're in a real terminal
                (or (System/getenv "COLORTERM") ; Modern terminal indicator
                    (when-let [term (System/getenv "TERM")]
                      (and (not= term "dumb") ; Not a dumb terminal
                           (or (str/includes? term "color")
                               (str/includes? term "xterm")
                               (str/includes? term "screen")
                               (str/includes? term "tmux")))))))))

(def color-codes
  "ANSI color codes for terminal output"
  {:red "\033[31m",
   :yellow "\033[33m",
   :green "\033[32m",
   :blue "\033[34m",
   :reset "\033[0m"})

(defn colorize
  "Apply color to text if terminal supports it"
  [text color]
  (when-not (string? text)
    (throw (ex-info "Text must be a string"
                    {:type :invalid-input :value text})))
  (when-not (keyword? color)
    (throw (ex-info "Color must be a keyword"
                    {:type :invalid-input :value color})))
  (if (terminal-supports-color?)
    (str (get color-codes color "") text (get color-codes :reset ""))
    text))

(defn red [text] (colorize text :red))

(defn yellow [text] (colorize text :yellow))

(defn green [text] (colorize text :green))

(defn blue [text] (colorize text :blue))

;; ASCII status indicators with optional color

(defn format-success
  "Format success indicator with optional color"
  [text]
  (str (green "[OK]") " " text))

(defn format-error
  "Format error indicator with optional color"
  [text]
  (str (red "[ERR]") " " text))

(defn format-warning
  "Format warning indicator with optional color"
  [text]
  (str (yellow "[WARN]") " " text))

(defn format-info
  "Format info indicator with optional color"
  [text]
  (str (blue "[INFO]") " " text))

(defn debug-log
  "Log a debug message to stderr with timestamp if debug is enabled for the section"
  [section config message]
  (when (get-in config [section :debug] false)
    (let [timestamp (.format (java.time.LocalDateTime/now)
                             (java.time.format.DateTimeFormatter/ofPattern
                              "yyyy-MM-dd HH:mm:ss"))
          section-name (str/upper-case (name section))]
      (binding [*out* *err*]
        (println (str "[" timestamp "] [" section-name "-DEBUG] " message))))))

(defn print-config-error
  "Print a configuration error with helpful context"
  [errors]
  (println "Configuration error:")
  (doseq [error errors] (println (str "  - " error)))
  (println)
  (println (config-locations))
  (println)
  (println "Example configuration (~/.config/crucible/config.edn):")
  (println "{:jira {:base-url \"https://company.atlassian.net\"")
  (println "        :username \"user@company.com\"")
  (println "        :api-token \"pass:work/jira-token\"}}"))

(defn apply-debug-flags
  "Apply debug flags to config, overriding file-based settings"
  [config flags]
  (let [{:keys [debug debug-ai debug-jira debug-sprint]} flags]
    (cond-> config
      ;; Global debug flag enables all debugging
      debug (-> (assoc-in [:ai :debug] true)
                (assoc-in [:jira :debug] true)
                (assoc-in [:sprint :debug] true))
      ;; Specific debug flags override individual services
      debug-ai (assoc-in [:ai :debug] true)
      debug-jira (assoc-in [:jira :debug] true)
      debug-sprint (assoc-in [:sprint :debug] true))))
