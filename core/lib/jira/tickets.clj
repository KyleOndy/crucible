(ns lib.jira.tickets
  "Jira ticket operations and data formatting"
  (:require [clojure.string :as str]
            [lib.jira.auth :as jira-auth]))

(defn parse-ticket-id
  "Parse and validate a ticket ID (e.g., PROJ-1234)"
  [ticket-id]
  (when (and ticket-id (not (str/blank? ticket-id)))
    (let [match (->> ticket-id
                     str/upper-case
                     (re-matches #"^([A-Z][A-Z0-9]*)-(\d+)$"))]
      (when (seq match)
        (let [[_ project issue] match]
          {:project-key project,
           :issue-number issue,
           :full-id (str project "-" issue)})))))

(defn- process-ticket-response
  "Process Jira ticket response with consistent error handling"
  [response parsed-id data-processor]
  (cond (= 200 (:status response)) {:success true,
                                    :data (data-processor (:body response))}
        (= 404 (:status response))
          {:success false,
           :error (str "Ticket " (:full-id parsed-id) " not found"),
           :status (:status response)}
        (= 401 (:status response))
          {:success false,
           :error "Authentication failed. Check your Jira credentials.",
           :status (:status response)}
        :else {:success false,
               :error (str "Failed to fetch ticket: "
                           (get-in response [:body :errorMessages])),
               :status (:status response)}))

(defn get-ticket
  "Fetch ticket data from Jira"
  [jira-config ticket-id]
  (if-let [parsed-id (parse-ticket-id ticket-id)]
    (->> (jira-auth/jira-request jira-config
                                 :get
                                 (str "/issue/" (:full-id parsed-id)))
         (process-ticket-response
           parsed-id
           #(-> %
                (select-keys [:key :fields])
                (update :fields
                        (fn [fields]
                          (select-keys fields
                                       [:summary :description :status :assignee
                                        :reporter :priority :issuetype :created
                                        :updated]))))))
    {:success false,
     :error (str "Invalid ticket ID format: "
                 ticket-id
                 ". Expected format: PROJ-1234")}))

(defn get-ticket-full
  "Fetch complete ticket data from Jira including all custom fields"
  [jira-config ticket-id]
  (if-let [parsed-id (parse-ticket-id ticket-id)]
    (->> (jira-auth/jira-request jira-config
                                 :get
                                 (str "/issue/" (:full-id parsed-id)))
         (process-ticket-response parsed-id identity))
    {:success false,
     :error (str "Invalid ticket ID format: "
                 ticket-id
                 ". Expected format: PROJ-1234")}))

(defn format-ticket-summary
  "Format ticket data for display"
  [{:keys [key fields]}]
  (let [{:keys [summary status assignee reporter priority issuetype]} fields]
    {:key key,
     :summary summary,
     :status (get-in status [:name]),
     :assignee (get-in assignee [:displayName] "Unassigned"),
     :reporter (get-in reporter [:displayName]),
     :priority (get-in priority [:name]),
     :type (get-in issuetype [:name])}))
