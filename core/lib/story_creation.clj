(ns lib.story-creation
  "Story creation and Jira ticket building logic"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [lib.ai :as ai]
            [lib.jira :as jira]
            [lib.sprint-detection :as sprint-detection]
            [lib.ticket-editor :as ticket-editor]))

(defn resolve-file-path
  "Resolve file path - absolute paths as-is, relative paths from user's directory"
  [file-path]
  (if (fs/absolute? file-path)
    file-path
    (if-let [user-dir (System/getenv "CRUCIBLE_USER_DIR")]
      (str (fs/absolutize (fs/path user-dir file-path)))
      (str (fs/absolutize file-path)))))

(defn get-initial-ticket-data
  "Get initial ticket data from various input sources"
  [flags summary desc]
  (let [{:keys [file editor]} flags]
    (cond
      ;; File input
      file (let [resolved-path (resolve-file-path file)]
             (if (fs/exists? resolved-path)
               (try (let [content (slurp (str resolved-path))
                          lines (str/split-lines content)
                          title (first lines)
                          description (str/join "\n" (rest lines))]
                      {:success true,
                       :result {:title title,
                                :description description,
                                :source :file,
                                :file-path resolved-path}})
                    (catch Exception e
                      {:error :file-read-failed,
                       :message "Failed to read file",
                       :context {:file-path resolved-path,
                                 :exception (.getMessage e)}}))
               {:error :file-not-found,
                :message "File not found",
                :context {:file-path resolved-path}}))
      ;; Editor input
      editor (let [editor-result (ticket-editor/open-ticket-editor summary
                                                                   desc)]
               (if (:success editor-result)
                 {:success true,
                  :result (assoc (:result editor-result) :source :editor)}
                 editor-result))
      ;; Command line input
      summary {:success true,
               :result {:title summary,
                        :description (or desc ""),
                        :source :command-line}}
      ;; No input provided
      :else {:error :no-input,
             :message "No input provided",
             :context {:flags flags}})))

(defn handle-missing-input
  "Handle cases where no input was provided - now returns validation result"
  [initial-data-result flags]
  (if (:success initial-data-result)
    initial-data-result
    (let [{:keys [editor file]} flags
          error-type (:error initial-data-result)]
      (cond
        (and editor (= error-type :user-cancelled))
          {:error :user-cancelled,
           :message "Editor cancelled or no content provided",
           :context {:action :exit-gracefully}}
        (and file (= error-type :file-not-found))
          {:error :file-not-found,
           :message (str "Error reading file: " file),
           :context {:file file, :action :exit-with-error}}
        (= error-type :no-input)
          {:error :no-input,
           :message "Error: story summary required",
           :context
             {:usage-help
                ["Usage: crucible quick-story \"Your story summary\""
                 "   or: crucible qs \"Your story summary\""
                 "   or: crucible qs \"Your story summary\" -d \"Description here\""
                 "   or: crucible qs -e  (open editor)"
                 "   or: crucible qs -f filename.md  (from file)"
                 "   or: crucible qs --ai-only \"test content\"  (AI enhancement only)"
                 "   or: crucible qs --list-drafts  (show available drafts)"
                 "   or: crucible qs --recover <filename>  (recover from draft)"],
              :action :exit-with-error}}
        :else initial-data-result))))

(defn apply-ai-enhancement
  "Apply AI enhancement to ticket content if enabled"
  [initial-data flags ai-config]
  (let [{:keys [ai no-ai ai-only]} flags
        ai-enabled (boolean (and (not no-ai)
                                 (or ai ai-only (:enabled ai-config false))
                                 (:gateway-url ai-config)))]
    (if ai-enabled (ai/enhance-content initial-data ai-config) initial-data)))

(defn handle-ai-review
  "Handle AI review editor for enhanced content"
  [initial-data enhanced-data flags]
  (let [{:keys [editor ai-only debug debug-ai no-review]} flags
        ai-enhanced (not= initial-data enhanced-data)
        debug-mode (or debug debug-ai)]
    (when (and ai-enhanced (not= initial-data enhanced-data) debug-mode)
      (ai/show-enhanced-content initial-data enhanced-data ai-only))
    (if (and ai-enhanced (not no-review))
      (let [review-result (ticket-editor/review-enhanced-ticket enhanced-data)]
        (cond (:success review-result) {:success true,
                                        :result (:result review-result)}
              (:error review-result)
                {:error :review-cancelled,
                 :message "Review cancelled - ticket creation aborted",
                 :context {:review-error (:error review-result),
                           :original-error (:message review-result),
                           :action :exit-gracefully}}
              :else {:error :review-failed,
                     :message "Unexpected review result",
                     :context {:review-result review-result}}))
      {:success true, :result enhanced-data})))

