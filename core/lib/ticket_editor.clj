(ns lib.ticket-editor
  "Ticket creation and editing functionality for Crucible"
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str])
  (:import (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))


(defn launch-editor
  "Launch editor with the given file path. Returns structured result with exit code or error."
  [file-path]
  (let [editor (System/getenv "EDITOR")]
    (if editor
      (try (let [result @(process/process [editor (str file-path)]
                                          {:inherit true})]
             {:success true,
              :result {:exit-code (:exit result),
                       :editor editor,
                       :file-path (str file-path)}})
           (catch Exception e
             {:error :editor-launch-failed,
              :message "Failed to launch editor",
              :context {:editor editor,
                        :file-path (str file-path),
                        :exception (.getMessage e)}}))
      {:error :no-editor,
       :message "EDITOR environment variable not set",
       :context {:file-path (str file-path)}})))


(defn ensure-draft-directory
  "Ensure draft directory exists and return path"
  []
  (let [draft-dir (str (fs/cwd) "/temp/ticket-drafts")]
    (fs/create-dirs draft-dir)
    draft-dir))


(defn save-draft-copy
  "Save copy of ticket content to draft directory, return draft path"
  [content]
  (let [draft-dir (ensure-draft-directory)
        now (LocalDateTime/now)
        formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmm-ss")
        draft-filename (str "draft-" (.format now formatter) ".md")
        draft-path (str draft-dir "/" draft-filename)]
    (spit draft-path content)
    draft-path))


(defn cleanup-draft
  "Remove draft file if it exists"
  [draft-path]
  (when (and draft-path (fs/exists? draft-path)) (fs/delete draft-path)))


