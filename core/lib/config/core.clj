(ns lib.config.core
  "Core configuration loading and management"
  (:require [babashka.fs :as fs]
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
          ;; "customfield_10002")
          :default-fix-version-id nil, ; Fix version ID to set for all
          ;; tickets (e.g. "10100")
          :auto-assign-self true,
          :auto-add-to-sprint true,
          :debug false, ; Enable debug logging for Jira API calls and
          ;; sprint detection
          :fallback-board-ids nil,
          :sprint-name-pattern nil,
          :sprint-exclude-statuses ["Done"], ; Statuses to exclude from
          ;; sprint tickets list
          :sprint-show-done-tickets false, ; Override to show all tickets
          ;; including Done
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
   :ai {:enabled true, ; AI enhancement enabled by default
        :debug false, ; Enable debug logging for AI API calls
        :gateway-url nil,
        :api-key nil,
        :model "gpt-4",
        :max-tokens 1024,
        :timeout-ms 5000,
        :prompt nil, ; Default prompt (set via prompt-file or falls back to
        ;; built-in)
        :prompt-file nil, ; Path to external prompt file (alternative to
        ;; :prompt)
        ;; Response parsing paths - can be customized for different AI
        ;; providers Defaults to common response structures (defined in
        ;; lib.ai/default-response-paths)
        :response-paths nil, ; Override if provider uses non-standard
        ;; response format. Message template for API requests - customize
        ;; roles and content as needed. Available variables: {prompt},
        ;; {title},
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
                                ;; look for last log
                                :gather-jira-context true, ; Pull recent Jira
                                ;; activity
                                :carry-forward-tasks true, ; Include
                                ;; uncompleted
                                ;; tasks
                                :show-sprint-status true, ; Include current
                                ;; sprint info
                                :jira-activity-days 5, ; How many days of
                                ;; Jira history to
                                ;; fetch
                                :jira-exclude-own-activity true, ; Hide your
                                ;; own changes
                                :jira-activity-types ["status" "assignee"
                                                      "priority" "resolution"], ; Activity
                                ;; types to show
                                :jira-max-activities 10}}, ; Maximum number
   ;; of activities to display ; How many days of. Jira history to fetch
   :editor nil})

(defn expand-path
  "Expand ~ to user home directory"
  [path]
  (when path
    (when-not (string? path)
      (throw (ex-info "Path must be a string"
                      {:type :invalid-input, :value path})))
    (if (str/starts-with? path "~")
      (str (System/getProperty "user.home") (subs path 1))
      path)))

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
        (update-in [:workspace :logs-dir] #(when % (str (fs/path root-dir %))))
        (update-in [:workspace :tickets-dir]
                   #(when % (str (fs/path root-dir %))))
        (update-in [:workspace :docs-dir] #(when % (str (fs/path root-dir %))))
        (update-in [:workspace :prompts-dir]
                   #(when % (str (fs/path root-dir %)))))))

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
              ;; Use legacy values as defaults if new config doesn't
              ;; specify them
              (update :enabled #(if (nil? %) (boolean legacy-auto-add) %))
              (update :debug #(if (nil? %) (boolean legacy-debug) %))
              (update :auto-add-to-ticket
                      #(if (nil? %) (boolean legacy-auto-add) %))
              (update :fallback-board-ids
                      #(if (empty? %) (or legacy-fallback-boards []) %))
              (update :name-pattern #(if (nil? %) legacy-name-pattern %)))]
    ;; Return config with normalized sprint section
    (assoc config :sprint normalized-sprint)))

(defn resolve-prompt-file-path
  "Resolve prompt file path - absolute, home-relative, or relative to prompts-dir"
  [prompt-file-path config]
  (when prompt-file-path
    (cond
      ;; Absolute path - use as-is
      (str/starts-with? prompt-file-path "/") prompt-file-path
      ;; Home directory path - expand ~
      (str/starts-with? prompt-file-path "~") (expand-path prompt-file-path)
      ;; Relative path - resolve against prompts-dir
      :else (str (fs/path (get-in config [:workspace :prompts-dir])
                          prompt-file-path)))))

(defn load-prompt-file
  "Load prompt text from external file using decomposed I/O functions"
  [prompt-file-path config]
  (when prompt-file-path
    (let [resolved-path (resolve-prompt-file-path prompt-file-path config)
          ;; I/O: Read file content (reusing existing function)
          read-result (read-file-content resolved-path)]
      (if (:success read-result)
        (if-let [content (:result read-result)]
          content
          ;; File doesn't exist
          (throw (ex-info (str "Prompt file not found: " resolved-path)
                          {:path resolved-path,
                           :original-path prompt-file-path})))
        ;; Handle read errors
        (let [error-context (:context read-result)]
          (throw (ex-info (str "Failed to read prompt file: " resolved-path)
                          {:path resolved-path,
                           :original-path prompt-file-path,
                           :error (:exception error-context)})))))))

(defn resolve-prompt-files
  "Resolve external prompt files in AI config"
  [config]
  (if-let [ai-config (:ai config)]
    (let [prompt-file (:prompt-file ai-config)
          prompt (:prompt ai-config)]
      (cond
        ;; Load from external file (prompt-file takes precedence)
        (and prompt-file (not (str/blank? prompt-file)))
          (try (let [loaded-prompt (load-prompt-file prompt-file config)]
                 (-> config
                     (assoc-in [:ai :prompt] loaded-prompt)
                     (assoc-in [:ai :prompt-file] prompt-file))) ; Keep file
               ;; path for debugging
               (catch Exception e
                 (throw (ex-info (str "Error loading prompt file: "
                                      (.getMessage e))
                                 {:prompt-file prompt-file, :cause e}))))
        ;; Use configured prompt or default
        prompt config
        ;; Neither prompt nor prompt-file configured - use default
        :else (assoc-in config [:ai :prompt] default-ai-prompt)))
    config))

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