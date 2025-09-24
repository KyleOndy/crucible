(ns lib.jira.fields
  "Jira custom field handling and issue creation"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [lib.jira.auth :as jira-auth]))


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
    (or (string? value) (number? value) (boolean? value)) value
    ;; Array of strings (labels, text fields)
    (and (vector? value) (every? string? value)) value
    ;; Single select with ID
    (and (map? value) (:id value) (not (:child value))) {:id (:id value)}
    ;; Cascading select with parent and child
    (and (map? value) (:id value) (:child value))
      {:id (:id value),
       :child
         (if (map? (:child value)) {:id (:id (:child value))} (:child value))}
    ;; User picker with accountId
    (and (map? value) (:accountId value)) {:accountId (:accountId value)}
    ;; User picker with name (legacy)
    (and (map? value) (:name value) (not (:id value))) {:name (:name value)}
    ;; Version or component with just name
    (and (map? value) (:name value) (not (:accountId value)))
      (if (:id value)
        {:id (:id value)} ; Prefer ID if available
        {:name (:name value)})
    ;; Multi-select array of objects
    (and (vector? value) (every? map? value)) (mapv format-custom-field-value
                                                value)
    ;; Default: return as-is
    :else value))


(defn prepare-custom-fields
  "Prepare custom fields for Jira API submission.

  Takes a map of custom field definitions and formats them appropriately.
  Custom fields can be specified with or without the 'customfield_' prefix.

  Examples:
    {\"customfield_10001\" \"Simple text value\"
     \"10002\" {:id \"10100\"}  ; Will be prefixed to customfield_10002
     :customfield_10003 [{:id \"10200\"} {:id \"10201\"}]
     \"epic-link\" \"PROJ-123\"  ; Named field mapping (requires field-mappings)}"
  ([custom-fields] (prepare-custom-fields custom-fields {}))
  ([custom-fields field-mappings]
   (when custom-fields
     (reduce-kv
       (fn [acc k v]
         (let [field-key
                 (cond
                   ;; Already has customfield_ prefix
                   (and (string? k) (str/starts-with? k "customfield_")) k
                   ;; Keyword with customfield_ prefix
                   (and (keyword? k) (str/starts-with? (name k) "customfield_"))
                     (name k)
                   ;; Numeric ID without prefix
                   (and (string? k) (re-matches #"\d+" k)) (str "customfield_"
                                                                k)
                   ;; Check field mappings for named fields
                   (contains? field-mappings k) (get field-mappings k)
                   (contains? field-mappings (keyword k)) (get field-mappings
                                                               (keyword k))
                   ;; Default: convert to string
                   :else (str k))
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
        query-string
          (when (seq params)
            (str "?"
                 (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params))))
        response
          (jira-auth/jira-request jira-config :get (str "/field" query-string))]
    (when (= 200 (:status response)) (:body response))))


(defn get-create-metadata
  "Get metadata for creating issues, including custom field definitions and allowed values.
  This is useful for discovering what fields are required/optional and their constraints."
  [jira-config project-key issue-type-name]
  (let [params {:projectKeys project-key,
                :issuetypeNames issue-type-name,
                :expand "projects.issuetypes.fields"}
        query-string
          (str "?"
               (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) params)))
        response (jira-auth/jira-request jira-config
                                         :get
                                         (str "/issue/createmeta"
                                              query-string))]
    (when (= 200 (:status response))
      (let [body (:body response)
            project (first (:projects body))
            issue-type (first (:issuetypes project))]
        {:project project,
         :issue-type issue-type,
         :fields (:fields issue-type),
         :custom-fields (filter #(str/starts-with? (key %) "customfield_")
                          (:fields issue-type))}))))


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
                               (if (and (string? k)
                                        (str/starts-with? k "customfield_"))
                                 (assoc acc k (format-custom-field-value v))
                                 (assoc acc k v)))
                             {}
                             fields)
                           fields)
        formatted-issue-data (assoc issue-data :fields formatted-fields)
        response (jira-auth/jira-request jira-config
                                         :post
                                         "/issue"
                                         {:body (json/generate-string
                                                  formatted-issue-data)})]
    (cond (= 201 (:status response)) {:success true,
                                      :data (:body response),
                                      :key (get-in response [:body :key]),
                                      :id (get-in response [:body :id])}
          (= 400 (:status response))
            {:success false,
             :error (str "Invalid issue data: "
                         (or (get-in response [:body :errors])
                             (get-in response [:body :errorMessages])
                             "Unknown validation error")),
             :details (:body response),
             :status (:status response)}
          (= 401 (:status response))
            {:success false,
             :error "Authentication failed. Check your Jira credentials.",
             :status (:status response)}
          :else {:success false,
                 :error (str "Failed to create issue: "
                             (or (get-in response [:body :errorMessages])
                                 (get-in response [:body :errors])
                                 (:reason-phrase response))),
                 :details (:body response),
                 :status (:status response)})))
