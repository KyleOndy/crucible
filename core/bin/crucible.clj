#!/usr/bin/env bb

(ns bin.crucible
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [lib.ai :as ai]
            [lib.cli :as cli]
            [lib.commands :as commands]
            [lib.config :as config]
            [lib.daily-log :as daily-log]
            [lib.draft-management :as draft-management]
            [lib.jira :as jira]
            [lib.process-detection :as process-detection]
            [lib.sprint-detection :as sprint-detection]
            [lib.story-creation :as story-creation]
            [lib.ticket-editor :as ticket-editor])
  (:import (java.time LocalDate LocalDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(defn quick-story-command
  "Create a quick Jira story with minimal input, via editor, or from file.
   Returns structured result with side effects for caller to handle."
  [args]
  (when-not (sequential? args)
    (throw (ex-info "Args must be sequential"
                    {:type :invalid-input, :value args})))
  (let [{:keys [args flags]} (cli/parse-flags args)
        summary (first args)
        {:keys [editor dry-run file ai no-ai ai-only desc list-drafts
                clean-drafts recover no-review iterate]}
          flags]
    ;; Handle draft management commands first
    (if-let [draft-result (draft-management/process-draft-commands
                            flags
                            quick-story-command)]
      ;; Process draft command results with structured returns
      (cond
        ;; Success with list-drafts
        (and (:success draft-result) list-drafts)
          (let [drafts (get-in draft-result [:result :drafts])]
            {:success true,
             :result :draft-list-displayed,
             :side-effect {:type :print-lines,
                           :lines (if (seq drafts)
                                    (map :filename drafts)
                                    ["No ticket drafts found"])}})
        ;; Success with clean-drafts
        (and (:success draft-result) clean-drafts)
          {:success true,
           :result :drafts-cleaned,
           :side-effect {:type :print-message,
                         :message "Draft cleanup completed"}}
        ;; Success with recover-draft
        (and (:success draft-result) recover)
          {:success true,
           :result :draft-recovered,
           :side-effect {:type :print-message,
                         :message (str "Recovering draft from: "
                                       (get-in draft-result
                                               [:result :draft-file]))}}
        ;; Error case
        (:error draft-result) {:error :draft-command-failed,
                               :message (:message draft-result),
                               :context {:action :exit-with-error}})
      ;; Normal ticket creation logic
      (let [initial-data-result
              (story-creation/get-initial-ticket-data flags summary desc)
            validation-result
              (story-creation/handle-missing-input initial-data-result flags)]
        ;; Handle validation errors
        (if (:error validation-result)
          {:error :validation-failed,
           :message (:message validation-result),
           :context (assoc (:context validation-result)
                      :action :exit-with-error),
           :side-effect {:type :print-usage-help,
                         :usage-help (get-in validation-result
                                             [:context :usage-help])}}
          ;; Continue with valid data
          (let [initial-data (:result validation-result)
                config (-> (config/load-config)
                           (config/apply-debug-flags flags))
                jira-config (:jira config)
                ai-config (:ai config)]
            ;; Choose enhancement method based on --iterate flag
            (if iterate
              ;; Use iterative enhancement loop
              (let [iterative-result (story-creation/iterative-enhancement
                                       initial-data
                                       flags
                                       ai-config)]
                (if (:error iterative-result)
                  {:error :iterative-enhancement-failed,
                   :message (:message iterative-result),
                   :context {:action :exit-with-error}}
                  (let [final-data (:result iterative-result)]
                    ;; Skip normal AI review since iterative enhancement
                    ;; handles it
                    (let [sprint-info (sprint-detection/run-sprint-detection
                                        jira-config)
                          _ (sprint-detection/log-sprint-debug-info sprint-info
                                                                    jira-config)
                          dry-run-result (story-creation/handle-dry-run-mode
                                           flags
                                           final-data
                                           sprint-info
                                           jira-config)]
                      ;; Handle dry-run mode
                      (if (= :dry-run (get-in dry-run-result [:result :mode]))
                        (let [result-data (:result dry-run-result)]
                          {:success true,
                           :result :dry-run-completed,
                           :side-effect {:type :dry-run-display,
                                         :title (:title result-data),
                                         :description (:description
                                                        result-data),
                                         :file (:file result-data)}})
                        ;; Continue with ticket creation
                        (let [jira-validation
                                (story-creation/validate-jira-config
                                  jira-config)]
                          ;; Handle Jira validation errors
                          (if (:error jira-validation)
                            {:error :jira-config-invalid,
                             :message "Jira configuration error",
                             :context {:missing-fields
                                         (get-in jira-validation
                                                 [:context :missing-fields]),
                                       :action :exit-with-error}}
                            ;; Create the ticket
                            (let [issue-data (story-creation/build-issue-data
                                               final-data
                                               jira-config)
                                  creation-result
                                    (story-creation/create-jira-ticket
                                      issue-data
                                      final-data
                                      jira-config
                                      flags)]
                              ;; Handle creation result
                              (if (:success creation-result)
                                (let [issue-key (get-in creation-result
                                                        [:result :issue-key])
                                      sprint-result
                                        (sprint-detection/add-ticket-to-sprint
                                          sprint-info
                                          jira-config
                                          issue-key)]
                                  {:success true,
                                   :result :ticket-created,
                                   :side-effect {:type :creation-success,
                                                 :issue-key issue-key,
                                                 :sprint-info sprint-info,
                                                 :sprint-result sprint-result}})
                                ;; Handle creation error
                                {:error :ticket-creation-failed,
                                 :message (:message creation-result),
                                 :context {:jira-error (get-in creation-result
                                                               [:context
                                                                :jira-error]),
                                           :action :exit-with-error}})))))))))
              ;; Use normal enhancement flow
              (let [enhanced-data (story-creation/apply-ai-enhancement
                                    initial-data
                                    flags
                                    ai-config)
                    ai-review-result (story-creation/handle-ai-review
                                       initial-data
                                       enhanced-data
                                       flags)]
                ;; Handle AI review errors
                (if (:error ai-review-result)
                  {:error :ai-review-failed,
                   :message (:message ai-review-result),
                   :context (assoc (:context ai-review-result)
                              :action :exit-with-error),
                   :side-effect {:type :print-error-details,
                                 :error-details (get-in ai-review-result
                                                        [:context
                                                         :original-error])}}
                  (let [final-data (if (:success ai-review-result)
                                     (:result ai-review-result)
                                     enhanced-data)
                        ai-only-result (story-creation/handle-ai-only-mode
                                         final-data
                                         initial-data
                                         enhanced-data
                                         flags)]
                    ;; Handle AI-only mode
                    (if (= :ai-only (get-in ai-only-result [:result :mode]))
                      (let [result-data (:result ai-only-result)]
                        {:success true,
                         :result :ai-only-completed,
                         :side-effect
                           {:type :ai-only-display,
                            :content-changed (:content-changed result-data),
                            :title (:title result-data),
                            :description (:description result-data)}})
                      ;; Continue with normal flow
                      (let [sprint-info (sprint-detection/run-sprint-detection
                                          jira-config)
                            _ (sprint-detection/log-sprint-debug-info
                                sprint-info
                                jira-config)
                            dry-run-result (story-creation/handle-dry-run-mode
                                             flags
                                             final-data
                                             sprint-info
                                             jira-config)]
                        ;; Handle dry-run mode
                        (if (= :dry-run (get-in dry-run-result [:result :mode]))
                          (let [result-data (:result dry-run-result)]
                            {:success true,
                             :result :dry-run-completed,
                             :side-effect {:type :dry-run-display,
                                           :title (:title result-data),
                                           :description (:description
                                                          result-data),
                                           :file (:file result-data)}})
                          ;; Continue with ticket creation
                          (let [jira-validation
                                  (story-creation/validate-jira-config
                                    jira-config)]
                            ;; Handle Jira validation errors
                            (if (:error jira-validation)
                              {:error :jira-config-invalid,
                               :message "Jira configuration error",
                               :context {:missing-fields
                                           (get-in jira-validation
                                                   [:context :missing-fields]),
                                         :action :exit-with-error}}
                              ;; Create the ticket
                              (let [issue-data (story-creation/build-issue-data
                                                 final-data
                                                 jira-config)
                                    creation-result
                                      (story-creation/create-jira-ticket
                                        issue-data
                                        final-data
                                        jira-config
                                        flags)]
                                ;; Handle creation result
                                (if (:success creation-result)
                                  (let [issue-key (get-in creation-result
                                                          [:result :issue-key])
                                        sprint-result
                                          (sprint-detection/add-ticket-to-sprint
                                            sprint-info
                                            jira-config
                                            issue-key)]
                                    {:success true,
                                     :result :ticket-created,
                                     :side-effect {:type :creation-success,
                                                   :issue-key issue-key,
                                                   :sprint-info sprint-info,
                                                   :sprint-result
                                                     sprint-result}})
                                  ;; Handle creation error
                                  {:error :ticket-creation-failed,
                                   :message (:message creation-result),
                                   :context
                                     {:jira-error (get-in creation-result
                                                          [:context
                                                           :jira-error]),
                                      :action
                                        :exit-with-error}})))))))))))))))))

;; Command registry for CLI dispatch
(def command-registry
  {:pipe commands/pipe-command,
   :quick-story quick-story-command,
   :inspect-ticket commands/inspect-ticket-command,
   :doctor commands/doctor-command,
   :init-prompt commands/init-prompt-command})

;; For bb execution
(apply cli/-main command-registry *command-line-args*)
