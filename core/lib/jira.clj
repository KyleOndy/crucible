(ns lib.jira
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [lib.config :as config]
   [lib.adf :as adf]))

;; Core utility functions

(defn make-auth-header
  "Create Basic Auth header from username and API token"
  [username api-token]
  (let [credentials (str username ":" api-token)
        encoded (.encodeToString (java.util.Base64/getEncoder) (.getBytes credentials))]
    (str "Basic " encoded)))

(defn jira-request
  "Make an authenticated request to Jira API"
  [{:keys [base-url username api-token]} method path & [opts]]
  (let [url (str base-url "/rest/api/3" path)
        auth-header (make-auth-header username api-token)
        default-opts {:headers {"Authorization" auth-header
                                "Accept" "application/json"
                                "Content-Type" "application/json"}
                      :throw false}
        request-opts (merge default-opts opts)
        response (case method
                   :get (http/get url request-opts)
                   :post (http/post url request-opts)
                   :put (http/put url request-opts)
                   :delete (http/delete url request-opts))]
    (update response :body #(when % (json/parse-string % true)))))

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

(defn ^:private get-current-sprint
  "Get the active sprint for a board (private - use find-sprints instead)"
  [jira-config board-id]
  (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
        auth-header (make-auth-header (:username jira-config) (:api-token jira-config))
        response (http/get
                  (str agile-url "/board/" board-id "/sprint?state=active")
                  {:headers {"Authorization" auth-header
                             "Accept" "application/json"}
                   :throw false})]
    (when (= 200 (:status response))
      (let [body (json/parse-string (:body response) true)
            sprints (:values body)]
        (first sprints)))))

(defn ^:private get-board-for-project
  "Get the board ID for a project - first matching board (private - use find-sprints instead)"
  [jira-config project-key]
  (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
        auth-header (make-auth-header (:username jira-config) (:api-token jira-config))
        response (http/get
                  (str agile-url "/board?projectKeyOrId=" project-key)
                  {:headers {"Authorization" auth-header
                             "Accept" "application/json"}
                   :throw false})]
    (when (= 200 (:status response))
      (let [body (json/parse-string (:body response) true)
            boards (:values body)]
        (first boards)))))

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

(defn ^:private get-project-active-sprints
  "Find all active sprints across all boards for a project (private - use find-sprints instead).
   Returns a map with :sprints (unique active sprints) and :board-count"
  [jira-config project-key & {:keys [debug]}]
  (let [debug? (or debug (:sprint-debug jira-config false))]
    (when debug? (println (str "DEBUG: Looking for active sprints for project: " project-key)))
    (if-let [boards (get-boards-for-project jira-config project-key)]
      (do
        (when debug? (println (str "DEBUG: Found " (count boards) " boards for project " project-key)))
        (when debug?
          (doseq [board boards]
            (println (str "DEBUG: Board - ID: " (:id board) ", Name: " (:name board) ", Type: " (:type board)))))

        (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
              auth-header (make-auth-header (:username jira-config) (:api-token jira-config))

              ;; Get active sprints from each board
              sprint-results (for [board boards]
                               (let [sprint-url (str agile-url "/board/" (:id board) "/sprint?state=active")
                                     _ (when debug? (println (str "DEBUG: Checking sprints on board " (:id board) " (" (:name board) ")")))
                                     response (http/get sprint-url
                                                        {:headers {"Authorization" auth-header
                                                                   "Accept" "application/json"}
                                                         :throw false})]
                                 (when debug? (println (str "DEBUG: Sprint API response status: " (:status response))))
                                 (when (= 200 (:status response))
                                   (let [body (json/parse-string (:body response) true)
                                         sprints (:values body)]
                                     (when debug? (println (str "DEBUG: Found " (count sprints) " active sprints on board " (:id board))))
                                     (when debug?
                                       (doseq [sprint sprints]
                                         (println (str "DEBUG: Sprint - ID: " (:id sprint) ", Name: " (:name sprint) ", State: " (:state sprint)))))
                                     sprints))))

              ;; Flatten and deduplicate sprints by ID
              all-sprints (->> sprint-results
                               (filter some?) ; Remove nil results
                               (apply concat) ; Flatten the list
                               (group-by :id) ; Group by sprint ID
                               (vals) ; Get groups
                               (map first)) ; Take first from each group (deduplication)

              board-count (count boards)]

          (when debug? (println (str "DEBUG: After deduplication, found " (count all-sprints) " unique active sprints")))
          (when debug?
            (doseq [sprint all-sprints]
              (println (str "DEBUG: Unique Sprint - ID: " (:id sprint) ", Name: " (:name sprint)))))

          {:sprints all-sprints
           :board-count board-count}))
      (do
        (when debug? (println (str "DEBUG: No boards found for project: " project-key)))
        nil))))

