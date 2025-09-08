#!/usr/bin/env bb

;; Load library files
(load-file "core/lib/adf.clj")
(load-file "core/lib/ai.clj")
(load-file "core/lib/config.clj")
(load-file "core/lib/jira.clj")
(load-file "core/lib/process-detection.clj")
(load-file "core/lib/ticket-editor.clj")
(load-file "core/lib/daily-log.clj")
(load-file "core/lib/draft-management.clj")
(load-file "core/lib/sprint-detection.clj")
(load-file "core/lib/story-creation.clj")
(load-file "core/lib/commands.clj")
(load-file "core/lib/cli.clj")

(ns crucible
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [lib.ai :as ai]
   [lib.cli :as cli]
   [lib.commands :as commands]
   [lib.config :as config]
   [lib.daily-log :as daily-log]
   [lib.draft-management :as draft-management]
   [lib.jira :as jira]
   [lib.process-detection :as process-detection]
   [lib.sprint-detection :as sprint-detection]
   [lib.story-creation :as story-creation]
   [lib.ticket-editor :as ticket-editor])
  (:import
   (java.time
    LocalDate
    LocalDateTime)
   (java.time.format
    DateTimeFormatter)
   (java.util
    Locale)))

(defn quick-story-command
  "Create a quick Jira story with minimal input, via editor, or from file"
  [args]
  (let [{:keys [args flags]} (cli/parse-flags args)
        summary (first args)
        {:keys [editor dry-run file ai no-ai ai-only desc list-drafts clean-drafts recover]} flags]

    ;; Handle draft management commands first  
    (if-let [draft-result (draft-management/process-draft-commands flags quick-story-command)]
      draft-result
      ;; Normal ticket creation logic continues
      (let [;; Get initial ticket data from various sources
            initial-data (story-creation/get-initial-ticket-data flags summary desc)
            _ (story-creation/handle-missing-input initial-data flags)

            ;; Load configuration
            config (config/load-config)
            jira-config (:jira config)
            ai-config (:ai config)

            ;; Apply AI enhancement if enabled
            enhanced-data (story-creation/apply-ai-enhancement initial-data flags ai-config)

            ;; Handle AI review for editor mode
            final-data (story-creation/handle-ai-review initial-data enhanced-data flags)

            ;; Handle AI-only mode (exits if ai-only flag set)
            _ (story-creation/handle-ai-only-mode final-data initial-data enhanced-data flags)

            ;; Run sprint detection
            sprint-info (sprint-detection/run-sprint-detection jira-config)
            _ (sprint-detection/log-sprint-debug-info sprint-info jira-config)

            ;; Handle dry-run mode (exits if dry-run flag set)
            _ (story-creation/handle-dry-run-mode flags final-data sprint-info jira-config)

            ;; Validate Jira configuration
            _ (story-creation/validate-jira-config jira-config)

            ;; Build issue data and create ticket
            issue-data (story-creation/build-issue-data final-data jira-config)
            result (story-creation/create-jira-ticket issue-data final-data jira-config flags)]

        ;; Handle sprint assignment and final output
        (when (:success result)
          (let [issue-key (:issue-key result)
                sprint-added? (sprint-detection/add-ticket-to-sprint sprint-info jira-config issue-key)]
            (println (str "Created " issue-key))))))))

;; Command registry for CLI dispatch
(def command-registry
  {:pipe commands/pipe-command
   :quick-story quick-story-command
   :inspect-ticket commands/inspect-ticket-command
   :doctor commands/doctor-command})

;; For bb execution
(apply cli/-main command-registry *command-line-args*)
