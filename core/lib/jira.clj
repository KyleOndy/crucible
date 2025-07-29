(ns lib.jira
  (:require
    [clj-http.client :as http]
    [clojure.string :as str]))


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
                      :throw-exceptions false
                      :as :json}
        request-opts (merge default-opts opts)]
    (case method
      :get (http/get url request-opts)
      :post (http/post url request-opts)
      :put (http/put url request-opts)
      :delete (http/delete url request-opts))))


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
