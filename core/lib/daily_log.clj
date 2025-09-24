(ns lib.daily-log
  "Daily log management functionality for Crucible"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [lib.config :as config]
            [lib.jira :as jira])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(defn get-date-info
  "Returns map with formatted date information for template substitution"
  []
  (let [today (LocalDate/now)
        day-formatter (DateTimeFormatter/ofPattern "EEEE" Locale/ENGLISH)
        full-formatter (DateTimeFormatter/ofPattern "EEEE, MMMM d, yyyy"
                                                    Locale/ENGLISH)]
    {:date (.toString today),
     :day-name (.format today day-formatter),
     :full-date (.format today full-formatter)}))

(defn process-template
  "Replace template variables with actual values"
  [template-content date-info]
  (when-not (string? template-content)
    (throw (ex-info "Template content must be a string"
                    {:type :invalid-input, :value template-content})))
  (when-not (map? date-info)
    (throw (ex-info "Date info must be a map"
                    {:type :invalid-input, :value date-info})))
  (-> template-content
      (str/replace "{{DATE}}" (:date date-info))
      (str/replace "{{DAY_NAME}}" (:day-name date-info))
      (str/replace "{{FULL_DATE}}" (:full-date date-info))))

(defn ensure-log-directory
  "Create logs/daily directory if it doesn't exist, using configured paths"
  [config]
  (let [log-dir (fs/path (get-in config [:workspace :logs-dir]) "daily")]
    (when-not (fs/exists? log-dir) (fs/create-dirs log-dir))
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
        (spit (str log-path)
              (str "# " (:full-date date-info) " - Daily Log\n\n"))))))

(defn launch-editor
  "Launch editor with the given file path. Returns structured result with exit code."
  [file-path]
  (let [editor (System/getenv "EDITOR")]
    (if editor
      (let [result @(process/process [editor (str file-path)] {:inherit true})]
        {:success true, :result {:exit-code (:exit result)}})
      {:error :editor-not-set,
       :message "EDITOR environment variable not set",
       :context {:action :exit-with-error}})))

(defn open-daily-log
  "Open today's daily log in the configured editor"
  []
  (let [log-path (get-daily-log-path)]
    (create-daily-log-from-template log-path)
    (launch-editor log-path)))

(defn open-log-for-relative-date
  "Open log for a date relative to today (negative numbers for past, 'yesterday' for -1)"
  [date-spec]
  (let [days-ago (cond (= date-spec "yesterday") 1
                       (string? date-spec)
                         (try (let [num (Integer/parseInt date-spec)]
                                (if (< num 0)
                                  (- num) ; Convert negative to positive
                                  num))
                              (catch Exception _
                                {:error :invalid-date-format,
                                 :message (str "Invalid date specification: "
                                               date-spec),
                                 :context
                                   {:help "Use 'yesterday', '-1', '-2', etc.",
                                    :action :exit-with-error}}))
                       (number? date-spec) (Math/abs date-spec)
                       :else {:error :invalid-date-format,
                              :message "Invalid date specification",
                              :context {:action :exit-with-error}})]
    (if (:error days-ago)
      days-ago ; Return error result
      (let [target-date (.minusDays (LocalDate/now) days-ago)
            date-str (.toString target-date)
            log-path (get-log-path-for-date date-str)]
        (if (fs/exists? log-path)
          (do (println (str "Opening log from "
                            date-str
                            " ("
                            days-ago
                            " day"
                            (when (not= days-ago 1) "s")
                            " ago)"))
              (launch-editor log-path))
          {:error :log-not-found,
           :message (str "No log found for " date-str),
           :context {:date date-str, :action :exit-with-error}})))))

(defn find-last-working-log
  "Find the most recent daily log file, going back up to configured days"
  []
  (let [config (config/load-config)
        max-days
          (get-in config [:daily-workflow :start-day :max-look-back-days] 7)
        log-dir (ensure-log-directory config)
        today (LocalDate/now)]
    ;; Look backwards from yesterday up to max-days
    (loop [days-back 1]
      (when (<= days-back max-days)
        (let [check-date (.minusDays today days-back)
              check-filename (str check-date ".md")
              check-path (fs/path log-dir check-filename)]
          (if (fs/exists? check-path)
            {:path (str check-path),
             :date (str check-date),
             :days-ago days-back}
            (recur (inc days-back))))))))

