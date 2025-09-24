(ns lib.sprint-detection
  "Sprint detection and assignment logic for Jira tickets"
  (:require [lib.config :as config]
            [lib.jira :as jira]))

(defn validate-sprint-config
  "Validate sprint detection configuration"
  [jira-config]
  (if (and (:auto-add-to-sprint jira-config)
           (:base-url jira-config)
           (:default-project jira-config))
    {:success true, :result {:valid true, :config jira-config}}
    {:success true, :result {:valid false, :config jira-config}}))

(defn setup-sprint-debug-context
  "Setup debug context for sprint detection"
  [jira-config]
  (let [project-key (:default-project jira-config)
        fallback-boards (:fallback-board-ids jira-config)
        sprint-pattern (:sprint-name-pattern jira-config)
        debug? (:debug jira-config false)]
    {:success true,
     :result {:project-key project-key,
              :fallback-boards fallback-boards,
              :sprint-pattern sprint-pattern,
              :debug? debug?}}))

(defn fetch-sprint-data
  "Fetch sprint data from Jira API"
  [jira-config debug-context]
  (try
    (let [{:keys [project-key fallback-boards sprint-pattern debug?]}
            debug-context]
      (config/debug-log :jira jira-config "--- SPRINT DETECTION DEBUG ---")
      (config/debug-log :jira jira-config (str "  Project: " project-key))
      (config/debug-log :jira
                        jira-config
                        (str "  Fallback boards: " fallback-boards))
      (config/debug-log :jira
                        jira-config
                        (str "  Name pattern: " sprint-pattern))
      (let [sprint-data (jira/find-sprints jira-config
                                           {:project-key project-key,
                                            :debug debug?,
                                            :fallback-board-ids fallback-boards,
                                            :sprint-name-pattern
                                              sprint-pattern})]
        (config/debug-log :jira
                          jira-config
                          "--- FIND-SPRINTS RETURN VALUE DEBUG ---")
        (config/debug-log :jira
                          jira-config
                          (str "  Received sprint-data: " sprint-data))
        (config/debug-log :jira
                          jira-config
                          (str "  sprint-data type: " (type sprint-data)))
        (config/debug-log :jira
                          jira-config
                          (str "  sprint-data nil?: " (nil? sprint-data)))
        {:success true, :result sprint-data}))
    (catch Exception e
      {:error :fetch-failed,
       :message "Failed to fetch sprint data",
       :context {:exception (.getMessage e)}})))

(defn process-sprint-results
  "Process sprint data and determine final sprint selection"
  [sprint-data jira-config]
  (if sprint-data
    (let [sprints (:sprints sprint-data)
          board-count (:board-count sprint-data)
          method (:detection-method sprint-data)]
      (config/debug-log :jira jira-config "--- SPRINT DATA STRUCTURE DEBUG ---")
      (config/debug-log :jira jira-config (str "  sprint-data: " sprint-data))
      (when (:debug jira-config false)
        (config/debug-log :jira jira-config (str "  :sprints key: " sprints))
        (config/debug-log :jira
                          jira-config
                          (str "  :sprints count: " (count sprints)))
        (config/debug-log :jira
                          jira-config
                          (str "  :board-count: " board-count))
        (config/debug-log :jira
                          jira-config
                          (str "  :detection-method: " method)))
      (config/debug-log :jira
                        jira-config
                        "--- SPRINT PROCESSING LOGIC DEBUG ---")
      (config/debug-log :jira jira-config (str "  sprints variable: " sprints))
      (config/debug-log :jira
                        jira-config
                        (str "  sprints count: " (count sprints)))
      (config/debug-log :jira jira-config (str "  method variable: " method))
      (cond (= 1 (count sprints)) {:success true,
                                   :result {:sprint (first sprints),
                                            :method method}}
            (> (count sprints) 1) {:success true,
                                   :result {:sprint (first sprints),
                                            :method method}}
            :else {:success true,
                   :result {:sprint nil, :method method, :reason :no-sprints}}))
    {:success true, :result {:sprint nil, :method :none, :reason :no-data}}))

