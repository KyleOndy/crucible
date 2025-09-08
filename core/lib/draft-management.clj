(ns lib.draft-management
  "Draft management operations for ticket creation"
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [lib.ticket-editor :as ticket-editor]))

(defn handle-list-drafts
  "List available ticket drafts"
  []
  (let [drafts (ticket-editor/get-available-drafts)]
    (if (seq drafts)
      (doseq [draft drafts] (println (:filename draft)))
      (println "No ticket drafts found"))))

(defn handle-clean-drafts
  "Clean old ticket drafts"
  []
  (ticket-editor/clean-old-drafts))

(defn handle-recover-draft
  "Recover ticket content from a draft file and recursively create ticket"
  [recover-filename quick-story-fn]
  (let [draft-dir (ticket-editor/ensure-draft-directory)
        draft-path (if (str/starts-with? recover-filename "/")
                     recover-filename
                     (str draft-dir "/" recover-filename))
        content (ticket-editor/load-draft-content draft-path)]
    (if content
      (let [parsed (ticket-editor/parse-draft-content content)]
        (if parsed
          (do
            (println (str "Recovering draft from: " (fs/file-name draft-path)))
            ;; Use recovered data as initial-data and continue normally
            ;; but mark it so we don't save another draft
            (let [recovered-data (assoc parsed :recovered-from-draft true)]
              (quick-story-fn (concat [(:title recovered-data)]
                                      (when (not (str/blank? (:description recovered-data)))
                                        ["-d" (:description recovered-data)])))))
          (System/exit 1)))
      (System/exit 1))))

(defn process-draft-commands
  "Process draft-related commands. Returns :handled if a draft command was processed, nil otherwise"
  [flags quick-story-fn]
  (let [{:keys [list-drafts clean-drafts recover]} flags]
    (cond
      ;; List available drafts
      list-drafts
      (do
        (handle-list-drafts)
        :handled)

      ;; Clean old drafts
      clean-drafts
      (do
        (handle-clean-drafts)
        :handled)

      ;; Recover from draft
      recover
      (do
        (handle-recover-draft recover quick-story-fn)
        :handled)

      ;; No draft command
      :else nil)))