(ns lib.jira
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
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

(defn parse-inline-formatting
  "Parse inline markdown formatting (bold, italic, code, links) in text"
  [text]
  (if (str/blank? text)
    []
    (let [;; Find matches for each pattern type, avoiding overlaps within each type
          find-all-matches (fn [pattern type text]
                             (let [matcher (re-matcher pattern text)
                                   matches (atom [])]
                               (while (.find matcher)
                                 (let [start (.start matcher)
                                       end (.end matcher)
                                       full-match (.group matcher)
                                       inner-text (if (> (.groupCount matcher) 0)
                                                    (.group matcher 1)
                                                    full-match)
                                       ;; For URLs, both text and href should be the same
                                       href (if (= type :url)
                                              full-match
                                              (when (> (.groupCount matcher) 1)
                                                (.group matcher 2)))]
                                   (swap! matches conj {:start start
                                                        :end end
                                                        :type type
                                                        :text inner-text
                                                        :full full-match
                                                        :href href})))
                               @matches))

          ;; Find all different types of formatting
          ;; Order matters: bold before italic to handle ** vs * correctly
          bold-matches (find-all-matches #"\*\*([^*]+)\*\*" :bold text)
          code-matches (find-all-matches #"`([^`]+)`" :code text)
          custom-link-matches (find-all-matches #"\[([^\]]+)\]\(([^)]+)\)" :custom-link text)
          url-matches (find-all-matches #"https?://[^\s]+" :url text)
          ;; Italic last to avoid conflicts with bold
          italic-matches (find-all-matches #"(?<!\*)\*([^*]+)\*(?!\*)" :italic text)

          ;; Combine and sort all matches by position
          all-matches (concat bold-matches code-matches custom-link-matches url-matches italic-matches)
          sorted-matches (sort-by :start all-matches)

          ;; Remove overlapping matches (keep the earlier one)
          non-overlapping (reduce (fn [acc match]
                                    (if (or (empty? acc)
                                            (>= (:start match) (:end (last acc))))
                                      (conj acc match)
                                      acc))
                                  [] sorted-matches)]

      ;; Build content nodes by processing matches in order
      (loop [pos 0
             matches non-overlapping
             nodes []]
        (if (empty? matches)
          ;; Add any remaining text
          (if (< pos (count text))
            (conj nodes {:type "text" :text (subs text pos)})
            nodes)
          ;; Process next match
          (let [match (first matches)
                remaining (rest matches)
                ;; Add text before match (if any)
                nodes-with-prefix (if (< pos (:start match))
                                    (conj nodes {:type "text" :text (subs text pos (:start match))})
                                    nodes)
                ;; Create formatted node
                formatted-node (case (:type match)
                                 :bold {:type "text" :text (:text match)
                                        :marks [{:type "strong"}]}
                                 :italic {:type "text" :text (:text match)
                                          :marks [{:type "em"}]}
                                 :code {:type "text" :text (:text match)
                                        :marks [{:type "code"}]}
                                 :custom-link {:type "text" :text (:text match)
                                               :marks [{:type "link" :attrs {:href (:href match)}}]}
                                 :url {:type "text" :text (:text match)
                                       :marks [{:type "link" :attrs {:href (:href match)}}]})
                updated-nodes (conj nodes-with-prefix formatted-node)]
            (recur (:end match) remaining updated-nodes)))))))

(defn create-paragraph
  "Create ADF paragraph node from text"
  [text]
  (when-not (str/blank? text)
    {:type "paragraph"
     :content (parse-inline-formatting text)}))

(defn create-heading
  "Create ADF heading node from markdown header"
  [text]
  (let [level (count (take-while #(= % \#) text))
        heading-text (str/trim (subs text level))]
    (when (and (<= 1 level 6) (not (str/blank? heading-text)))
      {:type "heading"
       :attrs {:level level}
       :content (parse-inline-formatting heading-text)})))

(defn create-bullet-list
  "Create ADF bullet list from lines starting with -"
  [list-lines]
  (when (seq list-lines)
    {:type "bulletList"
     :content (map (fn [line]
                     (let [item-text (str/trim (subs line 1))]
                       {:type "listItem"
                        :content [{:type "paragraph"
                                   :content (parse-inline-formatting item-text)}]}))
                   list-lines)}))

(defn parse-block-elements
  "Parse block-level markdown elements (headers, lists, paragraphs)"
  [text]
  (if (str/blank? text)
    []
    (let [lines (str/split-lines text)
          ;; Group consecutive list items
          grouped-lines (reduce (fn [acc line]
                                  (cond
                                    ;; Header
                                    (str/starts-with? line "#")
                                    (conj acc {:type :header :line line})

                                    ;; List item
                                    (str/starts-with? line "- ")
                                    (let [last-group (last acc)]
                                      (if (and last-group (= (:type last-group) :list))
                                        (update acc (dec (count acc))
                                                #(update % :lines conj line))
                                        (conj acc {:type :list :lines [line]})))

                                    ;; Regular paragraph
                                    (not (str/blank? line))
                                    (conj acc {:type :paragraph :line line})

                                    ;; Empty line - ignore for grouping
                                    :else acc))
                                [] lines)]

      ;; Convert groups to ADF nodes
      (keep (fn [group]
              (case (:type group)
                :header (create-heading (:line group))
                :list (create-bullet-list (:lines group))
                :paragraph (create-paragraph (:line group))))
            grouped-lines))))

(defn text->adf
  "Convert text with basic markdown to Atlassian Document Format (ADF)
  
  Supported markdown:
  - **bold text** → strong formatting
  - *italic text* → emphasis formatting  
  - `inline code` → code formatting
  - # Header (levels 1-6) → headings
  - - List item → bullet lists
  - [Link text](https://example.com) → links
  - https://example.com → auto-linked URLs
  - PROJ-123 → plain text (Jira auto-links ticket IDs)"
  [text]
  (if (or (nil? text) (str/blank? text))
    {:version 1
     :type "doc"
     :content []}
    (try
      ;; Split into paragraphs on double newlines
      (let [paragraphs (str/split text #"\n\s*\n")
            content (mapcat parse-block-elements paragraphs)
            ;; Ensure we have at least one paragraph if content is empty
            final-content (if (empty? content)
                            [{:type "paragraph"
                              :content (parse-inline-formatting text)}]
                            content)]
        {:version 1
         :type "doc"
         :content final-content})
      (catch Exception e
        ;; Fallback to simple paragraph on any parsing error
        {:version 1
         :type "doc"
         :content [{:type "paragraph"
                    :content [{:type "text" :text text}]}]}))))

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
   (println "🔍 Checking Jira Configuration and Connectivity")
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
     (println "1️⃣  Configuration Validation")
     (if jira-config
       (let [required-keys [:base-url :username :api-token]
             missing-keys (filter #(not (get jira-config %)) required-keys)]
         (if (empty? missing-keys)
           (do
             (println "   ✅ All required configuration present")
             (println (str "   🌐 URL: " (:base-url jira-config)))
             (println (str "   👤 User: " (:username jira-config)))
             (println (str "   🔑 Token: " (if (get jira-config :api-token) "*****" "Missing")))
             (when-let [project (:default-project jira-config)]
               (println (str "   📁 Default Project: " project)))
             (swap! results assoc :config-valid true))
           (do
             (println "   ❌ Configuration incomplete")
             (doseq [key missing-keys]
               (println (str "   ⚠️  Missing: " (name key))))
             (swap! errors conj (str "Missing required configuration: " (clojure.string/join ", " (map name missing-keys)))))))
       (do
         (println "   ❌ No configuration found")
         (swap! errors conj "No Jira configuration found")))

     (println)

     ;; Step 2: Connection Test (only if config is valid)
     (when (:config-valid @results)
       (println "2️⃣  Connection Test")
       (let [connection-result (test-connection jira-config)]
         (if (:success connection-result)
           (do
             (println (str "   ✅ " (:message connection-result)))
             (swap! results assoc :connection-ok true))
           (do
             (println (str "   ❌ " (:message connection-result)))
             (swap! errors conj (:message connection-result)))))
       (println))

     ;; Step 3: User Information (only if connected)
     (when (:connection-ok @results)
       (println "3️⃣  User Information")
       (if-let [user-info (get-user-info jira-config)]
         (do
           (println (str "   ✅ Connected as: " (:displayName user-info)))
           (println (str "   📧 Email: " (:emailAddress user-info)))
           (println (str "   🆔 Account ID: " (:accountId user-info)))
           (when-let [timezone (:timeZone user-info)]
             (println (str "   🕐 Timezone: " timezone)))
           (swap! results assoc :user-info user-info))
         (do
           (println "   ⚠️  Could not retrieve user information")
           (swap! warnings conj "User information not available")))
       (println))

     ;; Step 4: Test Ticket Fetch (if ticket ID provided and connected)
     (when (and (:connection-ok @results) test-ticket-id)
       (println (str "4️⃣  Test Ticket Fetch (" test-ticket-id ")"))
       (let [ticket-result (get-ticket jira-config test-ticket-id)]
         (if (:success ticket-result)
           (let [summary (format-ticket-summary (:data ticket-result))]
             (println "   ✅ Ticket fetched successfully")
             (println (str "   🎫 " (:key summary) ": " (:summary summary)))
             (println (str "   📊 Status: " (:status summary)))
             (println (str "   🏷️  Type: " (:type summary)))
             (println (str "   👤 Assignee: " (:assignee summary)))
             (swap! results assoc :test-ticket-ok true))
           (do
             (println (str "   ❌ Failed to fetch ticket: " (:error ticket-result)))
             (swap! errors conj (:error ticket-result)))))
       (println))

     ;; Step 5: Sprint Integration Test (only if connected and default project configured)
     (when (and (:connection-ok @results) (:default-project jira-config))
       (println "5️⃣  Sprint Integration Check")
       (if-let [board (get-board-for-project jira-config (:default-project jira-config))]
         (if-let [sprint (get-current-sprint jira-config (:id board))]
           (do
             (println (str "   ✅ Active sprint found: " (:name sprint)))
             (println (str "   📋 Board: " (:name board)))
             (println (str "   🎯 Sprint State: " (:state sprint)))
             (swap! results assoc :sprint-integration-ok true))
           (do
             (println "   ⚠️  No active sprint found")
             (println "   ℹ️  New tickets won't be added to sprint automatically")
             (swap! warnings conj "No active sprint available")))
         (do
           (println (str "   ⚠️  No board found for project: " (:default-project jira-config)))
           (swap! warnings conj (str "Board not found for project: " (:default-project jira-config)))))
       (println))

     ;; Summary
     (println "📋 Summary")
     (let [total-checks (+ (if (:config-valid @results) 1 0)
                           (if (:connection-ok @results) 1 0)
                           (if (:user-info @results) 1 0)
                           (if (and test-ticket-id (:test-ticket-ok @results)) 1 0)
                           (if (:sprint-integration-ok @results) 1 0))
           possible-checks (+ 3 ; config, connection, user info
                              (if test-ticket-id 1 0)
                              (if (:default-project jira-config) 1 0))]

       (println (str "   ✅ " total-checks "/" possible-checks " checks passed"))

       (when (seq @errors)
         (println)
         (println "❌ Errors:")
         (doseq [error @errors]
           (println (str "   • " error))))

       (when (seq @warnings)
         (println)
         (println "⚠️  Warnings:")
         (doseq [warning @warnings]
           (println (str "   • " warning))))

       (when (seq @errors)
         (println)
         (println "💡 Next Steps:")
         (println "   1. Review the Jira setup guide: docs/jira-guide.md")
         (println "   2. Check your configuration file or environment variables")
         (println "   3. Verify your API token is correct and has proper permissions")
         (println "   4. Test your credentials manually with curl if needed"))

       (when (and (empty? @errors) (:connection-ok @results))
         (println)
         (println "🎉 Jira integration is working correctly!")
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