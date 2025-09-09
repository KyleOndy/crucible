(ns lib.jira
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [lib.adf :as adf]
   [lib.config :as config]))

;; Core utility functions

(defn make-auth-header
  "Create Basic Auth header from username and API token"
  [username api-token]
  (let [credentials (str username ":" api-token)
        encoded (.encodeToString (java.util.Base64/getEncoder) (.getBytes credentials))]
    (str "Basic " encoded)))

(defn jira-request
  "Make an authenticated request to Jira API"
  [{:keys [base-url username api-token debug] :as jira-config} method path & [opts]]
  (let [url (str base-url "/rest/api/3" path)
        auth-header (make-auth-header username api-token)
        default-opts {:headers {"Authorization" auth-header
                                "Accept" "application/json"
                                "Content-Type" "application/json"}
                      :throw false}
        request-opts (merge default-opts opts)]

    ;; Debug logging for outgoing request
    (when debug
      (binding [*out* *err*]
        (let [timestamp (.format (java.time.LocalDateTime/now)
                                 (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))]
          (println (str "[" timestamp "] [JIRA-DEBUG] HTTP " (str/upper-case (name method)) " " url))
          (when-let [query-params (:query-params request-opts)]
            (println (str "[" timestamp "] [JIRA-DEBUG] Query params: " query-params)))
          (when-let [body (:body request-opts)]
            (println (str "[" timestamp "] [JIRA-DEBUG] Request body: " body))))))

    (let [response (case method
                     :get (http/get url request-opts)
                     :post (http/post url request-opts)
                     :put (http/put url request-opts)
                     :delete (http/delete url request-opts))
          parsed-response (update response :body #(when % (json/parse-string % true)))]

      ;; Debug logging for response
      (when debug
        (binding [*out* *err*]
          (let [timestamp (.format (java.time.LocalDateTime/now)
                                   (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))]
            (println (str "[" timestamp "] [JIRA-DEBUG] Response status: " (:status response)))
            (when-let [body (:body parsed-response)]
              (if (map? body)
                (do
                  (println (str "[" timestamp "] [JIRA-DEBUG] Response body keys: " (keys body)))
                  (when (:issues body)
                    (println (str "[" timestamp "] [JIRA-DEBUG] Issues count: " (count (:issues body)))))
                  (when (< (count (str body)) 1000)
                    (println (str "[" timestamp "] [JIRA-DEBUG] Response body: " body))))
                (println (str "[" timestamp "] [JIRA-DEBUG] Response body: " body)))))))

      parsed-response)))

(defn test-connection
  "Test Jira connection and authentication"
  [jira-config]
  (let [response (jira-request jira-config :get "/myself")]
    (if (= 200 (:status response))
      {:success true
       :message (str "Successfully connected as: " (get-in response [:body :displayName]))}
      {:success false
       :message (str "Connection failed: " (or (get-in response [:body :message])
                                               (:reason-phrase response)))
       :status (:status response)})))

(defn get-user-info
  "Get current user information"
  [jira-config]
  (let [response (jira-request jira-config :get "/myself")]
    (when (= 200 (:status response))
      (:body response))))

(defn parse-ticket-id
  "Parse and validate a ticket ID (e.g., PROJ-1234)"
  [ticket-id]
  (when ticket-id
    (let [pattern #"^([A-Z][A-Z0-9]*)-(\d+)$"
          matcher (re-matcher pattern (str/upper-case ticket-id))]
      (when (.matches matcher)
        {:project-key (.group matcher 1)
         :issue-number (.group matcher 2)
         :full-id (str (.group matcher 1) "-" (.group matcher 2))}))))

(defn get-ticket
  "Fetch ticket data from Jira"
  [jira-config ticket-id]
  (if-let [parsed-id (parse-ticket-id ticket-id)]
    (let [response (jira-request jira-config :get (str "/issue/" (:full-id parsed-id)))]
      (cond
        (= 200 (:status response))
        {:success true
         :data (-> response
                   :body
                   (select-keys [:key :fields])
                   (update :fields #(select-keys % [:summary :description :status :assignee :reporter
                                                    :priority :issuetype :created :updated])))}

        (= 404 (:status response))
        {:success false
         :error (str "Ticket " (:full-id parsed-id) " not found")
         :status (:status response)}

        (= 401 (:status response))
        {:success false
         :error "Authentication failed. Check your Jira credentials."
         :status (:status response)}

        :else
        {:success false
         :error (str "Failed to fetch ticket: " (get-in response [:body :errorMessages]))
         :status (:status response)}))
    {:success false
     :error (str "Invalid ticket ID format: " ticket-id ". Expected format: PROJ-1234")}))

