(ns lib.ticket-editor
  "Ticket creation and editing functionality for Crucible"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str])
  (:import
   (java.time
    LocalDateTime)
   (java.time.format
    DateTimeFormatter)))

(defn launch-editor
  "Launch editor with the given file path. Returns the editor's exit code."
  [file-path]
  (let [editor (System/getenv "EDITOR")]
    (if editor
      (let [result @(process/process [editor (str file-path)] {:inherit true})]
        (:exit result))
      (do
        (println "Error: EDITOR environment variable not set")
        (System/exit 1)))))

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
  (when (and draft-path (fs/exists? draft-path))
    (fs/delete draft-path)))

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
                    {:path (str path)
                     :filename filename
                     :size size
                     :modified modified})))
           (sort-by :modified)
           reverse)
      [])))

(defn clean-old-drafts
  "Remove draft files older than specified days (default 7)"
  [& {:keys [days] :or {days 7}}]
  (let [draft-dir (ensure-draft-directory)
        cutoff-time (-> (LocalDateTime/now)
                        (.minusDays days)
                        (.toInstant (java.time.ZoneOffset/UTC)))]
    (when (fs/exists? draft-dir)
      (doseq [file (fs/list-dir draft-dir)]
        (when (str/ends-with? (str file) ".md")
          (let [modified-time (fs/last-modified-time file)]
            (when (.isBefore (.toInstant modified-time) cutoff-time)
              (fs/delete file))))))))

(defn load-draft-content
  "Load content from draft file"
  [draft-path]
  (when (fs/exists? draft-path)
    (slurp draft-path)))

(defn create-ticket-template
  "Create template for editor"
  ([]
   (create-ticket-template nil nil))
  ([title]
   (create-ticket-template title nil))
  ([title description]
   (str (if title (str title "\n") "")
        "\n"
        "# Enter ticket title on first line" (when title " (or edit above)")
        "\n"
        (if description
          (str description "\n\n")
          "")
        "# Enter description " (if description "above (or edit above)" "below") " (markdown supported)\n"
        "# Lines starting with # are comments (ignored)\n"
        "# Save and exit to create ticket, exit without saving to cancel\n")))

(defn parse-editor-content
  "Parse content from editor into title and description"
  [content]
  (let [lines (str/split-lines content)
        non-comment-lines (filter #(not (str/starts-with? % "#")) lines)
        non-empty-lines (filter #(not (str/blank? %)) non-comment-lines)]
    (when (seq non-empty-lines)
      {:title (first non-empty-lines)
       :description (str/join "\n" (rest non-empty-lines))})))

(defn parse-draft-content
  "Parse content from draft file into title and description"
  [content]
  (when content
    (parse-editor-content content)))

(defn open-ticket-editor
  "Open editor for ticket creation, return parsed content with draft path.
   Returns nil if editor exits with non-zero code."
  ([]
   (open-ticket-editor nil nil))
  ([title]
   (open-ticket-editor title nil))
  ([title description]
   (let [temp-file (fs/create-temp-file {:prefix "crucible-ticket-"
                                         :suffix ".md"})
         template (create-ticket-template title description)]
     (try
       (spit (str temp-file) template)
       (let [exit-code (launch-editor temp-file)]
         (if (= 0 exit-code)
           (let [content (slurp (str temp-file))
                 parsed (parse-editor-content content)]
             (fs/delete temp-file)
             (if parsed
               ;; Save draft copy for recovery and add draft path to result
               (let [draft-path (save-draft-copy content)]
                 (assoc parsed :draft-path draft-path))
               ;; No valid content, don't save draft
               nil))
           ;; Non-zero exit code, user cancelled
           (do
             (fs/delete temp-file)
             nil)))
       (catch Exception e
         (when (fs/exists? temp-file)
           (fs/delete temp-file))
         (throw e))))))

(defn review-enhanced-ticket
  "Open editor to review AI-enhanced ticket content.
   Returns the parsed content if user approves (exit 0), nil if cancelled."
  [{:keys [title description]}]
  (let [temp-file (fs/create-temp-file {:prefix "crucible-review-"
                                        :suffix ".md"})
        content (str "# AI-Enhanced Ticket Review\n\n"
                     "# The ticket below has been enhanced by AI.\n"
                     "# Review and make any final edits.\n"
                     "# Save and exit (exit code 0) to create the ticket.\n"
                     "# Exit without saving (exit code non-0) to cancel.\n\n"
                     "---\n\n"
                     title "\n\n"
                     (if (str/blank? description)
                       "<!-- No description -->"
                       description))]
    (try
      (spit (str temp-file) content)
      (let [exit-code (launch-editor temp-file)]
        (if (= 0 exit-code)
          (let [edited-content (slurp (str temp-file))
                parsed (parse-editor-content edited-content)]
            (fs/delete temp-file)
            parsed)
          ;; Non-zero exit code, user cancelled
          (do
            (fs/delete temp-file)
            nil)))
      (catch Exception e
        (when (fs/exists? temp-file)
          (fs/delete temp-file))
        (throw e)))))