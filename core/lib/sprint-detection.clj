(ns lib.sprint-detection
  "Sprint detection and assignment logic for Jira tickets"
  (:require
   [lib.config :as config]
   [lib.jira :as jira]))

(defn run-sprint-detection
  "Run sprint detection for both dry-run and normal modes"
  [jira-config]
  (when (and (:auto-add-to-sprint jira-config)
             (:base-url jira-config)
             (:default-project jira-config))
    (let [debug? (:debug jira-config false)
          project-key (:default-project jira-config)
          fallback-boards (:fallback-board-ids jira-config)
          sprint-pattern (:sprint-name-pattern jira-config)]

      (config/debug-log :jira jira-config "--- SPRINT DETECTION DEBUG ---")
      (config/debug-log :jira jira-config (str "  Project: " project-key))
      (config/debug-log :jira jira-config (str "  Fallback boards: " fallback-boards))
      (config/debug-log :jira jira-config (str "  Name pattern: " sprint-pattern))

      (let [sprint-data (jira/find-sprints jira-config
                                           {:project-key project-key
                                            :debug debug?
                                            :fallback-board-ids fallback-boards
                                            :sprint-name-pattern sprint-pattern})]
        ;; DEBUG: Add detailed logging of what enhanced-sprint-detection actually returned
        (config/debug-log :jira jira-config "--- FIND-SPRINTS RETURN VALUE DEBUG ---")
        (config/debug-log :jira jira-config (str "  Received sprint-data: " sprint-data))
        (config/debug-log :jira jira-config (str "  sprint-data type: " (type sprint-data)))
        (config/debug-log :jira jira-config (str "  sprint-data nil?: " (nil? sprint-data)))

        ;; DEBUG: Add detailed logging of sprint-data structure
        (config/debug-log :jira jira-config "--- SPRINT DATA STRUCTURE DEBUG ---")
        (config/debug-log :jira jira-config (str "  sprint-data: " sprint-data))
        (when (:debug jira-config false)
          (when sprint-data
            (config/debug-log :jira jira-config (str "  :sprints key: " (:sprints sprint-data)))
            (config/debug-log :jira jira-config (str "  :sprints count: " (count (:sprints sprint-data))))
            (config/debug-log :jira jira-config (str "  :board-count: " (:board-count sprint-data)))
            (config/debug-log :jira jira-config (str "  :detection-method: " (:detection-method sprint-data)))))

        ;; Process sprint data and return result (FIX: ensure this is the return value)
        (let [sprint-result
              (when sprint-data
                (let [sprints (:sprints sprint-data)
                      board-count (:board-count sprint-data)
                      method (:detection-method sprint-data)]
                  ;; DEBUG: Add more logging right before the cond
                  (config/debug-log :jira jira-config "--- SPRINT PROCESSING LOGIC DEBUG ---")
                  (config/debug-log :jira jira-config (str "  sprints variable: " sprints))
                  (config/debug-log :jira jira-config (str "  sprints count: " (count sprints)))
                  (config/debug-log :jira jira-config (str "  method variable: " method))

                  (cond
                    (= 1 (count sprints))
                    (do
                      (println (str "  Found 1 active sprint across " board-count " boards (" method ")"))
                      {:sprint (first sprints) :method method})

                    (> (count sprints) 1)
                    (do
                      (println (str "  Found " (count sprints) " active sprints, using: " (:name (first sprints)) " (" method ")"))
                      {:sprint (first sprints) :method method})

                    :else
                    (do
                      (println (str "  No active sprints found (" method ")"))
                      (when (:debug jira-config false)
                        (config/debug-log :jira jira-config "  Debug suggestions:")
                        (config/debug-log :jira jira-config "     - Check if project key is correct")
                        (config/debug-log :jira jira-config "     - Verify user has access to project boards")
                        (config/debug-log :jira jira-config "     - Check if sprints are in 'active' state (not future/closed)")
                        (config/debug-log :jira jira-config "     - Consider setting :fallback-board-ids in config")
                        (config/debug-log :jira jira-config "     - Try: c jira-check to test basic connectivity"))
                      nil))))]

          ;; DEBUG: Log the final sprint result that will be returned
          (config/debug-log :jira jira-config "--- FINAL SPRINT RESULT DEBUG ---")
          (config/debug-log :jira jira-config (str "  sprint-result: " sprint-result))
          (config/debug-log :jira jira-config (str "  sprint-result nil?: " (nil? sprint-result)))

          ;; Show troubleshooting if no sprint data
          (when (and (not sprint-data) (:debug jira-config false))
            (config/debug-log :jira jira-config "--- TROUBLESHOOTING ---")
            (config/debug-log :jira jira-config "  Sprint detection completely failed. Try:")
            (config/debug-log :jira jira-config "  1. c jira-check - verify basic connectivity")
            (config/debug-log :jira jira-config "  2. Set :debug true in :jira config for detailed logging")
            (config/debug-log :jira jira-config "  3. Manually find board IDs and set :fallback-board-ids [123 456]")
            (config/debug-log :jira jira-config "  4. Set :auto-add-to-sprint false to disable sprint detection"))

          ;; Return the sprint result (this is the key fix!)
          sprint-result)))))

(defn log-sprint-debug-info
  "Log final sprint assignment debug information"
  [sprint-info jira-config]
  (let [debug? (:debug jira-config false)]
    (when debug?
      (println "--- SPRINT-INFO FINAL ASSIGNMENT DEBUG ---")
      (println (str "  Final sprint-info: " sprint-info))
      (println (str "  sprint-info nil?: " (nil? sprint-info))))))

(defn show-dry-run-sprint-info
  "Show sprint information in dry-run mode"
  [sprint-info jira-config]
  (when sprint-info
    (let [sprint (:sprint sprint-info)]
      (println (str "Sprint: Would be added to \"" (:name sprint) "\" (ID: " (:id sprint) ")"))))
  (when (and (:auto-add-to-sprint jira-config) (not sprint-info))
    (println "Sprint: No active sprint found (would not be added to sprint)")))

(defn add-ticket-to-sprint
  "Add created ticket to detected sprint if available"
  [sprint-info jira-config issue-key]
  (when sprint-info
    (jira/add-issue-to-sprint
     jira-config
     (:id (:sprint sprint-info))
     issue-key)))