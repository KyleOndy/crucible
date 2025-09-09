(ns lib.daily-log
  "Daily log management functionality for Crucible"
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

(defn get-log-path-for-date
  "Get daily log path for specific date string (YYYY-MM-DD)"
  [date-str]
  (let [config (config/load-config)
        log-dir (ensure-log-directory config)
        filename (str date-str ".md")]
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

(defn open-daily-log
  "Open today's daily log in the configured editor"
  []
  (let [log-path (get-daily-log-path)]
    (create-daily-log-from-template log-path)
    (launch-editor log-path)))

(defn find-last-working-log
  "Find the most recent daily log file, going back up to configured days"
  []
  (let [config (config/load-config)
        max-days (get-in config [:daily-workflow :start-day :max-look-back-days] 7)
        log-dir (ensure-log-directory config)
        today (LocalDate/now)]

    ;; Look backwards from yesterday up to max-days
    (loop [days-back 1]
      (when (<= days-back max-days)
        (let [check-date (.minusDays today days-back)
              check-filename (str check-date ".md")
              check-path (fs/path log-dir check-filename)]
          (if (fs/exists? check-path)
            {:path (str check-path)
             :date (str check-date)
             :days-ago days-back}
            (recur (inc days-back))))))))

(defn extract-uncompleted-tasks
  "Extract uncompleted tasks from daily log content"
  [log-content]
  (when log-content
    (let [lines (str/split-lines log-content)]
      (->> lines
           (filter #(str/includes? % "- [ ]"))
           (map #(str/trim (str/replace % #"^\s*-\s*\[\s*\]\s*" "")))
           (filter #(not (str/blank? %)))))))

(defn gather-start-day-context
  "Collect all context for start of day"
  []
  (let [config (config/load-config)
        start-day-config (get-in config [:daily-workflow :start-day])
        last-log (find-last-working-log)

        ;; Extract carried tasks if we have a last log
        carried-tasks (when (and last-log (:carry-forward-tasks start-day-config true))
                        (try
                          (let [log-content (slurp (:path last-log))]
                            (extract-uncompleted-tasks log-content))
                          (catch Exception _
                            nil)))

        ;; Get Jira activity if configured
        jira-activity (when (and last-log (:gather-jira-context start-day-config true))
                        (try
                          (let [jira-config (:jira config)
                                activity-options {:activity-days (get start-day-config :jira-activity-days 5)
                                                  :exclude-own-activity (get start-day-config :jira-exclude-own-activity true)
                                                  :activity-types (get start-day-config :jira-activity-types ["status" "assignee" "priority" "resolution"])
                                                  :max-activities (get start-day-config :jira-max-activities 10)}]
                            (when (and (:base-url jira-config) (:username jira-config))
                              (jira/get-my-recent-activity jira-config (:date last-log) activity-options)))
                          (catch Exception e
                            (println (str "Warning: Could not fetch Jira activity: " (.getMessage e)))
                            nil)))

        ;; Get current sprint info if configured  
        sprint-info (when (:show-sprint-status start-day-config true)
                      (try
                        (let [jira-config (:jira config)]
                          (when (and (:base-url jira-config) (:auto-add-to-sprint jira-config))
                            (jira/get-current-sprint-info jira-config)))
                        (catch Exception e
                          (println (str "Warning: Could not fetch sprint info: " (.getMessage e)))
                          nil)))]

    {:last-log last-log
     :carried-tasks carried-tasks
     :jira-activity jira-activity
     :sprint-info sprint-info}))

(defn build-context-sections
  "Build context sections from gathered data"
  [context]
  (let [{:keys [last-log carried-tasks jira-activity sprint-info]} context
        sections []]

    (cond-> sections
      ;; Add context header if we have any context
      (or last-log carried-tasks jira-activity sprint-info)
      (concat ["## Previous Context"])

      ;; Last log info
      last-log
      (concat [(str "Last log: " (:date last-log) " (" (:days-ago last-log) " days ago)")])

      ;; Carried forward tasks
      (seq carried-tasks)
      (concat ["" "### Carried Forward Tasks"]
              (map #(str "- [ ] " %) carried-tasks))

      ;; Jira activity
      (seq jira-activity)
      (concat ["" "### Recent Jira Activity"]
              (map #(str "- " %) jira-activity))

      ;; Sprint status
      sprint-info
      (concat ["" "### Sprint Status"]
              [(str "Sprint: " (:name sprint-info) " (" (:days-remaining sprint-info) " days remaining)")]
              (when (seq (:assigned-tickets sprint-info))
                (concat ["Assigned tickets:"]
                        (map #(str "- " %) (:assigned-tickets sprint-info)))))

      ;; Add separator line if we added any context
      (or last-log carried-tasks jira-activity sprint-info)
      (concat ["" "---" ""]))))

(defn add-context-sections
  "Add context sections after the main header in daily log content"
  [base-content context]
  (let [lines (str/split-lines base-content)
        context-sections (build-context-sections context)]

    (if (seq context-sections)
      ;; Find the main header line and insert context after it
      (let [header-idx (first (keep-indexed
                               #(when (str/starts-with? %2 "# ") %1)
                               lines))]
        (if header-idx
          ;; Insert context sections after header
          (let [before-lines (take (inc header-idx) lines)
                after-lines (drop (inc header-idx) lines)
                enhanced-lines (concat before-lines [""] context-sections after-lines)]
            (str/join "\n" enhanced-lines))
          ;; No header found, prepend context
          (str/join "\n" (concat context-sections [""] lines))))
      ;; No context to add
      base-content)))

(defn enhance-daily-log-with-context
  "Add context to today's daily log file if not already present"
  [context]
  (let [log-path (get-daily-log-path)]
    ;; Ensure the log exists (create from template if needed)
    (create-daily-log-from-template log-path)

    ;; Only enhance if not already enhanced (idempotent)
    (let [current-content (slurp (str log-path))]
      (when-not (str/includes? current-content "## Previous Context")
        (let [enhanced-content (add-context-sections current-content context)]
          (spit (str log-path) enhanced-content))))))

(defn start-day-command
  "Main start-day implementation"
  []
  (println "Gathering start-day context...")

  (let [context (gather-start-day-context)]
    ;; Show what we found
    (when-let [last-log (:last-log context)]
      (println (str "Last log: " (:date last-log) " (" (:days-ago last-log) " days ago)")))

    (when-let [tasks (:carried-tasks context)]
      (when (seq tasks)
        (println (str "Found " (count tasks) " uncompleted tasks"))))

    (when-let [activity (:jira-activity context)]
      (when (seq activity)
        (println (str "Found " (count activity) " Jira updates"))))

    (when-let [sprint (:sprint-info context)]
      (println (str "Current sprint: " (:name sprint) " (" (:days-remaining sprint) " days remaining)")))

    ;; Enhance the log file
    (enhance-daily-log-with-context context)

    ;; Check if we actually added context
    (let [log-content (slurp (str (get-daily-log-path)))]
      (if (str/includes? log-content "## Previous Context")
        (println "Enhanced today's log with context.")
        (println "Today's log opened (no new context to add).")))

    ;; Open through standard path
    (println "Opening today's log...")
    (open-daily-log)))