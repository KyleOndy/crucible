(ns lib.jira.auth
  "Core Jira authentication and HTTP request utilities"
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn make-auth-header
  "Create Basic Auth header from username and API token"
  [username api-token]
  (when (or (str/blank? username) (str/blank? api-token))
    (throw (ex-info "Username and API token are required"
                    {:username username, :api-token (some? api-token)})))
  (->> (str username ":" api-token)
       (.getBytes)
       (.encodeToString (java.util.Base64/getEncoder))
       (str "Basic ")))

(defn- log-timestamp
  "Generate formatted timestamp for debug logging"
  []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern
             "yyyy-MM-dd HH:mm:ss")))

(defn- debug-log
  "Helper function for debug logging with timestamp"
  [& messages]
  (binding [*out* *err*]
    (println (str "[" (log-timestamp)
                  "] [JIRA-DEBUG] " (str/join " " messages)))))

(defn- log-request-debug
  "Log outgoing request details"
  [method url request-opts]
  (debug-log "HTTP" (str/upper-case (name method)) url)
  (when-let [query-params (:query-params request-opts)]
    (debug-log "Query params:" query-params))
  (when-let [body (:body request-opts)] (debug-log "Request body:" body)))

(defn- log-response-debug
  "Log response details"
  [response parsed-response]
  (debug-log "Response status:" (:status response))
  (when-let [body (:body parsed-response)]
    (if (map? body)
      (do (debug-log "Response body keys:" (keys body))
          (when (:issues body)
            (debug-log "Issues count:" (count (:issues body))))
          (when (< (count (str body)) 1000) (debug-log "Response body:" body)))
      (debug-log "Response body:" body))))

(defn jira-request
  "Make an authenticated request to Jira API"
  [{:keys [base-url username api-token debug], :as jira-config} method path &
   [opts]]
  (let [url (str base-url "/rest/api/3" path)
        auth-header (make-auth-header username api-token)
        default-opts {:headers {"Authorization" auth-header,
                                "Accept" "application/json",
                                "Content-Type" "application/json"},
                      :throw false}
        request-opts (merge default-opts opts)]
    (when debug (log-request-debug method url request-opts))
    (let [response (case method
                     :get (http/get url request-opts)
                     :post (http/post url request-opts)
                     :put (http/put url request-opts)
                     :delete (http/delete url request-opts))
          parsed-response
            (update response :body #(when % (json/parse-string % true)))]
      (when debug (log-response-debug response parsed-response))
      parsed-response)))

(defn test-connection
  "Test Jira connection and authentication"
  [jira-config]
  (let [response (jira-request jira-config :get "/myself")]
    (if (= 200 (:status response))
      {:success true,
       :message (str "Successfully connected as: "
                     (get-in response [:body :displayName]))}
      {:success false,
       :message (str "Connection failed: "
                     (or (get-in response [:body :message])
                         (:reason-phrase response))),
       :status (:status response)})))

(defn get-user-info
  "Get current user information"
  [jira-config]
  (let [response (jira-request jira-config :get "/myself")]
    (when (= 200 (:status response)) (:body response))))
