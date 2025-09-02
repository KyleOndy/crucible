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

(defn get-current-sprint
  "Get the active sprint for a board"
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

(defn get-board-for-project
  "Get the board ID for a project (first matching board)"
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

(defn get-boards-for-project
  "Get all boards for a project"
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
  "Find all active sprints across all boards for a project.
   Returns a map with :sprints (unique active sprints) and :board-count"
  [jira-config project-key]
  (println (str "DEBUG: Looking for active sprints for project: " project-key))
  (if-let [boards (get-boards-for-project jira-config project-key)]
    (do
      (println (str "DEBUG: Found " (count boards) " boards for project " project-key))
      (doseq [board boards]
        (println (str "DEBUG: Board - ID: " (:id board) ", Name: " (:name board) ", Type: " (:type board))))

      (let [agile-url (str (:base-url jira-config) "/rest/agile/1.0")
            auth-header (make-auth-header (:username jira-config) (:api-token jira-config))

            ;; Get active sprints from each board
            sprint-results (for [board boards]
                             (let [sprint-url (str agile-url "/board/" (:id board) "/sprint?state=active")
                                   _ (println (str "DEBUG: Checking sprints on board " (:id board) " (" (:name board) ")"))
                                   response (http/get sprint-url
                                                      {:headers {"Authorization" auth-header
                                                                 "Accept" "application/json"}
                                                       :throw false})]
                               (println (str "DEBUG: Sprint API response status: " (:status response)))
                               (when (= 200 (:status response))
                                 (let [body (json/parse-string (:body response) true)
                                       sprints (:values body)]
                                   (println (str "DEBUG: Found " (count sprints) " active sprints on board " (:id board)))
                                   (doseq [sprint sprints]
                                     (println (str "DEBUG: Sprint - ID: " (:id sprint) ", Name: " (:name sprint) ", State: " (:state sprint))))
                                   sprints))))

            ;; Flatten and deduplicate sprints by ID
            all-sprints (->> sprint-results
                             (filter some?) ; Remove nil results
                             (apply concat) ; Flatten the list
                             (group-by :id) ; Group by sprint ID
                             (vals) ; Get groups
                             (map first)) ; Take first from each group (deduplication)

            board-count (count boards)]

        (println (str "DEBUG: After deduplication, found " (count all-sprints) " unique active sprints"))
        (doseq [sprint all-sprints]
          (println (str "DEBUG: Unique Sprint - ID: " (:id sprint) ", Name: " (:name sprint))))

        {:sprints all-sprints
         :board-count board-count}))
    (do
      (println (str "DEBUG: No boards found for project: " project-key))
      nil)))

(defn get-user-active-sprints
  "Find active sprints where the user is assigned/participating"
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

(defn find-sprints-by-name-pattern
  "Find active sprints across all accessible boards matching a name pattern"
  [jira-config pattern]
  (when pattern
    (println (str "DEBUG: Searching for sprints matching pattern: " pattern))
    ;; This would require getting all boards user can access, then searching sprints
    ;; For now, placeholder implementation
    nil))

(defn enhanced-sprint-detection
  "Enhanced sprint detection with multiple fallback strategies"
  [jira-config project-key & {:keys [debug fallback-board-ids sprint-name-pattern]}]
  (let [debug? (or debug (:sprint-debug jira-config false))]

    (when debug? (println (str "DEBUG: Enhanced sprint detection for project: " project-key)))

    ;; Strategy 1: Project-wide detection (existing)
    (when debug? (println "DEBUG: Trying Strategy 1 - Project-wide sprint detection"))
    (if-let [result (get-project-active-sprints jira-config project-key)]
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

(defn create-issue
  "Create a new Jira issue"
  [jira-config issue-data]
  (let [response (jira-request jira-config :post "/issue" {:body (json/generate-string issue-data)})]
    (cond
      (= 201 (:status response))
      {:success true
       :data (:body response)
       :key (get-in response [:body :key])
       :id (get-in response [:body :id])}

      (= 400 (:status response))
      {:success false
       :error (str "Invalid issue data: " (get-in response [:body :errors]))
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
       :status (:status response)})))

;; High-level functions

(defn comprehensive-jira-check
  "Comprehensive Jira configuration and connectivity check with user-friendly output"
  ([jira-config] (comprehensive-jira-check jira-config nil))
  ([jira-config test-ticket-id]
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
       (if-let [board (get-board-for-project jira-config (:default-project jira-config))]
         (if-let [sprint (get-current-sprint jira-config (:id board))]
           (do
             (println (str "   [OK] Active sprint found: " (:name sprint)))
             (println (str "   Board: " (:name board)))
             (println (str "   Sprint State: " (:state sprint)))
             (swap! results assoc :sprint-integration-ok true))
           (do
             (println "   [WARN] No active sprint found")
             (println "   Info: New tickets won't be added to sprint automatically")
             (swap! warnings conj "No active sprint available")))
         (do
           (println (str "   [WARN] No board found for project: " (:default-project jira-config)))
           (swap! warnings conj (str "Board not found for project: " (:default-project jira-config)))))
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
        :warnings @warnings}))))

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
        (comprehensive-jira-check jira-config test-ticket-id))

      (catch Exception e
        (println "❌ Error loading configuration:")
        (println (str "   " (.getMessage e)))
        (println)
        (println "💡 Configuration help:")
        (println "   • Check that your config file exists and is valid EDN")
        (println "   • See docs/jira-guide.md for setup instructions")
        (println "   • Try: bb -e \"(load-file \\\"core/lib/config.clj\\\") (lib.config/load-config)\"")
        {:success false :errors [(.getMessage e)] :warnings []}))))