#!/usr/bin/env bb

;; Load library files
(load-file "core/lib/adf.clj")
(load-file "core/lib/ai.clj")
(load-file "core/lib/config.clj")
(load-file "core/lib/jira.clj")
(load-file "core/lib/process-detection.clj")
(load-file "core/lib/ticket-editor.clj")
(load-file "core/lib/daily-log.clj")
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
   [lib.jira :as jira]
   [lib.process-detection :as process-detection]
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
    (cond
      ;; List available drafts
      list-drafts
      (let [drafts (ticket-editor/get-available-drafts)]
        (if (seq drafts)
          (do
            (doseq [draft drafts] (println (:filename draft))))
          (println "No ticket drafts found")))

      ;; Clean old drafts
      clean-drafts
      (ticket-editor/clean-old-drafts)

      ;; Recover from draft - create ticket using draft content
      recover
      (let [draft-dir (ticket-editor/ensure-draft-directory)
            draft-path (if (str/starts-with? recover "/")
                         recover
                         (str draft-dir "/" recover))
            content (ticket-editor/load-draft-content draft-path)]
        (if content
          (let [parsed (ticket-editor/parse-draft-content content)]
            (if parsed
              (do
                (println (str "Recovering draft from: " (fs/file-name draft-path)))
                ;; Use recovered data as initial-data and continue normally
                ;; but mark it so we don't save another draft
                (let [recovered-data (assoc parsed :recovered-from-draft true)]
                  (quick-story-command (concat [(:title recovered-data)]
                                               (when (not (str/blank? (:description recovered-data))) ["-d" (:description recovered-data)])))))
              (System/exit 1)))
          (System/exit 1)))

      ;; Normal ticket creation logic
      :else
      ;; Get initial ticket data from file, editor, or command line
      (let [initial-data (cond
                           ;; File input
                           file
                           (if (fs/exists? file)
                             (let [content (slurp file)
                                   lines (str/split-lines content)
                                   title (first lines)
                                   description (str/join "\n" (rest lines))]
                               {:title title :description description})
                             (System/exit 1))

                           ;; Editor input
                           editor
                           (ticket-editor/open-ticket-editor summary desc)

                           ;; Command line input
                           summary
                           {:title summary :description (or desc "")}

                           ;; No input provided
                           :else nil)]

        (when-not initial-data
          (cond
            editor
            (do
              (println "Editor cancelled or no content provided")
              (System/exit 0))

            file
            (do
              (println (str "Error reading file: " file))
              (System/exit 1))

            :else
            (do
              (println "Error: story summary required")
              (println "Usage: crucible quick-story \"Your story summary\"")
              (println "   or: crucible qs \"Your story summary\"")
              (println "   or: crucible qs \"Your story summary\" -d \"Description here\"")
              (println "   or: crucible qs -e  (open editor)")
              (println "   or: crucible qs -f filename.md  (from file)")
              (println "   or: crucible qs --ai-only \"test content\"  (AI enhancement only)")
              (println "   or: crucible qs --list-drafts  (show available drafts)")
              (println "   or: crucible qs --recover <filename>  (recover from draft)")
              (System/exit 1))))

        ;; Load config for AI settings and sprint detection
        (let [config (config/load-config)
              jira-config (:jira config)
              ai-config (:ai config)
              ai-enabled (and (not no-ai)
                              (or ai ai-only (:enabled ai-config false))
                              (:gateway-url ai-config))

              ;; Apply AI enhancement if enabled
              enhanced-data (if ai-enabled
                              (do
                                (println "Enhancing content with AI...")
                                (ai/enhance-content initial-data ai-config))
                              initial-data)

              ;; Show AI changes if any
              _ (when (and ai-enabled (not= initial-data enhanced-data))
                  (ai/show-diff initial-data enhanced-data))

              ;; For editor mode with AI, open review editor
              final-data (if (and editor ai-enabled (not= initial-data enhanced-data))
                           (let [reviewed (ticket-editor/review-enhanced-ticket enhanced-data)]
                             (if reviewed
                               reviewed
                               (do
                                 (println "Review cancelled - ticket creation aborted")
                                 (System/exit 0))))
                           enhanced-data)]

          ;; Handle AI-only mode - exit without creating Jira ticket
          (when ai-only
            (println "\n=== AI-ONLY MODE ===")
            (let [{:keys [title description]} final-data
                  content-changed? (not= initial-data enhanced-data)]
              (if content-changed?
                (println "AI enhanced the content:")
                (println "AI returned unchanged content:"))
              (println)
              (println (str "Title: " title))
              (println (str "Description: " (if (str/blank? description) "(empty)" description)))
              (when content-changed?
                (println "\n(See diff above for changes)")))
            (println "====================")
            (System/exit 0))

          ;; Run sprint detection for both dry-run and normal modes
          (let [sprint-info (when (and (:auto-add-to-sprint jira-config)
                                       (:base-url jira-config)
                                       (:default-project jira-config))
                              (let [debug? (:sprint-debug jira-config false)
                                    project-key (:default-project jira-config)
                                    fallback-boards (:fallback-board-ids jira-config)
                                    sprint-pattern (:sprint-name-pattern jira-config)]

                                (when debug? (println "--- SPRINT DETECTION DEBUG ---"))
                                (when debug? (println (str "  Project: " project-key)))
                                (when debug? (println (str "  Fallback boards: " fallback-boards)))
                                (when debug? (println (str "  Name pattern: " sprint-pattern)))

                                (let [sprint-data (jira/find-sprints jira-config
                                                                     {:project-key project-key
                                                                      :debug debug?
                                                                      :fallback-board-ids fallback-boards
                                                                      :sprint-name-pattern sprint-pattern})]
                                  ;; DEBUG: Add detailed logging of what enhanced-sprint-detection actually returned
                                  (when debug?
                                    (println "--- FIND-SPRINTS RETURN VALUE DEBUG ---")
                                    (println (str "  Received sprint-data: " sprint-data))
                                    (println (str "  sprint-data type: " (type sprint-data)))
                                    (println (str "  sprint-data nil?: " (nil? sprint-data))))

                                  ;; DEBUG: Add detailed logging of sprint-data structure
                                  (when debug?
                                    (println "--- SPRINT DATA STRUCTURE DEBUG ---")
                                    (println (str "  sprint-data: " sprint-data))
                                    (when sprint-data
                                      (println (str "  :sprints key: " (:sprints sprint-data)))
                                      (println (str "  :sprints count: " (count (:sprints sprint-data))))
                                      (println (str "  :board-count: " (:board-count sprint-data)))
                                      (println (str "  :detection-method: " (:detection-method sprint-data)))))

                                  ;; Process sprint data and return result (FIX: ensure this is the return value)
                                  (let [sprint-result
                                        (when sprint-data
                                          (let [sprints (:sprints sprint-data)
                                                board-count (:board-count sprint-data)
                                                method (:detection-method sprint-data)]
                                            ;; DEBUG: Add more logging right before the cond
                                            (when debug?
                                              (println "--- SPRINT PROCESSING LOGIC DEBUG ---")
                                              (println (str "  sprints variable: " sprints))
                                              (println (str "  sprints count: " (count sprints)))
                                              (println (str "  method variable: " method)))

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
                                                (when debug?
                                                  (println "  Debug suggestions:")
                                                  (println "     - Check if project key is correct")
                                                  (println "     - Verify user has access to project boards")
                                                  (println "     - Check if sprints are in 'active' state (not future/closed)")
                                                  (println "     - Consider setting :fallback-board-ids in config")
                                                  (println "     - Try: c jira-check to test basic connectivity"))
                                                nil))))]

                                    ;; DEBUG: Log the final sprint result that will be returned
                                    (when debug?
                                      (println "--- FINAL SPRINT RESULT DEBUG ---")
                                      (println (str "  sprint-result: " sprint-result))
                                      (println (str "  sprint-result nil?: " (nil? sprint-result))))

                                    ;; Show troubleshooting if no sprint data
                                    (when (and (not sprint-data) debug?)
                                      (println "--- TROUBLESHOOTING ---")
                                      (println "  Sprint detection completely failed. Try:")
                                      (println "  1. c jira-check - verify basic connectivity")
                                      (println "  2. Set :sprint-debug true in config for detailed logging")
                                      (println "  3. Manually find board IDs and set :fallback-board-ids [123 456]")
                                      (println "  4. Set :auto-add-to-sprint false to disable sprint detection"))

                                    ;; Return the sprint result (this is the key fix!)
                                    sprint-result))))
                {:keys [title description]} final-data]

            ;; DEBUG: Log what sprint-info actually contains
            (let [debug? (:sprint-debug jira-config false)]
              (when debug?
                (println "--- SPRINT-INFO FINAL ASSIGNMENT DEBUG ---")
                (println (str "  Final sprint-info: " sprint-info))
                (println (str "  sprint-info nil?: " (nil? sprint-info)))))

            ;; Handle dry-run mode (now includes sprint detection results)
            (when dry-run
              (println "=== DRY RUN ===")
              (println (str "Title: " title))
              (println (str "Description:\n" description))
              (when file
                (println (str "Source file: " file)))
              (when sprint-info
                (let [sprint (:sprint sprint-info)]
                  (println (str "Sprint: Would be added to \"" (:name sprint) "\" (ID: " (:id sprint) ")"))))
              (when (and (:auto-add-to-sprint jira-config) (not sprint-info))
                (println "Sprint: No active sprint found (would not be added to sprint)"))
              (System/exit 0))

            ;; Proceed with normal ticket creation
            ;; Validate configuration
            (when-not (:base-url jira-config) (System/exit 1))

            (when-not (:default-project jira-config) (System/exit 1))

            ;; Get current user info if auto-assign is enabled
            (let [user-info (when (:auto-assign-self jira-config)
                              (jira/get-user-info jira-config))

                  ;; Build the issue data
                  issue-data {:fields {:project {:key (:default-project jira-config)}
                                       :summary title
                                       :issuetype {:name (:default-issue-type jira-config)}
                                       :description (jira/text->adf description)}}

                  ;; Add assignee if auto-assign is enabled and we have user info
                  issue-data (if (and user-info (:accountId user-info))
                               (assoc-in issue-data [:fields :assignee]
                                         {:accountId (:accountId user-info)})
                               issue-data)

                  ;; Add default fix version if configured
                  default-fix-version-id (:default-fix-version-id jira-config)
                  issue-data (if default-fix-version-id
                               (assoc-in issue-data [:fields :fixVersions]
                                         [{:id default-fix-version-id}])
                               issue-data)

                  ;; Add custom fields from configuration
                  custom-fields (:custom-fields jira-config {})

                  ;; Add default story points if configured and not already in custom fields
                  default-story-points (:default-story-points jira-config)
                  custom-fields-with-story-points
                  (if (and default-story-points
                           (not (some #(str/includes? (str %) "story") (keys custom-fields))))
                    ;; Story points field is commonly customfield_10002, but this should be configurable
                    ;; For now, add it to custom-fields if story-points-field is configured
                    (if-let [story-points-field (:story-points-field jira-config)]
                      (assoc custom-fields story-points-field default-story-points)
                      custom-fields)
                    custom-fields)

                  issue-data (if (seq custom-fields-with-story-points)
                               (update issue-data :fields merge custom-fields-with-story-points)
                               issue-data)]

              ;; Create the issue
              (println (cond
                         file "Creating story from file..."
                         ai-enabled "Creating AI-enhanced story..."
                         :else "Creating story..."))
              (let [result (jira/create-issue jira-config issue-data)
                    draft-path (:draft-path final-data)]
                (if (:success result)
                  (do
                    ;; Success - clean up draft file and show results
                    (ticket-editor/cleanup-draft draft-path)
                    (let [issue-key (:key result)
                          sprint-added? (when sprint-info
                                          (jira/add-issue-to-sprint
                                           jira-config
                                           (:id (:sprint sprint-info))
                                           issue-key))]
                      (println (str "Created " issue-key))))
                  (do
                    ;; Failure - preserve draft and show recovery info
                    (println (str "Error: " (:error result)))
                    (System/exit 1)))))))))))

;; Command registry for CLI dispatch
(def command-registry
  {:pipe commands/pipe-command
   :quick-story quick-story-command
   :inspect-ticket commands/inspect-ticket-command
   :doctor commands/doctor-command})

;; For bb execution
(apply cli/-main command-registry *command-line-args*)