(defn extract-uncompleted-tasks
  "Extract uncompleted tasks from daily log content, preserving indentation for nested tasks"
  [log-content]
  (when log-content
    (let [lines (str/split-lines log-content)]
      (->> lines
           (filter #(str/includes? % "- [ ]"))
           (map (fn [line]
                  ;; Find the indentation level (spaces before the -)
                  (let [trimmed-line (str/triml line)
                        original-length (count line)
                        trimmed-length (count trimmed-line)
                        indent-spaces (- original-length trimmed-length)
                        ;; Remove the "- [ ] " part but keep the
                        ;; indentation
                        task-text
                          (str/replace trimmed-line #"^-\s*\[\s*\]\s*" "")]
                    ;; Reconstruct with preserved indentation
                    (str (str/join "" (repeat indent-spaces " ")) task-text))))
           (filter #(not (str/blank? (str/trim %))))))))

(defn load-previous-log-info
  "Load previous log file content with structured error handling"
  [log-path carry-forward-tasks?]
  (when (and log-path (not (string? log-path)))
    (throw (ex-info "Log path must be a string"
                    {:type :invalid-input, :value log-path})))
  (when-not (boolean? carry-forward-tasks?)
    (throw (ex-info "Carry forward tasks must be a boolean"
                    {:type :invalid-input, :value carry-forward-tasks?})))
  (if (and log-path carry-forward-tasks?)
    (try (->> (slurp log-path)
              (hash-map :content)
              (assoc :path log-path)
              (hash-map :result)
              (assoc :success true))
         (catch Exception e
           {:error :file-read-failed,
            :message "Failed to read previous log file",
            :context {:log-path log-path, :exception (.getMessage e)}}))
    {:success true, :result nil}))

(defn fetch-jira-context
  "Fetch Jira activity and sprint info with structured error handling"
  [jira-config last-log-date activity-options gather-jira? show-sprint?]
  (when-not (map? jira-config)
    (throw (ex-info "Jira config must be a map"
                    {:type :invalid-input, :value jira-config})))
  (when-not (map? activity-options)
    (throw (ex-info "Activity options must be a map"
                    {:type :invalid-input, :value activity-options})))
  (when-not (boolean? gather-jira?)
    (throw (ex-info "Gather jira must be a boolean"
                    {:type :invalid-input, :value gather-jira?})))
  (when-not (boolean? show-sprint?)
    (throw (ex-info "Show sprint must be a boolean"
                    {:type :invalid-input, :value show-sprint?})))
  (let [jira-enabled? (and (:base-url jira-config) (:username jira-config))
        fetch-activity (fn []
                         (when (and gather-jira? jira-enabled?)
                           (try (jira/get-my-recent-activity jira-config
                                                             last-log-date
                                                             activity-options)
                                (catch Exception e
                                  {:error :jira-activity-failed,
                                   :message (.getMessage e)}))))
        fetch-sprint (fn []
                       (when (and show-sprint?
                                  jira-enabled?
                                  (:auto-add-to-sprint jira-config))
                         (try (jira/get-current-sprint-info jira-config)
                              (catch Exception e
                                {:error :sprint-info-failed,
                                 :message (.getMessage e)}))))]
    {:success true,
     :result {:jira-activity (fetch-activity), :sprint-info (fetch-sprint)}}))

(defn build-context-map
  "Pure function to build final context map from loaded data"
  [last-log log-info-result jira-result]
  (when-not (map? log-info-result)
    (throw (ex-info "Log info result must be a map"
                    {:type :invalid-input, :value log-info-result})))
  (when-not (map? jira-result)
    (throw (ex-info "Jira result must be a map"
                    {:type :invalid-input, :value jira-result})))
  (let [carried-tasks (->> log-info-result
                           :result
                           :content
                           extract-uncompleted-tasks
                           (when (:success log-info-result)))
        jira-activity (get-in jira-result [:result :jira-activity])
        sprint-info (get-in jira-result [:result :sprint-info])]
    {:last-log last-log,
     :carried-tasks carried-tasks,
     :jira-activity jira-activity,
     :sprint-info sprint-info}))

(defn gather-start-day-context
  "Collect all context for start of day using decomposed I/O functions"
  []
  (let [config (config/load-config)
        start-day-config (get-in config [:daily-workflow :start-day])
        last-log (find-last-working-log)
        ;; I/O: Load previous log information
        log-info-result (load-previous-log-info
                          (:path last-log)
                          (get start-day-config :carry-forward-tasks true))
        ;; I/O: Fetch Jira context
        jira-config (:jira config)
        activity-options
          {:activity-days (get start-day-config :jira-activity-days 5),
           :exclude-own-activity
             (get start-day-config :jira-exclude-own-activity true),
           :activity-types (get start-day-config
                                :jira-activity-types
                                ["status" "assignee" "priority" "resolution"]),
           :max-activities (get start-day-config :jira-max-activities 10)}
        jira-result (fetch-jira-context
                      jira-config
                      (:date last-log)
                      activity-options
                      (get start-day-config :gather-jira-context true)
                      (get start-day-config :show-sprint-status true))]
    ;; Pure: Build final context map
    (build-context-map last-log log-info-result jira-result)))

(defn build-context-sections
  "Build context sections from gathered data"
  [context]
  (when-not (map? context)
    (throw (ex-info "Context must be a map"
                    {:type :invalid-input, :value context})))
  (let [{:keys [last-log carried-tasks jira-activity sprint-info]} context
        has-any-context? (or last-log carried-tasks jira-activity sprint-info)
        format-task (fn [task]
                      (if (str/starts-with? task " ")
                        (str "- [ ]" task) ; Preserve nested indentation
                        (str "- [ ] " task))) ; Top-level task
        sprint-tickets (when sprint-info
                         (->> (:assigned-tickets sprint-info)
                              (map #(str "- " %))))]
    (->> []
         (cond-> has-any-context? (concat ["## Previous Context"]))
         (cond-> last-log
           (concat [(str "Last log: "
                         (:date last-log)
                         " ("
                         (:days-ago last-log)
                         " days ago)")]))
         (cond-> (seq carried-tasks)
           (concat (concat ["" "### Carried Forward Tasks"]
                           (map format-task carried-tasks))))
         (cond-> (seq jira-activity)
           (concat (concat ["" "### Recent Jira Activity"]
                           (map #(str "- " %) jira-activity))))
         (cond-> sprint-info
           (concat (concat ["" "### Sprint Status"]
                           [(str "Sprint: "
                                 (:name sprint-info)
                                 " ("
                                 (:days-remaining sprint-info)
                                 " days remaining)")]
                           (when (seq sprint-tickets)
                             (concat ["Assigned tickets:"] sprint-tickets)))))
         (cond-> has-any-context? (concat ["" "---" ""])))))

(defn add-context-sections
  "Add context sections after the main header in daily log content"
  [base-content context]
  (let [lines (str/split-lines base-content)
        context-sections (build-context-sections context)]
    (if (seq context-sections)
      ;; Find the main header line and insert context after it
      (let [header-idx (first (keep-indexed #(when (str/starts-with? %2 "# ")
                                               %1)
                                            lines))]
        (if header-idx
          ;; Insert context sections after header
          (let [before-lines (take (inc header-idx) lines)
                after-lines (drop (inc header-idx) lines)
                enhanced-lines
                  (concat before-lines [""] context-sections after-lines)]
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
      (println (str "Last log: "
                    (:date last-log)
                    " ("
                    (:days-ago last-log)
                    " days ago)")))
    (when-let [tasks (:carried-tasks context)]
      (when (seq tasks)
        (println (str "Found " (count tasks) " uncompleted tasks"))))
    (when-let [activity (:jira-activity context)]
      (when (seq activity)
        (println (str "Found " (count activity) " Jira updates"))))
    (when-let [sprint (:sprint-info context)]
      (println (str "Current sprint: "
                    (:name sprint)
                    " ("
                    (:days-remaining sprint)
                    " days remaining)")))
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
