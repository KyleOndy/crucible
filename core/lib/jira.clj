(ns lib.jira
  "Main Jira namespace - re-exports from specialized namespaces for backward compatibility"
  (:require [clojure.string :as str]
            [lib.adf :as adf]
            [lib.jira.activity :as jira-activity]
            [lib.jira.auth :as jira-auth]
            [lib.jira.fields :as jira-fields]
            [lib.jira.sprints :as jira-sprints]
            [lib.jira.tickets :as jira-tickets]))

;; Core utility functions

;; Re-export from auth namespace for backward compatibility
(def make-auth-header jira-auth/make-auth-header)

;; Re-export from auth namespace for backward compatibility
(def jira-request jira-auth/jira-request)

;; Re-export from auth namespace for backward compatibility
(def test-connection jira-auth/test-connection)

;; Re-export from auth namespace for backward compatibility
(def get-user-info jira-auth/get-user-info)

;; Re-export from tickets namespace for backward compatibility
(def parse-ticket-id jira-tickets/parse-ticket-id)

;; Re-export from tickets namespace for backward compatibility
(def get-ticket jira-tickets/get-ticket)

;; Re-export from tickets namespace for backward compatibility
(def get-ticket-full jira-tickets/get-ticket-full)

;; Re-export from tickets namespace for backward compatibility
(def format-ticket-summary jira-tickets/format-ticket-summary)

;; Note: get-boards-for-project is private in sprints namespace - use
;; find-sprints instead

;; Re-export from sprints namespace for backward compatibility
(def get-project-active-sprints jira-sprints/get-project-active-sprints)

;; Re-export from sprints namespace for backward compatibility
(def find-sprints jira-sprints/find-sprints)

;; Re-export from sprints namespace for backward compatibility
(def add-issue-to-sprint jira-sprints/add-issue-to-sprint)

;; ADF conversion - delegated to lib.adf
(def text->adf adf/text->adf)

;; Legacy functions - these will be removed after full migration to lib.adf
(defn create-paragraph
  "Create ADF paragraph node from text"
  [text]
  (when-not (str/blank? text)
    {:type "paragraph", :content (adf/parse-inline-formatting text)}))

;; Remove old ADF functions - use lib.adf instead

;; Custom field formatting helpers

;; Re-export from fields namespace for backward compatibility
(def format-custom-field-value jira-fields/format-custom-field-value)

;; Re-export from fields namespace for backward compatibility
(def prepare-custom-fields jira-fields/prepare-custom-fields)

;; Re-export from fields namespace for backward compatibility
(def get-field-metadata jira-fields/get-field-metadata)

;; Re-export from fields namespace for backward compatibility
(def get-create-metadata jira-fields/get-create-metadata)

;; Re-export from fields namespace for backward compatibility
(def create-issue jira-fields/create-issue)

;; High-level functions

;; Re-export from activity namespace for backward compatibility
(def format-time-ago jira-activity/format-time-ago)

;; Re-export from activity namespace for backward compatibility
(def get-my-recent-activity jira-activity/get-my-recent-activity)

;; Re-export from sprints namespace for backward compatibility
(def get-current-sprint-info jira-sprints/get-current-sprint-info)