(defn ^:private get-user-active-sprints
  "Find active sprints where the user is assigned/participating (private - placeholder implementation)"
  [jira-config]
  (let [user-info (get-user-info jira-config)]
    (when user-info
      (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
            auth-header (make-auth-header (:username jira-config) (:api-token jira-config))
            ;; Search for sprints where user is involved - this might need specific API endpoint
            ;; For now, return empty as this requires more research into Jira's user sprint API
            response (http/get
                      (str agile-url "/sprint?state=active") ; This might not be the right endpoint
                      {:headers {"Authorization" auth-header
                                 "Accept" "application/json"}
                       :throw false})]
        (when (= 200 (:status response))
          (let [body (json/parse-string (:body response) true)]
            (:values body)))))))

(defn ^:private find-sprints-by-name-pattern
  "Find active sprints across all accessible boards matching a name pattern (private - placeholder implementation)"
  [jira-config pattern]
  (when pattern
    (println (str "DEBUG: Searching for sprints matching pattern: " pattern))
    ;; This would require getting all boards user can access, then searching sprints
    ;; For now, placeholder implementation
    nil))

(defn enhanced-sprint-detection
  "Enhanced sprint detection with multiple fallback strategies
   
   DEPRECATED: Use find-sprints instead for new code.
   This function is maintained for compatibility during the transition."
  [jira-config project-key & {:keys [debug fallback-board-ids sprint-name-pattern]}]
  (let [debug? (or debug (:sprint-debug jira-config false))]

    (when debug? (println (str "DEBUG: Enhanced sprint detection for project: " project-key)))

    ;; Strategy 1: Project-wide detection (existing)
    (when debug? (println "DEBUG: Trying Strategy 1 - Project-wide sprint detection"))
    (if-let [result (get-project-active-sprints jira-config project-key :debug debug?)]
      (do
        (when debug? (println "DEBUG: Strategy 1 succeeded"))
        (when debug? (println (str "DEBUG: Strategy 1 raw result: " result)))
        (let [final-result (assoc result :detection-method "project-wide")]
          (when debug? (println (str "DEBUG: Strategy 1 final result: " final-result)))
          (when debug? (println (str "DEBUG: Returning from enhanced-sprint-detection: " final-result)))
          final-result))

      ;; Strategy 2: Fallback board IDs
      (do
        (when debug? (println "DEBUG: Strategy 1 failed, trying Strategy 2 - Fallback board IDs"))
        (if (and fallback-board-ids (seq fallback-board-ids))
          (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
                auth-header (make-auth-header (:username jira-config) (:api-token jira-config))
                sprint-results (for [board-id fallback-board-ids]
                                 (let [response (http/get
                                                 (str agile-url "/board/" board-id "/sprint?state=active")
                                                 {:headers {"Authorization" auth-header
                                                            "Accept" "application/json"}
                                                  :throw false})]
                                   (when debug? (println (str "DEBUG: Checking fallback board " board-id ", status: " (:status response))))
                                   (when (= 200 (:status response))
                                     (let [body (json/parse-string (:body response) true)]
                                       (:values body)))))
                all-sprints (->> sprint-results
                                 (filter some?)
                                 (apply concat)
                                 (group-by :id)
                                 (vals)
                                 (map first))]
            (when debug? (println (str "DEBUG: Fallback boards found " (count all-sprints) " unique sprints")))
            (if (seq all-sprints)
              (let [final-result {:sprints all-sprints
                                  :board-count (count fallback-board-ids)
                                  :detection-method "fallback-boards"}]
                (when debug? (println (str "DEBUG: Strategy 2 final result: " final-result)))
                (when debug? (println (str "DEBUG: Returning from enhanced-sprint-detection: " final-result)))
                final-result)

              ;; Strategy 3: Sprint name pattern (placeholder)
              (do
                (when debug? (println "DEBUG: Strategy 2 failed, trying Strategy 3 - Name pattern matching"))
                (when debug? (println "DEBUG: Strategy 3 not yet implemented"))
                (when debug? (println "DEBUG: All strategies failed, returning nil"))
                nil)))

          ;; No fallback boards configured
          (do
            (when debug? (println "DEBUG: No fallback board IDs configured"))
            (when debug? (println "DEBUG: Strategy 1 failed, no fallback boards, returning nil"))
            nil))))))

