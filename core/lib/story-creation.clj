(ns lib.story-creation
  "Story creation and Jira ticket building logic"
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [lib.ai :as ai]
   [lib.config :as config]
   [lib.jira :as jira]
   [lib.ticket-editor :as ticket-editor]))

(defn get-initial-ticket-data
  "Get initial ticket data from various input sources"
  [flags summary desc]
  (let [{:keys [file editor]} flags]
    (cond
      ;; File input
      file
      (if (fs/exists? file)
        (let [content (slurp file)
              lines (str/split-lines content)
              title (first lines)
              description (str/join "\n" (rest lines))]
          {:title title :description description})
        (do
          (println (str "Error: File not found: " file))
          (System/exit 1)))

      ;; Editor input
      editor
      (ticket-editor/open-ticket-editor summary desc)

      ;; Command line input
      summary
      {:title summary :description (or desc "")}

      ;; No input provided
      :else nil)))

(defn handle-missing-input
  "Handle cases where no input was provided"
  [initial-data flags]
  (when-not initial-data
    (let [{:keys [editor file]} flags]
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
          (System/exit 1))))))

(defn apply-ai-enhancement
  "Apply AI enhancement to ticket content if enabled"
  [initial-data flags ai-config]
  (let [{:keys [ai no-ai ai-only]} flags
        ai-enabled (and (not no-ai)
                        (or ai ai-only (:enabled ai-config false))
                        (:gateway-url ai-config))]
    (if ai-enabled
      (do
        (println "Enhancing content with AI...")
        (ai/enhance-content initial-data ai-config))
      initial-data)))

(defn handle-ai-review
  "Handle AI review editor for enhanced content"
  [initial-data enhanced-data flags]
  (let [{:keys [editor]} flags
        ai-enhanced (not= initial-data enhanced-data)]
    (when (and ai-enhanced (not= initial-data enhanced-data))
      (ai/show-diff initial-data enhanced-data))

    (if (and editor ai-enhanced)
      (let [reviewed (ticket-editor/review-enhanced-ticket enhanced-data)]
        (if reviewed
          reviewed
          (do
            (println "Review cancelled - ticket creation aborted")
            (System/exit 0))))
      enhanced-data)))

(defn handle-ai-only-mode
  "Handle AI-only mode that exits without creating Jira ticket"
  [final-data initial-data enhanced-data flags]
  (let [{:keys [ai-only]} flags]
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
      (System/exit 0))))

(defn handle-dry-run-mode
  "Handle dry-run mode that shows what would be created"
  [flags final-data sprint-info jira-config]
  (let [{:keys [dry-run file]} flags]
    (when dry-run
      (println "=== DRY RUN ===")
      (let [{:keys [title description]} final-data]
        (println (str "Title: " title))
        (println (str "Description:\n" description))
        (when file
          (println (str "Source file: " file))))
      (when sprint-info
        (let [sprint (:sprint sprint-info)]
          (println (str "Sprint: Would be added to \"" (:name sprint) "\" (ID: " (:id sprint) ")"))))
      (when (and (:auto-add-to-sprint jira-config) (not sprint-info))
        (println "Sprint: No active sprint found (would not be added to sprint)"))
      (System/exit 0))))

(defn validate-jira-config
  "Validate Jira configuration before creating ticket"
  [jira-config]
  (when-not (:base-url jira-config) (System/exit 1))
  (when-not (:default-project jira-config) (System/exit 1)))

(defn build-issue-data
  "Build Jira issue data with all configured fields"
  [final-data jira-config]
  (let [{:keys [title description]} final-data
        ;; Get current user info if auto-assign is enabled
        user-info (when (:auto-assign-self jira-config)
                    (jira/get-user-info jira-config))

        ;; Build the base issue data
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
    issue-data))

(defn create-jira-ticket
  "Create the Jira ticket and handle success/failure"
  [issue-data final-data jira-config flags]
  (let [{:keys [file]} flags
        ai-enabled (contains? final-data :ai-enhanced)]
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
          {:success true :issue-key (:key result)})
        (do
          ;; Failure - preserve draft and show recovery info
          (println (str "Error: " (:error result)))
          (System/exit 1))))))