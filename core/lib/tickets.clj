(ns lib.tickets
  (:require
    [babashka.fs :as fs]
    [clojure.string :as str]
    [lib.jira :as jira]))


(defn ensure-ticket-directory
  "Create ticket directory structure"
  [tickets-dir project-key ticket-id]
  (let [ticket-path (fs/path tickets-dir project-key ticket-id)]
    (when-not (fs/exists? ticket-path)
      (fs/create-dirs ticket-path)
      (fs/create-dirs (fs/path ticket-path "scripts"))
      (fs/create-dirs (fs/path ticket-path "artifacts")))
    ticket-path))


(defn format-ticket-readme
  "Generate README.md content for a ticket"
  [{:keys [key fields]}]
  (let [{:keys [summary description status assignee reporter priority issuetype created updated]} fields
        description-text (or description "No description provided.")]
    (str/join "\n"
              [(str "# " key ": " summary)
               ""
               "## Ticket Information"
               ""
               (str "- **Type**: " (get issuetype :name "Unknown"))
               (str "- **Status**: " (get status :name "Unknown"))
               (str "- **Priority**: " (get priority :name "Unknown"))
               (str "- **Assignee**: " (or (get assignee :displayName) "Unassigned"))
               (str "- **Reporter**: " (get reporter :displayName "Unknown"))
               (str "- **Created**: " created)
               (str "- **Updated**: " updated)
               ""
               "## Description"
               ""
               description-text
               ""
               "## Work Log"
               ""
               "### " (str (java.time.LocalDate/now))
               ""
               "- Started working on this ticket"
               ""
               "## Notes"
               ""
               "_Add your notes here_"
               ""
               "## Scripts"
               ""
               "Scripts related to this ticket are stored in the `scripts/` directory."
               ""
               "## Artifacts"
               ""
               "Output files, logs, and other artifacts are stored in the `artifacts/` directory."
               ""])))


(defn create-notes-file
  "Create initial notes.md file"
  [ticket-path ticket-key]
  (let [notes-path (fs/path ticket-path "notes.md")
        date-str (str (java.time.LocalDate/now))]
    (when-not (fs/exists? notes-path)
      (spit (str notes-path)
            (str/join "\n"
                      [(str "# " ticket-key " - Work Notes")
                       ""
                       (str "## " date-str)
                       ""
                       "### Initial Setup"
                       ""
                       "- [ ] Review ticket requirements"
                       "- [ ] Set up development environment"
                       "- [ ] Create implementation plan"
                       ""
                       "### Investigation"
                       ""
                       "_Document your findings here_"
                       ""
                       "### Implementation"
                       ""
                       "_Track implementation progress_"
                       ""
                       "### Testing"
                       ""
                       "_Document test scenarios and results_"
                       ""
                       ""])))))


(defn setup-ticket-workspace
  "Set up complete ticket workspace with Jira data"
  [config ticket-id]
  (let [jira-config (:jira config)
        tickets-dir (get-in config [:workspace :tickets-dir])
        ticket-result (jira/get-ticket jira-config ticket-id)]
    (if (:success ticket-result)
      (let [ticket-data (:data ticket-result)
            parsed-id (jira/parse-ticket-id ticket-id)
            ticket-path (ensure-ticket-directory tickets-dir
                                                 (:project-key parsed-id)
                                                 (:full-id parsed-id))
            readme-path (fs/path ticket-path "README.md")]
        ;; Create or update README.md
        (spit (str readme-path) (format-ticket-readme ticket-data))
        ;; Create notes.md if it doesn't exist
        (create-notes-file ticket-path (:key ticket-data))
        {:success true
         :path ticket-path
         :ticket ticket-data})
      {:success false
       :error (:error ticket-result)})))
