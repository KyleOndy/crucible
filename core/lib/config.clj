(ns lib.config
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def default-ai-prompt
  "Default AI prompt when neither :prompt nor :prompt-file is configured"
  "Enhance this Jira ticket for clarity and professionalism. Fix spelling and grammar. Keep the same general meaning but improve readability.")

(def default-config
  {:jira {:base-url nil
          :username nil
          :api-token nil
          :default-project nil
          :default-issue-type "Task"
          :default-story-points 1
          :auto-assign-self true
          ;; Legacy sprint config (maintained for backward compatibility)
          :auto-add-to-sprint true
          :sprint-debug false
          :fallback-board-ids nil
          :sprint-name-pattern nil}

   ;; Unified sprint detection configuration
   :sprint {:enabled true
            :debug false
            :auto-add-to-ticket true
            :fallback-board-ids []
            :name-pattern nil
            :detection-strategies [:project-wide :fallback-boards :pattern-matching]
            :timeout-ms 10000}

   :ai {:enabled false
        :gateway-url nil
        :api-key nil
        :model "gpt-4"
        :max-tokens 1024
        :timeout-ms 5000
        :prompt nil ; Default prompt (set via prompt-file or falls back to built-in) 
        :prompt-file nil ; Path to external prompt file (alternative to :prompt)

        ;; Message template for API requests - customize roles and content as needed
        ;; Available variables: {prompt}, {title}, {description}, {title_and_description}
        :message-template [{:role "assistant" :content "{prompt}"}
                           {:role "user" :content "Title: {title}\nDescription: {description}"}]}

   :workspace {:root-dir "workspace"
               :logs-dir "logs"
               :tickets-dir "tickets"
               :docs-dir "docs"}

   :editor nil})

(defn expand-path
  "Expand ~ to user home directory"
  [path]
  (when path
    (if (str/starts-with? path "~")
      (str (System/getProperty "user.home") (subs path 1))
      path)))

(defn resolve-pass-value
  "Resolve a pass: prefixed value by calling the pass command"
  [value]
  (if (and (string? value) (str/starts-with? value "pass:"))
    (let [pass-path (subs value 5)]
      (try
        ;; First check if pass command exists
        (let [which-result (try
                             (process/shell {:out :string :err :string} "which" "pass")
                             (catch Exception _ nil))]
          (when-not (and which-result (= 0 (:exit which-result)))
            (throw (ex-info (str "\nERROR: 'pass' command not found\n\n"
                                 "The password manager 'pass' is not installed or not in PATH.\n"
                                 "Please install it first:\n"
                                 "  • macOS:  brew install pass\n"
                                 "  • Linux:  apt install pass  (or your distro's package manager)\n"
                                 "  • Then:   pass init <your-gpg-id>\n\n"
                                 "Learn more: https://www.passwordstore.org/")
                            {:pass-path pass-path
                             :error-type :pass-not-installed}))))

        ;; Try to retrieve the password
        (let [result (process/shell {:out :string :err :string :continue true}
                                    "pass" "show" pass-path)]
          (if (= 0 (:exit result))
            (str/trim (:out result))
            ;; Parse the specific error
            (let [stderr (:err result)
                  error-msg (cond
                              (str/includes? stderr "is not in the password store")
                              (str "\n" (str "ERROR: Password entry not found: " pass-path) "\n\n"
                                   "The entry '" pass-path "' does not exist in your password store.\n\n"
                                   "To check available entries:\n"
                                   "  pass ls\n\n"
                                   "To add this entry:\n"
                                   "  pass insert " pass-path "\n\n"
                                   "Or update your config to use a different entry.")

                              (str/includes? stderr "gpg: decryption failed")
                              (str "\n" "ERROR: GPG decryption failed" "\n\n"
                                   "Could not decrypt the password entry.\n"
                                   "Possible causes:\n"
                                   "  • GPG key not available or expired\n"
                                   "  • GPG agent not running\n"
                                   "  • Wrong GPG key used\n\n"
                                   "Try:\n"
                                   "  gpg --list-secret-keys\n"
                                   "  gpgconf --kill gpg-agent && gpgconf --launch gpg-agent")

                              (str/includes? stderr "not initialized")
                              (str "\n" "ERROR: Password store not initialized" "\n\n"
                                   "Your password store has not been initialized.\n\n"
                                   "To initialize:\n"
                                   "  pass init <your-gpg-id>\n\n"
                                   "To find your GPG ID:\n"
                                   "  gpg --list-secret-keys")

                              :else
                              (str "\n" (str "ERROR: Failed to retrieve password from pass: " pass-path) "\n\n"
                                   "Pass command output:\n"
                                   (when-not (str/blank? stderr)
                                     (str "  " (str/replace stderr "\n" "\n  ") "\n\n"))
                                   "To debug:\n"
                                   "  pass show " pass-path))]
              (throw (ex-info error-msg
                              {:pass-path pass-path
                               :exit-code (:exit result)
                               :stderr stderr})))))

        (catch clojure.lang.ExceptionInfo e
          ;; Re-throw our enhanced errors
          (throw e))

        (catch Exception e
          ;; Catch any other unexpected errors
          (throw (ex-info (str "\n" "ERROR: Unexpected error using pass" "\n\n"
                               "Failed to retrieve: " pass-path "\n"
                               "Error: " (.getMessage e))
                          {:pass-path pass-path
                           :error (.getMessage e)})))))
    value))