(defn find-sprints
  "Unified sprint detection API - single entry point for all sprint detection needs.
  
  Options:
    :project-key (required) - Project key to search sprints for
    :debug (optional) - Enable debug output (default: false)  
    :fallback-board-ids (optional) - Vector of board IDs to search if project detection fails
    :sprint-name-pattern (optional) - Regex pattern to match sprint names
    :detection-strategies (optional) - Vector of strategies to use [:project-wide :fallback-boards :pattern-matching]
  
  Returns:
    {:sprints [sprint-data...]
     :board-count number
     :detection-method string}
  or nil if no sprints found"
  [jira-config {:keys [project-key debug fallback-board-ids sprint-name-pattern detection-strategies]
                :or {debug false
                     detection-strategies [:project-wide :fallback-boards :pattern-matching]}}]
  (when-not project-key
    (throw (ex-info "Project key is required for sprint detection"
                    {:jira-config jira-config})))

  ;; Use enhanced-sprint-detection for now, but this provides a clean API boundary
  (enhanced-sprint-detection jira-config project-key
                             :debug debug
                             :fallback-board-ids fallback-board-ids
                             :sprint-name-pattern sprint-name-pattern))

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

(defn comprehensive-jira-check
  "Comprehensive Jira configuration and connectivity check with user-friendly output"
  ([config] (comprehensive-jira-check config nil))
  ([config test-ticket-id]
   (let [jira-config (:jira config)]
     (println "Checking Jira Configuration and Connectivity")
     (println (str "   " (java.time.LocalDateTime/now)))
     (println)

     (let [results (atom {:config-valid false
                          :connection-ok false
                          :user-info nil
                          :test-ticket-ok false
                          :sprint-integration-ok false})
           errors (atom [])
           warnings (atom [])]

       ;; Step 1: Configuration Validation
       (println "1. Configuration Validation")
       (if jira-config
         (let [required-keys [:base-url :username :api-token]
               missing-keys (filter #(not (get jira-config %)) required-keys)]
           (if (empty? missing-keys)
             (do
               (println "   [OK] All required configuration present")
               (println (str "   URL: " (:base-url jira-config)))
               (println (str "   User: " (:username jira-config)))
               (println (str "   Token: " (if (get jira-config :api-token) "*****" "Missing")))
               (when-let [project (:default-project jira-config)]
                 (println (str "   Default Project: " project)))
               (swap! results assoc :config-valid true))
             (do
               (println "   [ERROR] Configuration incomplete")
               (doseq [key missing-keys]
                 (println (str "   Missing: " (name key))))
               (swap! errors conj (str "Missing required configuration: " (clojure.string/join ", " (map name missing-keys)))))))
         (do
           (println "   [ERROR] No configuration found")
           (swap! errors conj "No Jira configuration found")))

       (println)

       ;; Step 2: Connection Test (only if config is valid)
       (when (:config-valid @results)
         (println "2. Connection Test")
         (let [connection-result (test-connection jira-config)]
           (if (:success connection-result)
             (do
               (println (str "   [OK] " (:message connection-result)))
               (swap! results assoc :connection-ok true))
             (do
               (println (str "   [ERROR] " (:message connection-result)))
               (swap! errors conj (:message connection-result)))))
         (println))

       ;; Step 3: User Information (only if connected)
       (when (:connection-ok @results)
         (println "3. User Information")
         (if-let [user-info (get-user-info jira-config)]
           (do
             (println (str "   [OK] Connected as: " (:displayName user-info)))
             (println (str "   Email: " (:emailAddress user-info)))
             (println (str "   Account ID: " (:accountId user-info)))
             (when-let [timezone (:timeZone user-info)]
               (println (str "   Timezone: " timezone)))
             (swap! results assoc :user-info user-info))
           (do
             (println "   [WARN] Could not retrieve user information")
             (swap! warnings conj "User information not available")))
         (println))

       ;; Step 4: Test Ticket Fetch (if ticket ID provided and connected)
       (when (and (:connection-ok @results) test-ticket-id)
         (println (str "4. Test Ticket Fetch (" test-ticket-id ")"))
         (let [ticket-result (get-ticket jira-config test-ticket-id)]
           (if (:success ticket-result)
             (let [summary (format-ticket-summary (:data ticket-result))]
               (println "   [OK] Ticket fetched successfully")
               (println (str "   " (:key summary) ": " (:summary summary)))
               (println (str "   Status: " (:status summary)))
               (println (str "   Type: " (:type summary)))
               (println (str "   Assignee: " (:assignee summary)))
               (swap! results assoc :test-ticket-ok true))
             (do
               (println (str "   [ERROR] Failed to fetch ticket: " (:error ticket-result)))
               (swap! errors conj (:error ticket-result)))))
         (println))

       ;; Step 5: Sprint Integration Test (only if connected and default project configured)
       (when (and (:connection-ok @results) (:default-project jira-config))
         (println "5. Sprint Integration Check")
         (let [sprint-config (:sprint config)
               debug? (:debug sprint-config false)
               project-key (:default-project jira-config)
               fallback-boards (:fallback-board-ids sprint-config)
               sprint-pattern (:name-pattern sprint-config)

               sprint-data (find-sprints jira-config
                                         {:project-key project-key
                                          :debug debug?
                                          :fallback-board-ids fallback-boards
                                          :sprint-name-pattern sprint-pattern})]

           (if sprint-data
             (let [sprints (:sprints sprint-data)
                   board-count (:board-count sprint-data)
                   method (:detection-method sprint-data)]
               (cond
                 (= 1 (count sprints))
                 (do
                   (println (str "   [OK] Active sprint found: " (:name (first sprints))))
                   (println (str "   Detection method: " method " (across " board-count " boards)"))
                   (println (str "   Sprint ID: " (:id (first sprints))))
                   (println (str "   Sprint State: " (:state (first sprints))))
                   (swap! results assoc :sprint-integration-ok true))

                 (> (count sprints) 1)
                 (do
                   (println (str "   [OK] Multiple active sprints found (" (count sprints) " sprints)"))
                   (println (str "   Detection method: " method " (across " board-count " boards)"))
                   (println (str "   Primary sprint: " (:name (first sprints))))
                   (println "   Note: First sprint would be used for new tickets")
                   (swap! results assoc :sprint-integration-ok true))

                 :else
                 (do
                   (println (str "   [WARN] No active sprints found (" method ")"))
                   (println "   Info: New tickets won't be added to sprint automatically")
                   (swap! warnings conj "No active sprint available"))))
             (do
               (println "   [WARN] Sprint detection failed completely")
               (println "   Info: Enhanced detection with fallbacks found no active sprints")
               (println "   Troubleshooting:")
               (println "     - Verify project key is correct")
               (println "     - Check user access to project boards")
               (println "     - Consider setting :sprint :fallback-board-ids in config")
               (println "     - Set :sprint :debug true for detailed logging")
               (swap! warnings conj "Sprint detection failed with enhanced algorithm"))))
         (println))

       ;; Summary
       (println "Summary")
       (let [total-checks (+ (if (:config-valid @results) 1 0)
                             (if (:connection-ok @results) 1 0)
                             (if (:user-info @results) 1 0)
                             (if (and test-ticket-id (:test-ticket-ok @results)) 1 0)
                             (if (:sprint-integration-ok @results) 1 0))
             possible-checks (+ 3 ; config, connection, user info
                                (if test-ticket-id 1 0)
                                (if (:default-project jira-config) 1 0))]

         (println (str "   " total-checks "/" possible-checks " checks passed"))

         (when (seq @errors)
           (println)
           (println "Errors:")
           (doseq [error @errors]
             (println (str "   - " error))))

         (when (seq @warnings)
           (println)
           (println "Warnings:")
           (doseq [warning @warnings]
             (println (str "   - " warning))))

         (when (seq @errors)
           (println)
           (println "Next Steps:")
           (println "   1. Review the Jira setup guide: docs/jira-guide.md")
           (println "   2. Check your configuration file or environment variables")
           (println "   3. Verify your API token is correct and has proper permissions")
           (println "   4. Test your credentials manually with curl if needed"))

         (when (and (empty? @errors) (:connection-ok @results))
           (println)
           (println "Success: Jira integration is working correctly!")
           (println "   You can now create tickets with: c qs \"Your ticket summary\""))

         ;; Return structured result
         {:success (empty? @errors)
          :results @results
          :errors @errors
          :warnings @warnings})))))

(defn run-jira-check
  "Command-line interface for jira-check"
  [& args]
  (let [test-ticket-id (first args)]
    (println "Crucible Jira Configuration Check")
    (println "=================================")
    (println)

    (try
      (let [config (config/load-config)
            jira-config (:jira config)]
        (comprehensive-jira-check config test-ticket-id))

      (catch Exception e
        (println "❌ Error loading configuration:")
        (println (str "   " (.getMessage e)))
        (println)
        (println "💡 Configuration help:")
        (println "   • Check that your config file exists and is valid EDN")
        (println "   • See docs/jira-guide.md for setup instructions")
        (println "   • Try: bb -e \"(load-file \\\"core/lib/config.clj\\\") (lib.config/load-config)\"")
        {:success false :errors [(.getMessage e)] :warnings []}))))