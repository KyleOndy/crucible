(ns lib.jira.sprints
  "Jira sprint and board management operations"
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [lib.jira.auth :as jira-auth]))


(defn ^:private get-boards-for-project
  "Get all boards for a project (private - use find-sprints instead)"
  [jira-config project-key]
  (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
        auth-header (jira-auth/make-auth-header (:username jira-config)
                                                (:api-token jira-config))
        response (http/get (str agile-url "/board?projectKeyOrId=" project-key)
                           {:headers {"Authorization" auth-header,
                                      "Accept" "application/json"},
                            :throw false})]
    (when (= 200 (:status response))
      (let [body (json/parse-string (:body response) true)] (:values body)))))


(defn get-project-active-sprints
  "Find active sprints for a project"
  [jira-config project-key]
  (when-let [boards (get-boards-for-project jira-config project-key)]
    (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
          auth-header (jira-auth/make-auth-header (:username jira-config)
                                                  (:api-token jira-config))
          sprint-results
            (for [board boards]
              (let [response (http/get (str agile-url
                                            "/board/"
                                            (:id board)
                                            "/sprint?state=active")
                                       {:headers {"Authorization" auth-header,
                                                  "Accept" "application/json"},
                                        :throw false})]
                (when (= 200 (:status response))
                  (let [body (json/parse-string (:body response) true)]
                    (:values body)))))
          all-sprints (->> sprint-results
                           (filter some?)
                           (apply concat)
                           (group-by :id)
                           (vals)
                           (map first))]
      {:sprints all-sprints, :board-count (count boards)})))


(defn find-sprints
  "Find active sprints for a project with optional fallback board IDs"
  [jira-config {:keys [project-key fallback-board-ids]}]
  (or (get-project-active-sprints jira-config project-key)
      (when fallback-board-ids
        (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
              auth-header (jira-auth/make-auth-header (:username jira-config)
                                                      (:api-token jira-config))
              sprint-results
                (for [board-id fallback-board-ids]
                  (let [response (http/get (str agile-url
                                                "/board/"
                                                board-id
                                                "/sprint?state=active")
                                           {:headers
                                              {"Authorization" auth-header,
                                               "Accept" "application/json"},
                                            :throw false})]
                    (when (= 200 (:status response))
                      (let [body (json/parse-string (:body response) true)]
                        (:values body)))))
              all-sprints (->> sprint-results
                               (filter some?)
                               (apply concat)
                               (group-by :id)
                               (vals)
                               (map first))]
          (when (seq all-sprints)
            {:sprints all-sprints, :board-count (count fallback-board-ids)})))))


(defn add-issue-to-sprint
  "Add an issue to a sprint"
  [jira-config sprint-id issue-key]
  (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
        auth-header (jira-auth/make-auth-header (:username jira-config)
                                                (:api-token jira-config))
        body-data {:issues [issue-key]}
        response (http/post (str agile-url "/sprint/" sprint-id "/issue")
                            {:headers {"Authorization" auth-header,
                                       "Accept" "application/json",
                                       "Content-Type" "application/json"},
                             :body (json/generate-string body-data),
                             :throw false})]
    (= 204 (:status response))))


(defn get-current-sprint-info
  "Get current sprint information for the user"
  [jira-config]
  (try
    ;; Use existing sprint detection logic
    (let [project-key (get jira-config :default-project)
          fallback-boards (get jira-config :fallback-board-ids)
          ;; Get status filter config - exclude Done tickets by default
          exclude-statuses (get jira-config :sprint-exclude-statuses ["Done"])
          show-done-tickets (get jira-config :sprint-show-done-tickets false)]
      (when project-key
        (let [sprint-data (find-sprints jira-config
                                        {:project-key project-key,
                                         :fallback-board-ids fallback-boards})
              sprints (get sprint-data :sprints [])
              active-sprint (first sprints)]
          (when active-sprint
            (let [sprint-name (:name active-sprint)
                  sprint-id (:id active-sprint)
                  end-date-str (:endDate active-sprint)
                  ;; Calculate days remaining (simplified - could be more
                  ;; precise)
                  days-remaining
                    (if end-date-str
                      (try
                        ;; Parse ISO date and calculate days
                        (let [end-date (java.time.LocalDate/parse
                                         (subs end-date-str 0 10))
                              today (java.time.LocalDate/now)
                              days (.until today
                                           end-date
                                           java.time.temporal.ChronoUnit/DAYS)]
                          (max 0 days))
                        (catch Exception _ "unknown"))
                      "unknown")
                  ;; Build JQL with optional status filter. Get assigned
                  ;; tickets in current sprint - use sprint ID instead of
                  ;; name
                  status-filter
                    (if (and (not show-done-tickets) (seq exclude-statuses))
                      (str " AND status NOT IN ("
                           (str/join ", "
                                     (map #(str "\"" % "\"") exclude-statuses))
                           ")")
                      "")
                  jql (str "assignee = currentUser() "
                           "AND sprint = "
                           sprint-id
                           status-filter
                           " ORDER BY priority DESC")
                  assigned-tickets
                    (try
                      (when (:debug jira-config)
                        (binding [*out* *err*]
                          (let
                            [timestamp
                               (.format
                                 (java.time.LocalDateTime/now)
                                 (java.time.format.DateTimeFormatter/ofPattern
                                   "yyyy-MM-dd HH:mm:ss"))]
                            (println (str "[" timestamp
                                          "] [JIRA-DEBUG] Executing JQL: "
                                            jql)))))
                      (let [response (jira-auth/jira-request
                                       jira-config
                                       :post
                                       "/search/jql"
                                       {:body (json/generate-string
                                                {:jql jql,
                                                 :fields ["key" "summary"
                                                          "status"],
                                                 :maxResults 10})})
                            issues (get-in response [:body :issues] [])]
                        (when (:debug jira-config)
                          (binding [*out* *err*]
                            (let
                              [timestamp
                                 (.format
                                   (java.time.LocalDateTime/now)
                                   (java.time.format.DateTimeFormatter/ofPattern
                                     "yyyy-MM-dd HH:mm:ss"))]
                              (println (str
                                         "[" timestamp
                                         "] [JIRA-DEBUG] Raw issues from API: "
                                           (count issues)))
                              (when (seq issues)
                                (println
                                  (str "[" timestamp
                                       "] [JIRA-DEBUG] First issue structure: "
                                         (keys (first issues))))
                                (println
                                  (str "[" timestamp
                                       "] [JIRA-DEBUG] First issue fields: "
                                         (keys (:fields (first issues)))))))))
                        (->> issues
                             (map (fn [issue]
                                    (str (:key issue)
                                         ": "
                                         (get-in issue [:fields :summary])
                                         " ["
                                         (or (get-in issue
                                                     [:fields :status :name])
                                             (get-in issue [:fields :status]))
                                         "]")))))
                      (catch Exception e
                        (println (str
                                   "Warning: Failed to fetch sprint tickets: "
                                   (.getMessage e)))
                        (println (str "JQL Query was: " jql))
                        []))]
              {:name sprint-name,
               :id sprint-id,
               :days-remaining days-remaining,
               :assigned-tickets assigned-tickets,
               :jql jql})))))
    (catch Exception e
      (println (str "Error fetching sprint info: " (.getMessage e)))
      nil)))