(defn handle-ai-only-mode
  "Handle AI-only mode that returns results without creating Jira ticket"
  [final-data initial-data enhanced-data flags]
  (let [{:keys [ai-only]} flags]
    (if ai-only
      (let [{:keys [title description]} final-data
            content-changed? (not= initial-data enhanced-data)]
        {:success true,
         :result {:mode :ai-only,
                  :content-changed content-changed?,
                  :title title,
                  :description description,
                  :action :exit-gracefully}})
      {:success true, :result {:mode :normal, :continue true}})))

(defn handle-dry-run-mode
  "Handle dry-run mode that returns what would be created"
  [flags final-data sprint-info jira-config]
  (let [{:keys [dry-run file]} flags]
    (if dry-run
      (let [{:keys [title description]} final-data
            sprint-result (sprint-detection/show-dry-run-sprint-info
                            sprint-info
                            jira-config)]
        {:success true,
         :result {:mode :dry-run,
                  :title title,
                  :description description,
                  :file file,
                  :sprint-info sprint-result,
                  :action :exit-gracefully}})
      {:success true, :result {:mode :normal, :continue true}})))

(defn validate-jira-config
  "Validate Jira configuration before creating ticket"
  [jira-config]
  (let [missing-fields (cond-> []
                         (not (:base-url jira-config)) (conj :base-url)
                         (not (:default-project jira-config))
                           (conj :default-project))]
    (if (empty? missing-fields)
      {:success true, :result {:config jira-config}}
      {:error :missing-config,
       :message "Missing required Jira configuration",
       :context {:missing-fields missing-fields, :config jira-config}})))

(defn build-issue-data
  "Build Jira issue data with all configured fields"
  [final-data jira-config]
  (let [{:keys [title description]} final-data
        ;; Get current user info if auto-assign is enabled
        user-info (when (:auto-assign-self jira-config)
                    (jira/get-user-info jira-config))
        ;; Build the base issue data
        issue-data {:fields {:project {:key (:default-project jira-config)},
                             :summary title,
                             :issuetype {:name (:default-issue-type
                                                 jira-config)},
                             :description (jira/text->adf description)}}
        ;; Add assignee if auto-assign is enabled and we have user info
        issue-data (if (and user-info (:accountId user-info))
                     (assoc-in issue-data
                       [:fields :assignee]
                       {:accountId (:accountId user-info)})
                     issue-data)
        ;; Add default fix version if configured
        default-fix-version-id (:default-fix-version-id jira-config)
        issue-data (if default-fix-version-id
                     (assoc-in issue-data
                       [:fields :fixVersions]
                       [{:id default-fix-version-id}])
                     issue-data)
        ;; Add custom fields from configuration
        custom-fields (:custom-fields jira-config {})
        ;; Add default story points if configured and not already in custom
        ;; fields
        default-story-points (:default-story-points jira-config)
        custom-fields-with-story-points
          (if (and default-story-points
                   (not (some #(str/includes? (str %) "story")
                              (keys custom-fields))))
            ;; Story points field is commonly customfield_10002, but this
            ;; should be configurable. For now, add it to custom-fields if
            ;; story-points-field is configured
            (if-let [story-points-field (:story-points-field jira-config)]
              (assoc custom-fields story-points-field default-story-points)
              custom-fields)
            custom-fields)
        issue-data
          (if (seq custom-fields-with-story-points)
            (update issue-data :fields merge custom-fields-with-story-points)
            issue-data)]
    issue-data))

(defn create-jira-ticket
  "Create the Jira ticket and handle success/failure"
  [issue-data final-data jira-config flags]
  (let [{:keys [file]} flags
        ai-enabled (contains? final-data :ai-enhanced)]
    (try
      ;; Create the issue
      (println (cond file "Creating story from file..."
                     ai-enabled "Creating AI-enhanced story..."
                     :else "Creating story..."))
      (let [result (jira/create-issue jira-config issue-data)
            draft-path (:draft-path final-data)]
        (if (:success result)
          (do
            ;; Success - clean up draft file and show results
            (ticket-editor/cleanup-draft draft-path)
            {:success true,
             :result {:issue-key (:key result),
                      :draft-cleaned true,
                      :creation-type (cond file :file
                                           ai-enabled :ai-enhanced
                                           :else :normal)}})
          {:error :jira-creation-failed,
           :message "Failed to create Jira ticket",
           :context {:jira-error (:error result),
                     :draft-path draft-path,
                     :issue-data issue-data}}))
      (catch Exception e
        {:error :ticket-creation-exception,
         :message "Exception during ticket creation",
         :context {:exception (.getMessage e), :issue-data issue-data}}))))
