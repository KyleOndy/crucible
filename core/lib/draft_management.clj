(ns lib.draft-management
  "Draft management operations for ticket creation"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [lib.ticket-editor :as ticket-editor]))

(defn handle-list-drafts
  "List available ticket drafts"
  []
  (let [drafts (ticket-editor/get-available-drafts)]
    {:success true
     :result (if (seq drafts)
               {:drafts drafts, :count (count drafts)}
               {:drafts [], :count 0})}))

(defn handle-clean-drafts
  "Clean old ticket drafts"
  []
  (let [cleanup-result (ticket-editor/clean-old-drafts)]
    (if (:success cleanup-result)
      {:success true
       :result {:operation "clean-drafts", :details (:result cleanup-result)}}
      cleanup-result)))

(defn handle-recover-draft
  "Recover ticket content from a draft file and recursively create ticket"
  [recover-filename quick-story-fn]
  (when (str/blank? recover-filename)
    (throw (ex-info "Recovery filename cannot be blank" {:filename recover-filename})))

  (try
    (let [draft-dir (ticket-editor/ensure-draft-directory)
          draft-path (if (str/starts-with? recover-filename "/")
                       recover-filename
                       (str draft-dir "/" recover-filename))
          load-result (ticket-editor/load-draft-content draft-path)]

      (if (:success load-result)
        (let [content (get-in load-result [:result :content])
              parsed (ticket-editor/parse-draft-content content)]
          (if parsed
            (let [recovered-data (assoc parsed :recovered-from-draft true)
                  args (cond-> [(:title recovered-data)]
                         (not (str/blank? (:description recovered-data)))
                         (concat ["-d" (:description recovered-data)]))]
              {:success true
               :result {:operation "recover-draft"
                        :draft-file (fs/file-name draft-path)
                        :recovered-data recovered-data
                        :args args}})
            {:error :parse-failed
             :message "Failed to parse draft content"
             :context {:draft-path draft-path}}))
        load-result))

    (catch Exception e
      {:error :recovery-failed
       :message "Failed to recover draft"
       :context {:filename recover-filename, :exception (.getMessage e)}})))

(defn process-draft-commands
  "Process draft-related commands. Returns result data for handled commands, nil for non-draft commands"
  [flags quick-story-fn]
  (let [{:keys [list-drafts clean-drafts recover]} flags]
    (cond
      ;; List available drafts
      list-drafts (handle-list-drafts)
      ;; Clean old drafts
      clean-drafts (handle-clean-drafts)
      ;; Recover from draft
      recover (let [result (handle-recover-draft recover quick-story-fn)]
                (if (:success result)
                  ;; Execute the recovered command and merge results
                  (let [recovery-data (:result result)]
                    (try (quick-story-fn (:args recovery-data))
                         (assoc result
                                :result (assoc recovery-data
                                               :quick-story-executed true))
                         (catch Exception e
                           {:error :execution-failed,
                            :message "Failed to execute recovered draft",
                            :context {:recovery-data recovery-data,
                                      :exception (.getMessage e)}})))
                  ;; Return the error as-is
                  result))
      ;; No draft command
      :else nil)))