(defn resolve-pass-references
  "Recursively resolve all pass: references in the config"
  [config]
  (cond
    (map? config) (into {} (map (fn [[k v]] [k (resolve-pass-references v)]) config))
    (string? config) (resolve-pass-value config)
    :else config))

(defn deep-merge
  "Deep merge two maps, with m2 values taking precedence"
  [m1 m2]
  (cond
    (and (map? m1) (map? m2))
    (merge-with deep-merge m1 m2)

    (nil? m2) m1
    :else m2))

(defn load-edn-file
  "Load and parse an EDN file, returning nil if it doesn't exist"
  [path]
  (when (fs/exists? path)
    (try
      (edn/read-string (slurp path))
      (catch Exception e
        (throw (ex-info (str "Failed to parse config file: " path)
                        {:path path
                         :error (.getMessage e)}))))))

(defn load-prompt-file
  "Load prompt text from external file, with path expansion support"
  [prompt-file-path]
  (when prompt-file-path
    (let [expanded-path (expand-path prompt-file-path)]
      (if (fs/exists? expanded-path)
        (try
          (slurp expanded-path)
          (catch Exception e
            (throw (ex-info (str "Failed to read prompt file: " expanded-path)
                            {:path expanded-path
                             :error (.getMessage e)}))))
        (throw (ex-info (str "Prompt file not found: " expanded-path)
                        {:path expanded-path}))))))

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
        (update-in [:jira :base-url] #(or (get-env-override env-map [:jira :base-url]) %))
        (update-in [:jira :username] #(or (get-env-override env-map [:jira :username]) %))
        (update-in [:jira :api-token] #(or (get-env-override env-map [:jira :api-token]) %))
        (update-in [:workspace :root-dir] #(or (get-env-override env-map [:workspace :root-dir]) %))
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
        normalized-sprint (-> sprint-config
                              ;; Use legacy values as defaults if new config doesn't specify them
                              (update :enabled #(if (nil? %) (boolean legacy-auto-add) %))
                              (update :debug #(if (nil? %) (boolean legacy-debug) %))
                              (update :auto-add-to-ticket #(if (nil? %) (boolean legacy-auto-add) %))
                              (update :fallback-board-ids #(if (empty? %) (or legacy-fallback-boards []) %))
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
        (try
          (let [loaded-prompt (load-prompt-file prompt-file)]
            (-> config
                (assoc-in [:ai :prompt] loaded-prompt)
                (assoc-in [:ai :prompt-file] prompt-file))) ; Keep file path for debugging
          (catch Exception e
            (throw (ex-info (str "Error loading prompt file: " (.getMessage e))
                            {:prompt-file prompt-file
                             :cause e}))))

        ;; Use configured prompt or default
        prompt
        config

        ;; Neither prompt nor prompt-file configured - use default
        :else
        (assoc-in config [:ai :prompt] default-ai-prompt)))
    config))

(defn load-config
  "Load configuration from all sources with proper precedence"
  []
  (-> default-config
      (deep-merge (load-home-config))
      (deep-merge (load-project-config))
      (apply-env-overrides)
      (resolve-pass-references)
      (expand-workspace-paths)
      (normalize-sprint-config)
      (resolve-prompt-files)))

(defn validate-jira-config
  "Validate that required Jira configuration is present"
  [config]
  (let [jira-config (:jira config)
        errors (cond-> []
                 (not (:base-url jira-config))
                 (conj "Missing Jira base URL (set in config file or CRUCIBLE_JIRA_URL)")

                 (not (:username jira-config))
                 (conj "Missing Jira username (set in config file or CRUCIBLE_JIRA_USER)")

                 (not (:api-token jira-config))
                 (conj "Missing Jira API token (set in config file or CRUCIBLE_JIRA_TOKEN)"))]
    (when (seq errors)
      errors)))

(defn config-locations
  "Return a string describing where config files are loaded from"
  []
  (str "Configuration is loaded from (in order of precedence):\n"
       "  1. ./crucible.edn (project-specific)\n"
       "  2. ~/.config/crucible/config.edn (user config)\n"
       "  3. Environment variables (CRUCIBLE_*)\n"
       "  4. Built-in defaults"))

(defn get-config-file-status
  "Get the actual status of config files and their paths"
  []
  (let [project-path "crucible.edn"
        xdg-config-home (or (System/getenv "XDG_CONFIG_HOME")
                            (str (System/getProperty "user.home") "/.config"))
        xdg-path (str (fs/path xdg-config-home "crucible" "config.edn"))]

    {:project-config {:path project-path
                      :exists (fs/exists? project-path)
                      :readable (and (fs/exists? project-path)
                                     (fs/readable? project-path))}
     :xdg-config {:path xdg-path
                  :exists (fs/exists? xdg-path)
                  :readable (and (fs/exists? xdg-path)
                                 (fs/readable? xdg-path))}}))

(defn get-env-var-status
  "Get status of relevant environment variables"
  []
  (let [env-vars ["CRUCIBLE_JIRA_URL"
                  "CRUCIBLE_JIRA_USERNAME"
                  "CRUCIBLE_JIRA_API_TOKEN"
                  "CRUCIBLE_WORKSPACE_DIR"
                  "EDITOR"]]
    (into {} (map (fn [var]
                    [var {:set (some? (System/getenv var))
                          :value (when-let [val (System/getenv var)]
                                   (if (str/includes? var "TOKEN")
                                     "*****" ; Hide sensitive values
                                     val))}])
                  env-vars))))

(defn ensure-workspace-directories
  "Create missing workspace directories. Returns a map of creation results."
  [workspace-config]
  (let [directories [[:root-dir "Root workspace directory"]
                     [:logs-dir "Logs directory"]
                     [:tickets-dir "Tickets directory"]
                     [:docs-dir "Documentation directory"]]
        results (atom {})]

    (doseq [[dir-key description] directories]
      (let [dir-path (get workspace-config dir-key)]
        (if (fs/exists? dir-path)
          (swap! results assoc dir-key {:created false :exists true :path dir-path})
          (try
            (fs/create-dirs dir-path)
            (swap! results assoc dir-key {:created true :exists true :path dir-path :description description})
            (catch Exception e
              (swap! results assoc dir-key {:created false :exists false :path dir-path :error (.getMessage e)}))))))

    @results))

(defn check-workspace-directories
  "Check which workspace directories are missing and return summary"
  [workspace-config]
  (let [directories [[:root-dir "Root workspace directory"]
                     [:logs-dir "Logs directory"]
                     [:tickets-dir "Tickets directory"]
                     [:docs-dir "Documentation directory"]]
        missing (filter (fn [[dir-key _]]
                          (not (fs/exists? (get workspace-config dir-key))))
                        directories)]
    {:total-dirs (count directories)
     :missing-dirs (count missing)
     :missing-list (map (fn [[dir-key desc]]
                          {:key dir-key
                           :path (get workspace-config dir-key)
                           :description desc})
                        missing)}))

(defn terminal-supports-color?
  "Check if the terminal supports color output"
  []
  (and (System/console) ; Check if we're in a real terminal
       (or (System/getenv "COLORTERM") ; Modern terminal indicator
           (when-let [term (System/getenv "TERM")]
             (and (not= term "dumb") ; Not a dumb terminal
                  (or (str/includes? term "color")
                      (str/includes? term "xterm")
                      (str/includes? term "screen")
                      (str/includes? term "tmux")))))))

(def color-codes
  "ANSI color codes for terminal output"
  {:red "\033[31m"
   :yellow "\033[33m"
   :green "\033[32m"
   :blue "\033[34m"
   :reset "\033[0m"})

(defn colorize
  "Apply color to text if terminal supports it"
  [text color]
  (if (terminal-supports-color?)
    (str (get color-codes color "") text (get color-codes :reset ""))
    text))

(defn red
  [text]
  (colorize text :red))

(defn yellow
  [text]
  (colorize text :yellow))

(defn green
  [text]
  (colorize text :green))

(defn blue
  [text]
  (colorize text :blue))

(defn print-config-error
  "Print a configuration error with helpful context"
  [errors]
  (println "Configuration error:")
  (doseq [error errors]
    (println (str "  - " error)))
  (println)
  (println (config-locations))
  (println)
  (println "Example configuration (~/.config/crucible/config.edn):")
  (println "{:jira {:base-url \"https://company.atlassian.net\"")
  (println "        :username \"user@company.com\"")
  (println "        :api-token \"pass:work/jira-token\"}}"))