(defn get-ticket-full
  "Fetch complete ticket data from Jira including all custom fields"
  [jira-config ticket-id]
  (if-let [parsed-id (parse-ticket-id ticket-id)]
    (let [response (jira-request jira-config :get (str "/issue/" (:full-id parsed-id)))]
      (cond
        (= 200 (:status response))
        {:success true
         :data (:body response)} ; Return the full response without filtering

        (= 404 (:status response))
        {:success false
         :error (str "Ticket " (:full-id parsed-id) " not found")
         :status (:status response)}

        (= 401 (:status response))
        {:success false
         :error "Authentication failed. Check your Jira credentials."
         :status (:status response)}

        :else
        {:success false
         :error (str "Failed to fetch ticket: " (get-in response [:body :errorMessages]))
         :status (:status response)}))
    {:success false
     :error (str "Invalid ticket ID format: " ticket-id ". Expected format: PROJ-1234")}))

(defn format-ticket-summary
  "Format ticket data for display"
  [{:keys [key fields]}]
  (let [{:keys [summary status assignee reporter priority issuetype]} fields]
    {:key key
     :summary summary
     :status (get-in status [:name])
     :assignee (get-in assignee [:displayName] "Unassigned")
     :reporter (get-in reporter [:displayName])
     :priority (get-in priority [:name])
     :type (get-in issuetype [:name])}))

(defn ^:private get-boards-for-project
  "Get all boards for a project (private - use find-sprints instead)"
  [jira-config project-key]
  (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
        auth-header (make-auth-header (:username jira-config) (:api-token jira-config))
        response (http/get
                  (str agile-url "/board?projectKeyOrId=" project-key)
                  {:headers {"Authorization" auth-header
                             "Accept" "application/json"}
                   :throw false})]
    (when (= 200 (:status response))
      (let [body (json/parse-string (:body response) true)]
        (:values body)))))