(defn get-available-drafts
  "List available draft files with metadata"
  []
  (let [draft-dir (ensure-draft-directory)]
    (if (fs/exists? draft-dir)
      (->> (fs/list-dir draft-dir)
           (filter #(str/ends-with? (str %) ".md"))
           (map (fn [path]
                  (let [filename (fs/file-name path)
                        size (fs/size path)
                        modified (fs/last-modified-time path)]
                    {:path (str path),
                     :filename filename,
                     :size size,
                     :modified modified})))
           (sort-by :modified)
           reverse)
      [])))


(defn clean-old-drafts
  "Remove draft files older than specified days (default 7)"
  [& {:keys [days], :or {days 7}}]
  (try (let [draft-dir (ensure-draft-directory)
             cutoff-time (-> (LocalDateTime/now)
                             (.minusDays days)
                             (.toInstant (java.time.ZoneOffset/UTC)))
             deleted-files (atom [])]
         (when (fs/exists? draft-dir)
           (doseq [file (fs/list-dir draft-dir)]
             (when (str/ends-with? (str file) ".md")
               (let [modified-time (fs/last-modified-time file)]
                 (when (.isBefore (.toInstant modified-time) cutoff-time)
                   (fs/delete file)
                   (swap! deleted-files conj (str file)))))))
         {:success true,
          :result {:deleted-count (count @deleted-files),
                   :deleted-files @deleted-files,
                   :cutoff-days days}})
       (catch Exception e
         {:error :cleanup-failed,
          :message "Failed to clean old drafts",
          :context {:days days, :exception (.getMessage e)}})))


(defn load-draft-content
  "Load content from draft file"
  [draft-path]
  (try (if (fs/exists? draft-path)
         {:success true,
          :result {:content (slurp draft-path), :draft-path draft-path}}
         {:error :file-not-found,
          :message "Draft file does not exist",
          :context {:draft-path draft-path}})
       (catch Exception e
         {:error :load-failed,
          :message "Failed to load draft content",
          :context {:draft-path draft-path, :exception (.getMessage e)}})))


(defn create-ticket-template
  "Create template for editor"
  ([] (create-ticket-template nil nil))
  ([title] (create-ticket-template title nil))
  ([title description]
   (str (if title (str title "\n") "")
        "\n"
        "# Enter ticket title on first line"
        (when title " (or edit above)")
        "\n"
        (if description (str description "\n\n") "")
        "# Enter description "
        (if description "above (or edit above)" "below")
        " (markdown supported)\n"
        "# Lines starting with # are comments (ignored)\n"
        "# Save and exit to create ticket, exit without saving to cancel\n")))


(defn parse-editor-content
  "Parse content from editor into title and description"
  [content]
  (let [lines (str/split-lines content)
        non-comment-lines (filter #(not (str/starts-with? % "#")) lines)
        non-empty-lines (filter #(not (str/blank? %)) non-comment-lines)]
    (when (seq non-empty-lines)
      {:title (first non-empty-lines),
       :description (str/join "\n" (rest non-empty-lines))})))


(defn parse-draft-content
  "Parse content from draft file into title and description"
  [content]
  (when content (parse-editor-content content)))


(defn open-ticket-editor
  "Open editor for ticket creation, return parsed content with draft path."
  ([] (open-ticket-editor nil nil))
  ([title] (open-ticket-editor title nil))
  ([title description]
   (let [temp-file (fs/create-temp-file {:prefix "crucible-ticket-",
                                         :suffix ".md"})
         template (create-ticket-template title description)]
     (try (spit (str temp-file) template)
          (let [editor-result (launch-editor temp-file)]
            (cond (:error editor-result)
                    ;; Editor launch failed
                    (do (fs/delete temp-file) editor-result)
                  (= 0 (get-in editor-result [:result :exit-code]))
                    ;; Editor exited successfully
                    (let [content (slurp (str temp-file))
                          parsed (parse-editor-content content)]
                      (fs/delete temp-file)
                      (if parsed
                        ;; Save draft copy for recovery and add draft path
                        ;; to result
                        (let [draft-path (save-draft-copy content)]
                          {:success true,
                           :result (assoc parsed :draft-path draft-path)})
                        ;; No valid content parsed
                        {:error :no-content,
                         :message "No valid ticket content found",
                         :context {:template template}}))
                  :else
                    ;; Non-zero exit code, user cancelled
                    (do (fs/delete temp-file)
                        {:error :user-cancelled,
                         :message "User cancelled ticket creation",
                         :context {:exit-code (get-in editor-result
                                                      [:result :exit-code])}})))
          (catch Exception e
            (when (fs/exists? temp-file) (fs/delete temp-file))
            {:error :editor-operation-failed,
             :message "Failed during editor operation",
             :context {:exception (.getMessage e)}})))))


(defn review-enhanced-ticket
  "Open editor to review AI-enhanced ticket content. Returns structured result."
  [{:keys [title description]}]
  (let [temp-file (fs/create-temp-file {:prefix "crucible-review-",
                                        :suffix ".md"})
        content (str "# AI-Enhanced Ticket Review\n\n"
                     "# The ticket below has been enhanced by AI.\n"
                       "# Review and make any final edits.\n"
                     "# Save and exit (exit code 0) to create the ticket.\n"
                       "# Exit without saving (exit code non-0) to cancel.\n\n"
                     "---\n\n" title
                     "\n\n" (if (str/blank? description)
                              "<!-- No description -->"
                              description))]
    (try (spit (str temp-file) content)
         (let [editor-result (launch-editor temp-file)]
           (cond (:error editor-result)
                   ;; Editor launch failed
                   (do (fs/delete temp-file) editor-result)
                 (= 0 (get-in editor-result [:result :exit-code]))
                   ;; Editor exited successfully
                   (let [edited-content (slurp (str temp-file))
                         parsed (parse-editor-content edited-content)]
                     (fs/delete temp-file)
                     (if parsed
                       {:success true, :result parsed}
                       {:error :no-content,
                        :message "No valid content after review",
                        :context {:original-title title,
                                  :original-description description}}))
                 :else
                   ;; Non-zero exit code, user cancelled
                   (do (fs/delete temp-file)
                       {:error :review-cancelled,
                        :message "User cancelled AI review",
                        :context {:exit-code (get-in editor-result
                                                     [:result :exit-code])}})))
         (catch Exception e
           (when (fs/exists? temp-file) (fs/delete temp-file))
           {:error :review-operation-failed,
            :message "Failed during AI review operation",
            :context {:exception (.getMessage e)}}))))
