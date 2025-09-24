(ns lib.jira.activity
  "Jira activity tracking and time formatting utilities"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [lib.jira.auth :as jira-auth]))


(defn format-time-ago
  "Format an instant as a relative time string"
  [instant]
  (let [now (java.time.Instant/now)
        duration (java.time.Duration/between instant now)
        hours (.toHours duration)
        days (.toDays duration)]
    (cond (< hours 1) "less than 1 hour ago"
          (< hours 24) (str hours " hour" (when (> hours 1) "s") " ago")
          (< days 7) (str days " day" (when (> days 1) "s") " ago")
          :else (str (.format instant
                              (java.time.format.DateTimeFormatter/ofPattern
                                "MMM d"))))))


(defn get-my-recent-activity
  "Get meaningful recent activity on issues the current user is involved with"
  [jira-config since-date activity-options]
  (try
    (let [user-info (jira-auth/get-user-info jira-config)
          current-user-account-id (:accountId user-info)
          current-user-display-name (:displayName user-info)
          ;; Extract configuration options with defaults
          activity-days (get activity-options :activity-days 5)
          exclude-own-activity (get activity-options :exclude-own-activity true)
          allowed-activity-types (set (get activity-options
                                           :activity-types
                                           ["status" "assignee" "priority"
                                            "resolution"]))
          max-activities (get activity-options :max-activities 10)
          ;; Create JQL query for tickets updated recently that involve the
          ;; user
          jql (str "assignee = currentUser() OR reporter = currentUser() "
                   "AND updated >= -" activity-days
                   "d " "ORDER BY updated DESC")
          ;; Get issues with changelog data
          response (jira-auth/jira-request jira-config
                                           :post
                                           "/search/jql"
                                           {:body (json/generate-string
                                                    {:jql jql,
                                                     :fields ["key" "summary"
                                                              "status" "updated"
                                                              "assignee"],
                                                     :expand ["changelog"],
                                                     :maxResults 50})})
          issues (get-in response [:body :issues] [])]
      ;; Process each issue's changelog to extract meaningful recent
      ;; activities
      (->>
        issues
        (mapcat
          (fn [issue]
            (let [issue-key (:key issue)
                  summary (get-in issue [:fields :summary])
                  changelog (get-in issue [:changelog :histories] [])]
              ;; Process changelog entries from the last few days
              (->>
                changelog
                (filter
                  (fn [history]
                    (let [created-str (:created history)
                          created-instant (java.time.Instant/parse created-str)
                          cutoff-instant (.minus (java.time.Instant/now)
                                                 (java.time.Duration/ofDays
                                                   activity-days))]
                      (.isAfter created-instant cutoff-instant))))
                ;; Filter out activities by the current user if configured
                (filter (fn [history]
                          (if exclude-own-activity
                            (not= (get-in history [:author :accountId])
                                  current-user-account-id)
                            true)))
                ;; Convert to activity descriptions
                (map
                  (fn [history]
                    (let [author-name (get-in history [:author :displayName])
                          created-instant (java.time.Instant/parse (:created
                                                                     history))
                          time-ago (format-time-ago created-instant)
                          items (:items history)]
                      ;; Extract meaningful changes from the items
                      (if (seq items)
                        (let [filtered-items
                                (->> items
                                     (filter (fn [item]
                                               (contains? allowed-activity-types
                                                          (:field item)))))
                              change-descriptions
                                (->> filtered-items
                                     (map
                                       (fn [item]
                                         (let [field (:field item)
                                               from-str (:fromString item)
                                               to-str (:toString item)]
                                           (case field
                                             "status" (str "Status: " from-str
                                                           " → " to-str)
                                             "assignee" (if to-str
                                                          (str "Assigned to "
                                                               to-str)
                                                          "Unassigned")
                                             "summary" "Updated summary"
                                             "priority" (str "Priority: "
                                                               from-str
                                                             " → " to-str)
                                             "resolution" (if to-str
                                                            (str "Resolved as "
                                                                 to-str)
                                                            "Reopened")
                                             (str "Updated " field)))))
                                     (str/join ", "))]
                          (when (seq filtered-items)
                            (str issue-key
                                 ": "
                                 change-descriptions
                                 " ("
                                 author-name
                                 ", "
                                 time-ago
                                 ")")))
                        ;; If no items, might be a comment (though we don't
                        ;; have comment data in changelog)
                        nil))))))))
        ;; Remove empty results and limit output
        (filter some?)
        (take max-activities)
        vec))
    (catch Exception e
      (println (str "Error fetching Jira activity: " (.getMessage e)))
      [])))