(defn get-project-active-sprints
  "Find active sprints for a project"
  [jira-config project-key]
  (when-let [boards (get-boards-for-project jira-config project-key)]
    (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
          auth-header (make-auth-header (:username jira-config) (:api-token jira-config))
          sprint-results (for [board boards]
                           (let [response (http/get
                                           (str agile-url "/board/" (:id board) "/sprint?state=active")
                                           {:headers {"Authorization" auth-header
                                                      "Accept" "application/json"}
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
      {:sprints all-sprints
       :board-count (count boards)})))

(defn find-sprints
  "Find active sprints for a project with optional fallback board IDs"
  [jira-config {:keys [project-key fallback-board-ids]}]
  (or (get-project-active-sprints jira-config project-key)
      (when fallback-board-ids
        (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
              auth-header (make-auth-header (:username jira-config) (:api-token jira-config))
              sprint-results (for [board-id fallback-board-ids]
                               (let [response (http/get
                                               (str agile-url "/board/" board-id "/sprint?state=active")
                                               {:headers {"Authorization" auth-header
                                                          "Accept" "application/json"}
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
            {:sprints all-sprints
             :board-count (count fallback-board-ids)})))))

(defn add-issue-to-sprint
  "Add an issue to a sprint"
  [jira-config sprint-id issue-key]
  (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
        auth-header (make-auth-header (:username jira-config) (:api-token jira-config))
        body-data {:issues [issue-key]}
        response (http/post
                  (str agile-url "/sprint/" sprint-id "/issue")
                  {:headers {"Authorization" auth-header
                             "Accept" "application/json"
                             "Content-Type" "application/json"}
                   :body (json/generate-string body-data)
                   :throw false})]
    (= 204 (:status response))))

;; ADF conversion - delegated to lib.adf
(def text->adf adf/text->adf)

;; Legacy functions - these will be removed after full migration to lib.adf
(defn create-paragraph
  "Create ADF paragraph node from text"
  [text]
  (when-not (str/blank? text)
    {:type "paragraph"
     :content (adf/parse-inline-formatting text)}))

;; Remove old ADF functions - use lib.adf instead

;; Custom field formatting helpers

(defn format-custom-field-value
  "Format a custom field value based on its type and structure.
  
  Handles various complex field types:
  - Simple values: strings, numbers, booleans
  - Single select: {:id \"10001\"} or {:value \"Option1\"}
  - Multi-select: [{:id \"10001\"} {:id \"10002\"}]
  - Cascading select: {:id \"10001\" :child {:id \"10011\"}}
  - User picker: {:accountId \"557058:...\"} or {:name \"username\"}
  - Version picker: {:id \"10400\"} or {:name \"2.0.0\"}
  - Labels: [\"label1\" \"label2\"]
  
  The function attempts to detect the field type based on value structure."
  [value]
  (cond
    ;; Simple scalar values pass through
    (or (string? value) (number? value) (boolean? value))
    value

    ;; Array of strings (labels, text fields)
    (and (vector? value) (every? string? value))
    value

    ;; Single select with ID
    (and (map? value) (:id value) (not (:child value)))
    {:id (:id value)}

    ;; Cascading select with parent and child
    (and (map? value) (:id value) (:child value))
    {:id (:id value)
     :child (if (map? (:child value))
              {:id (:id (:child value))}
              (:child value))}

    ;; User picker with accountId
    (and (map? value) (:accountId value))
    {:accountId (:accountId value)}

    ;; User picker with name (legacy)
    (and (map? value) (:name value) (not (:id value)))
    {:name (:name value)}

    ;; Version or component with just name
    (and (map? value) (:name value) (not (:accountId value)))
    (if (:id value)
      {:id (:id value)} ; Prefer ID if available
      {:name (:name value)})

    ;; Multi-select array of objects
    (and (vector? value) (every? map? value))
    (mapv format-custom-field-value value)

    ;; Default: return as-is
    :else
    value))

(defn prepare-custom-fields
  "Prepare custom fields for Jira API submission.
  
  Takes a map of custom field definitions and formats them appropriately.
  Custom fields can be specified with or without the 'customfield_' prefix.
  
  Examples:
    {\"customfield_10001\" \"Simple text value\"
     \"10002\" {:id \"10100\"}  ; Will be prefixed to customfield_10002
     :customfield_10003 [{:id \"10200\"} {:id \"10201\"}]
     \"epic-link\" \"PROJ-123\"  ; Named field mapping (requires field-mappings)}"
  ([custom-fields]
   (prepare-custom-fields custom-fields {}))
  ([custom-fields field-mappings]
   (when custom-fields
     (reduce-kv
      (fn [acc k v]
        (let [field-key (cond
                           ;; Already has customfield_ prefix
                          (and (string? k) (str/starts-with? k "customfield_"))
                          k

                           ;; Keyword with customfield_ prefix
                          (and (keyword? k) (str/starts-with? (name k) "customfield_"))
                          (name k)

                           ;; Numeric ID without prefix
                          (and (string? k) (re-matches #"\d+" k))
                          (str "customfield_" k)

                           ;; Check field mappings for named fields
                          (contains? field-mappings k)
                          (get field-mappings k)

                          (contains? field-mappings (keyword k))
                          (get field-mappings (keyword k))

                           ;; Default: convert to string
                          :else
                          (str k))
              formatted-value (format-custom-field-value v)]
          (assoc acc field-key formatted-value)))
      {}
      custom-fields))))

(defn get-field-metadata
  "Get metadata for fields including custom fields.
  Useful for discovering custom field IDs and their allowed values.
  
  Options:
    - project-key: Filter fields by project
    - issue-type: Filter fields by issue type
    - include-fields: Vector of field IDs to specifically include"
  [jira-config {:keys [project-key issue-type include-fields]}]
  (let [params (cond-> {}
                 project-key (assoc :projectKeys project-key)
                 issue-type (assoc :issuetypeIds issue-type)
                 include-fields (assoc :expand "projects.issuetypes.fields"))
        query-string (when (seq params)
                       (str "?" (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params))))
        response (jira-request jira-config :get (str "/field" query-string))]
    (when (= 200 (:status response))
      (:body response))))

(defn get-create-metadata
  "Get metadata for creating issues, including custom field definitions and allowed values.
  This is useful for discovering what fields are required/optional and their constraints."
  [jira-config project-key issue-type-name]
  (let [params {:projectKeys project-key
                :issuetypeNames issue-type-name
                :expand "projects.issuetypes.fields"}
        query-string (str "?" (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params)))
        response (jira-request jira-config :get (str "/issue/createmeta" query-string))]
    (when (= 200 (:status response))
      (let [body (:body response)
            project (first (:projects body))
            issue-type (first (:issuetypes project))]
        {:project project
         :issue-type issue-type
         :fields (:fields issue-type)
         :custom-fields (filter #(str/starts-with? (key %) "customfield_") (:fields issue-type))}))))

(defn create-issue
  "Create a new Jira issue with support for complex custom fields.
  
  issue-data should contain:
    - fields: Standard fields and custom fields
      - project: {:key \"PROJ\"} or {:id \"10000\"}
      - issuetype: {:name \"Task\"} or {:id \"10001\"}
      - summary: Issue title
      - description: Issue description (can be ADF or plain text)
      - customfield_XXXXX: Custom field values (automatically formatted)
    
  Custom fields are automatically formatted based on their structure.
  Use prepare-custom-fields for more control over formatting."
  [jira-config issue-data]
  (let [;; Auto-format custom fields if present
        fields (:fields issue-data)
        formatted-fields (if fields
                           (reduce-kv
                            (fn [acc k v]
                              (if (and (string? k) (str/starts-with? k "customfield_"))
                                (assoc acc k (format-custom-field-value v))
                                (assoc acc k v)))
                            {}
                            fields)
                           fields)
        formatted-issue-data (assoc issue-data :fields formatted-fields)
        response (jira-request jira-config :post "/issue" {:body (json/generate-string formatted-issue-data)})]
    (cond
      (= 201 (:status response))
      {:success true
       :data (:body response)
       :key (get-in response [:body :key])
       :id (get-in response [:body :id])}

      (= 400 (:status response))
      {:success false
       :error (str "Invalid issue data: " (or (get-in response [:body :errors])
                                              (get-in response [:body :errorMessages])
                                              "Unknown validation error"))
       :details (:body response)
       :status (:status response)}

      (= 401 (:status response))
      {:success false
       :error "Authentication failed. Check your Jira credentials."
       :status (:status response)}

      :else
      {:success false
       :error (str "Failed to create issue: " (or (get-in response [:body :errorMessages])
                                                  (get-in response [:body :errors])
                                                  (:reason-phrase response)))
       :details (:body response)
       :status (:status response)})))

;; High-level functions

(defn format-time-ago
  "Format an instant as a relative time string"
  [instant]
  (let [now (java.time.Instant/now)
        duration (java.time.Duration/between instant now)
        hours (.toHours duration)
        days (.toDays duration)]
    (cond
      (< hours 1) "less than 1 hour ago"
      (< hours 24) (str hours " hour" (when (> hours 1) "s") " ago")
      (< days 7) (str days " day" (when (> days 1) "s") " ago")
      :else (str (.format instant (java.time.format.DateTimeFormatter/ofPattern "MMM d"))))))

(defn get-my-recent-activity
  "Get meaningful recent activity on issues the current user is involved with"
  [jira-config since-date activity-options]
  (try
    (let [user-info (get-user-info jira-config)
          current-user-account-id (:accountId user-info)
          current-user-display-name (:displayName user-info)

          ;; Extract configuration options with defaults
          activity-days (get activity-options :activity-days 5)
          exclude-own-activity (get activity-options :exclude-own-activity true)
          allowed-activity-types (set (get activity-options :activity-types ["status" "assignee" "priority" "resolution"]))
          max-activities (get activity-options :max-activities 10)

          ;; Create JQL query for tickets updated recently that involve the user
          jql (str "assignee = currentUser() OR reporter = currentUser() "
                   "AND updated >= -" activity-days "d "
                   "ORDER BY updated DESC")

          ;; Get issues with changelog data
          response (jira-request jira-config :post "/search/jql"
                                 {:body (json/generate-string {:jql jql
                                                               :fields ["key" "summary" "status" "updated" "assignee"]
                                                               :expand ["changelog"]
                                                               :maxResults 50})})

          issues (get-in response [:body :issues] [])]

      ;; Process each issue's changelog to extract meaningful recent activities
      (->> issues
           (mapcat (fn [issue]
                     (let [issue-key (:key issue)
                           summary (get-in issue [:fields :summary])
                           changelog (get-in issue [:changelog :histories] [])]

                       ;; Process changelog entries from the last few days
                       (->> changelog
                            (filter (fn [history]
                                      (let [created-str (:created history)
                                            created-instant (java.time.Instant/parse created-str)
                                            cutoff-instant (.minus (java.time.Instant/now)
                                                                   (java.time.Duration/ofDays activity-days))]
                                        (.isAfter created-instant cutoff-instant))))
                            ;; Filter out activities by the current user if configured
                            (filter (fn [history]
                                      (if exclude-own-activity
                                        (not= (get-in history [:author :accountId]) current-user-account-id)
                                        true)))
                            ;; Convert to activity descriptions
                            (map (fn [history]
                                   (let [author-name (get-in history [:author :displayName])
                                         created-instant (java.time.Instant/parse (:created history))
                                         time-ago (format-time-ago created-instant)
                                         items (:items history)]

                                     ;; Extract meaningful changes from the items
                                     (if (seq items)
                                       (let [filtered-items (->> items
                                                                 (filter (fn [item]
                                                                           (contains? allowed-activity-types (:field item)))))
                                             change-descriptions
                                             (->> filtered-items
                                                  (map (fn [item]
                                                         (let [field (:field item)
                                                               from-str (:fromString item)
                                                               to-str (:toString item)]
                                                           (case field
                                                             "status" (str "Status: " from-str " → " to-str)
                                                             "assignee" (if to-str
                                                                          (str "Assigned to " to-str)
                                                                          "Unassigned")
                                                             "summary" "Updated summary"
                                                             "priority" (str "Priority: " from-str " → " to-str)
                                                             "resolution" (if to-str
                                                                            (str "Resolved as " to-str)
                                                                            "Reopened")
                                                             (str "Updated " field)))))
                                                  (str/join ", "))]
                                         (when (seq filtered-items)
                                           (str issue-key ": " change-descriptions " (" author-name ", " time-ago ")")))
                                       ;; If no items, might be a comment (though we don't have comment data in changelog)
                                       nil))))))))
           ;; Remove empty results and limit output
           (filter some?)
           (take max-activities)
           vec))

    (catch Exception e
      (println (str "Error fetching Jira activity: " (.getMessage e)))
      [])))

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
                                        {:project-key project-key
                                         :fallback-board-ids fallback-boards})
              sprints (get sprint-data :sprints [])
              active-sprint (first sprints)]

          (when active-sprint
            (let [sprint-name (:name active-sprint)
                  sprint-id (:id active-sprint)
                  end-date-str (:endDate active-sprint)

                  ;; Calculate days remaining (simplified - could be more precise)
                  days-remaining (if end-date-str
                                   (try
                                     ;; Parse ISO date and calculate days
                                     (let [end-date (java.time.LocalDate/parse (subs end-date-str 0 10))
                                           today (java.time.LocalDate/now)
                                           days (.until today end-date java.time.temporal.ChronoUnit/DAYS)]
                                       (max 0 days))
                                     (catch Exception _ "unknown"))
                                   "unknown")

                  ;; Build JQL with optional status filter
                  ;; Get assigned tickets in current sprint - use sprint ID instead of name
                  status-filter (if (and (not show-done-tickets) (seq exclude-statuses))
                                  (str " AND status NOT IN ("
                                       (str/join ", " (map #(str "\"" % "\"") exclude-statuses))
                                       ")")
                                  "")
                  jql (str "assignee = currentUser() "
                           "AND sprint = " sprint-id
                           status-filter
                           " ORDER BY priority DESC")

                  assigned-tickets (try
                                     (when (:debug jira-config)
                                       (binding [*out* *err*]
                                         (let [timestamp (.format (java.time.LocalDateTime/now)
                                                                  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))]
                                           (println (str "[" timestamp "] [JIRA-DEBUG] Executing JQL: " jql)))))
                                     (let [response (jira-request jira-config :post "/search/jql"
                                                                  {:body (json/generate-string {:jql jql
                                                                                                :fields ["key" "summary" "status"]
                                                                                                :maxResults 10})})
                                           issues (get-in response [:body :issues] [])]
                                       (when (:debug jira-config)
                                         (binding [*out* *err*]
                                           (let [timestamp (.format (java.time.LocalDateTime/now)
                                                                    (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))]
                                             (println (str "[" timestamp "] [JIRA-DEBUG] Raw issues from API: " (count issues)))
                                             (when (seq issues)
                                               (println (str "[" timestamp "] [JIRA-DEBUG] First issue structure: " (keys (first issues))))
                                               (println (str "[" timestamp "] [JIRA-DEBUG] First issue fields: " (keys (:fields (first issues)))))))))
                                       (->> issues
                                            (map (fn [issue]
                                                   (str (:key issue) ": "
                                                        (get-in issue [:fields :summary])
                                                        " [" (or (get-in issue [:fields :status :name]) (get-in issue [:fields :status])) "]")))))
                                     (catch Exception e
                                       (println (str "Warning: Failed to fetch sprint tickets: " (.getMessage e)))
                                       (println (str "JQL Query was: " jql))
                                       []))]

              {:name sprint-name
               :id sprint-id
               :days-remaining days-remaining
               :assigned-tickets assigned-tickets
               :jql jql})))))

    (catch Exception e
      (println (str "Error fetching sprint info: " (.getMessage e)))
      nil)))