(defn show-troubleshooting-info
  "Show troubleshooting information when sprint detection fails"
  [process-result jira-config]
  (when (and (:debug jira-config false)
             (or (nil? (get-in process-result [:result :sprint]))
                 (= :no-data (get-in process-result [:result :reason]))))
    (cond
      (= :no-data (get-in process-result [:result :reason]))
        (do
          (config/debug-log :jira jira-config "--- TROUBLESHOOTING ---")
          (config/debug-log :jira
                            jira-config
                            "  Sprint detection completely failed. Try:")
          (config/debug-log :jira
                            jira-config
                            "  1. c jira-check - verify basic connectivity")
          (config/debug-log
            :jira
            jira-config
            "  2. Set :debug true in :jira config for detailed logging")
          (config/debug-log
            :jira
            jira-config
            "  3. Manually find board IDs and set :fallback-board-ids [123 456]")
          (config/debug-log
            :jira
            jira-config
            "  4. Set :auto-add-to-sprint false to disable sprint detection"))
      (= :no-sprints (get-in process-result [:result :reason]))
        (do
          (config/debug-log :jira jira-config "  Debug suggestions:")
          (config/debug-log :jira
                            jira-config
                            "     - Check if project key is correct")
          (config/debug-log :jira
                            jira-config
                            "     - Verify user has access to project boards")
          (config/debug-log
            :jira
            jira-config
            "     - Check if sprints are in 'active' state (not future/closed)")
          (config/debug-log
            :jira
            jira-config
            "     - Consider setting :fallback-board-ids in config")
          (config/debug-log
            :jira
            jira-config
            "     - Try: c jira-check to test basic connectivity")))
    {:success true, :result {:troubleshooting-shown true}}))

(defn run-sprint-detection
  "Run sprint detection for both dry-run and normal modes"
  [jira-config]
  (let [config-validation (validate-sprint-config jira-config)]
    (if (get-in config-validation [:result :valid])
      ;; Configuration is valid, proceed with sprint detection
      (let [debug-context-result (setup-sprint-debug-context jira-config)]
        (if (:success debug-context-result)
          (let [debug-context (:result debug-context-result)
                fetch-result (fetch-sprint-data jira-config debug-context)]
            (if (:success fetch-result)
              (let [sprint-data (:result fetch-result)
                    process-result (process-sprint-results sprint-data
                                                           jira-config)]
                ;; Show troubleshooting info if needed
                (show-troubleshooting-info process-result jira-config)
                ;; Debug log final result
                (config/debug-log :jira
                                  jira-config
                                  "--- FINAL SPRINT RESULT DEBUG ---")
                (config/debug-log :jira
                                  jira-config
                                  (str "  sprint-result: " process-result))
                (config/debug-log :jira
                                  jira-config
                                  (str "  sprint-result nil?: "
                                       (nil? (:result process-result))))
                ;; Return the final result (extracting from nested
                ;; structure)
                (when (and (:success process-result)
                           (get-in process-result [:result :sprint]))
                  (:result process-result)))
              ;; Fetch failed - return nil (maintain backward
              ;; compatibility)
              nil))
          ;; Debug context setup failed - return nil
          nil))
      ;; Configuration invalid - return nil (original behavior)
      nil)))

(defn log-sprint-debug-info
  "Log final sprint assignment debug information"
  [sprint-info jira-config]
  (let [debug? (:debug jira-config false)]
    (when debug?
      (println "--- SPRINT-INFO FINAL ASSIGNMENT DEBUG ---")
      (println (str "  Final sprint-info: " sprint-info))
      (println (str "  sprint-info nil?: " (nil? sprint-info))))
    {:success true, :result {:debug-logged debug?}}))

(defn show-dry-run-sprint-info
  "Show sprint information in dry-run mode"
  [sprint-info jira-config]
  (cond sprint-info (let [sprint (:sprint sprint-info)]
                      (println (str "Sprint: Would be added to \""
                                    (:name sprint)
                                    "\" (ID: "
                                    (:id sprint)
                                    ")"))
                      {:success true,
                       :result {:action :would-add-to-sprint, :sprint sprint}})
        (:auto-add-to-sprint jira-config)
          (do (println
                "Sprint: No active sprint found (would not be added to sprint)")
              {:success true,
               :result {:action :would-not-add, :reason :no-sprint}})
        :else {:success true, :result {:action :sprint-disabled}}))

(defn add-ticket-to-sprint
  "Add created ticket to detected sprint if available"
  [sprint-info jira-config issue-key]
  (if sprint-info
    (try (let [result (jira/add-issue-to-sprint jira-config
                                                (:id (:sprint sprint-info))
                                                issue-key)]
           {:success true,
            :result {:action :added-to-sprint,
                     :sprint (:sprint sprint-info),
                     :issue-key issue-key,
                     :jira-result result}})
         (catch Exception e
           {:error :add-failed,
            :message "Failed to add ticket to sprint",
            :context {:sprint-info sprint-info,
                      :issue-key issue-key,
                      :exception (.getMessage e)}}))
    {:success true, :result {:action :no-sprint, :issue-key issue-key}}))
