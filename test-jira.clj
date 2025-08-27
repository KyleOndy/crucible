#!/usr/bin/env bb

;; Test script for Jira integration
;; Usage: bb test-jira.clj [ticket-id]

(load-file "core/lib/config.clj")
(load-file "core/lib/jira.clj")


(require '[lib.config :as config]
         '[lib.jira :as jira])


(defn test-jira-connection
  []
  (println "Loading configuration...")
  (let [config (config/load-config)
        jira-config (:jira config)]

    ;; Check configuration
    (println "\nChecking Jira configuration...")
    (if-let [errors (config/validate-jira-config config)]
      (do
        (println "Configuration errors found:")
        (doseq [error errors]
          (println (str "  - " error)))
        (System/exit 1))
      (println "✓ Configuration valid"))

    ;; Test connection
    (println "\nTesting Jira connection...")
    (let [result (jira/test-connection jira-config)]
      (if (:success result)
        (println (str "✓ " (:message result)))
        (do
          (println (str "✗ " (:message result)))
          (System/exit 1))))

    ;; Test ticket fetching if ticket ID provided
    (when-let [ticket-id (first *command-line-args*)]
      (println (str "\nFetching ticket " ticket-id "..."))
      (let [result (jira/get-ticket jira-config ticket-id)]
        (if (:success result)
          (let [summary (jira/format-ticket-summary (:data result))]
            (println "\n✓ Ticket fetched successfully:")
            (println (str "  Key: " (:key summary)))
            (println (str "  Summary: " (:summary summary)))
            (println (str "  Status: " (:status summary)))
            (println (str "  Type: " (:type summary)))
            (println (str "  Priority: " (:priority summary)))
            (println (str "  Assignee: " (:assignee summary))))
          (println (str "\n✗ Failed to fetch ticket: " (:error result))))))))


(test-jira-connection)
