(ns lib.cli
  "CLI infrastructure for command parsing, help text, and dispatch"
  (:require [clojure.string :as str]
            [lib.config :as config]
            [lib.daily-log :as daily-log]
            [lib.jira :as jira]))

(def cli-spec {:help {:desc "Show help", :alias :h}})

(defn help-text
  []
  (str "Crucible - SRE productivity system\n\n" "Commands:\n"
       "  help              Show this help\n"
       "  doctor            System health check\n"
       "  init-prompt       Initialize AI prompt template\n"
       "  inspect-ticket <id> View ticket fields\n"
       "  l                 Open daily log\n"
       "  sd                Start day (enhanced log)\n"
       "  pipe [command]    Pipe stdin to log\n"
       "  qs <summary>      Create Jira story\n\n" "Quick Story Options:\n"
       "  -e, --editor      Open editor\n" "  -f, --file <file> From file\n"
       "  --dry-run         Preview only\n" "  --ai              Enable AI\n"
       "  --no-ai           Disable AI\n" "  --ai-only         AI test only\n"
       "  --list-drafts     Show drafts\n" "  --recover <file>  Recover draft\n"
       "  --clean-drafts    Clean old drafts\n\n" "Debug Options:\n"
       "  --debug           Enable all debug modes\n"
       "  --debug-ai        Debug AI API calls\n"
       "  --debug-jira      Debug Jira API calls\n"
       "  --debug-sprint    Debug sprint detection\n"))

(defn parse-flags
  "Simple flag parsing for commands. Returns {:args [...] :flags {...}} or {:error ...}"
  [args]
  (when-not (sequential? args)
    (throw (ex-info "Args must be sequential"
                    {:type :invalid-input :value args})))
  (let [process-flag (fn [{:keys [args flags remaining] :as acc} arg]
                       (cond
                         ;; Boolean flags without values
                         (#{"-e" "--editor"} arg)
                         (-> acc
                             (assoc-in [:flags :editor] true)
                             (update :remaining rest))

                         (= arg "--dry-run")
                         (-> acc
                             (assoc-in [:flags :dry-run] true)
                             (update :remaining rest))

                         (= arg "--ai")
                         (-> acc
                             (assoc-in [:flags :ai] true)
                             (update :remaining rest))

                         (= arg "--no-ai")
                         (-> acc
                             (assoc-in [:flags :no-ai] true)
                             (update :remaining rest))

                         (= arg "--ai-only")
                         (-> acc
                             (assoc-in [:flags :ai-only] true)
                             (update :remaining rest))

                         (= arg "--debug")
                         (-> acc
                             (assoc-in [:flags :debug] true)
                             (update :remaining rest))

                         (= arg "--debug-ai")
                         (-> acc
                             (assoc-in [:flags :debug-ai] true)
                             (update :remaining rest))

                         (= arg "--debug-jira")
                         (-> acc
                             (assoc-in [:flags :debug-jira] true)
                             (update :remaining rest))

                         (= arg "--debug-sprint")
                         (-> acc
                             (assoc-in [:flags :debug-sprint] true)
                             (update :remaining rest))

                         (= arg "--list-drafts")
                         (-> acc
                             (assoc-in [:flags :list-drafts] true)
                             (update :remaining rest))

                         (= arg "--clean-drafts")
                         (-> acc
                             (assoc-in [:flags :clean-drafts] true)
                             (update :remaining rest))

                         ;; Flags that require values
                         (#{"-f" "--file"} arg)
                         (if-let [value (second remaining)]
                           (-> acc
                               (assoc-in [:flags :file] value)
                               (update :remaining #(drop 2 %)))
                           (assoc acc :error {:type :missing-flag-value
                                              :flag arg
                                              :message (str "Flag " arg " requires a value")}))

                         (#{"-d" "--desc"} arg)
                         (if-let [value (second remaining)]
                           (-> acc
                               (assoc-in [:flags :desc] value)
                               (update :remaining #(drop 2 %)))
                           (assoc acc :error {:type :missing-flag-value
                                              :flag arg
                                              :message (str "Flag " arg " requires a value")}))

                         (= arg "--recover")
                         (if-let [value (second remaining)]
                           (-> acc
                               (assoc-in [:flags :recover] value)
                               (update :remaining #(drop 2 %)))
                           (assoc acc :error {:type :missing-flag-value
                                              :flag arg
                                              :message "Flag --recover requires a value"}))

                         ;; Unknown flags (skip them)
                         (str/starts-with? arg "-")
                         (update acc :remaining rest)

                         ;; Regular arguments
                         :else
                         (-> acc
                             (update :args conj arg)
                             (update :remaining rest))))

        initial-state {:args [] :flags {} :remaining args :error nil}

        final-state (reduce (fn [acc _]
                              (if (or (:error acc) (empty? (:remaining acc)))
                                acc
                                (let [current-arg (first (:remaining acc))]
                                  (process-flag acc current-arg))))
                            initial-state
                            (range (count args)))]

    (if (:error final-state)
      final-state
      {:args (:args final-state) :flags (:flags final-state)})))

(defn dispatch-command
  "Dispatch command to appropriate handler. Returns result or structured error."
  [command args command-registry]
  (when-not (string? command)
    (throw (ex-info "Command must be a string"
                    {:type :invalid-input :value command})))
  (when-not (sequential? args)
    (throw (ex-info "Args must be sequential"
                    {:type :invalid-input :value args})))
  (when-not (map? command-registry)
    (throw (ex-info "Command registry must be a map"
                    {:type :invalid-input :value command-registry})))
  (case command
    "help" {:success true :result :help-displayed :side-effect (println (help-text))}

    ("log" "l")
    (let [result (cond
                   ;; Shorthand 'l' with args goes directly to relative date
                   (and (= command "l") (seq args))
                   (daily-log/open-log-for-relative-date (first args))

                   ;; Shorthand 'l' without args opens today's log
                   (= command "l")
                   (daily-log/open-daily-log)

                   ;; Full 'log' command without args opens today's log
                   (nil? (first args))
                   (daily-log/open-daily-log)

                   ;; Full 'log daily' with optional date arg
                   (= (first args) "daily")
                   (if (seq (rest args))
                     (daily-log/open-log-for-relative-date (second args))
                     (daily-log/open-daily-log))

                   ;; Unknown subcommand
                   :else
                   {:error :unknown-subcommand
                    :message (str "Unknown log subcommand: " (first args))
                    :context {:action :exit-with-error}})]
      ;; Return error for caller to handle
      (if (:error result)
        result
        {:success true :result result}))

    "pipe"
    (try
      {:success true :result (apply (:pipe command-registry) args)}
      (catch Exception e
        {:error :command-failed
         :message (str "Pipe command failed: " (.getMessage e))
         :context {:command "pipe" :args args}}))

    ("start-day" "sd")
    (try
      {:success true :result (daily-log/start-day-command)}
      (catch Exception e
        {:error :command-failed
         :message (str "Start day command failed: " (.getMessage e))
         :context {:command "start-day"}}))

    ("quick-story" "qs")
    (try
      {:success true :result ((:quick-story command-registry) args)}
      (catch Exception e
        {:error :command-failed
         :message (str "Quick story command failed: " (.getMessage e))
         :context {:command "quick-story" :args args}}))

    "inspect-ticket"
    (try
      {:success true :result ((:inspect-ticket command-registry) args)}
      (catch Exception e
        {:error :command-failed
         :message (str "Inspect ticket command failed: " (.getMessage e))
         :context {:command "inspect-ticket" :args args}}))

    "doctor"
    (try
      {:success true :result ((:doctor command-registry))}
      (catch Exception e
        {:error :command-failed
         :message (str "Doctor command failed: " (.getMessage e))
         :context {:command "doctor"}}))

    "init-prompt"
    (try
      {:success true :result ((:init-prompt command-registry) args)}
      (catch Exception e
        {:error :command-failed
         :message (str "Init prompt command failed: " (.getMessage e))
         :context {:command "init-prompt" :args args}}))

    ;; Unknown command
    {:error :unknown-command
     :message (str "Unknown command: " command)
     :context {:available-commands ["help" "log" "l" "pipe" "start-day" "sd" "quick-story" "qs" "inspect-ticket" "doctor" "init-prompt"]
               :action :show-help}}))

(defn -main
  "Main CLI entry point with structured error handling"
  [command-registry & args]
  (when-not (map? command-registry)
    (throw (ex-info "Command registry must be a map"
                    {:type :invalid-input :value command-registry})))
  (let [command (first args)
        remaining-args (rest args)]
    (cond
      (or (= command "help") (= command "-h") (= command "--help"))
      (do (println (help-text)) {:success true :result :help-displayed})

      (or (empty? args) (nil? command))
      (do (println (help-text)) {:success true :result :help-displayed})

      :else
      (let [result (dispatch-command command remaining-args command-registry)]
        ;; Handle side effects and errors appropriately
        (cond
          ;; Success case
          (:success result)
          result

          ;; Error cases
          (:error result)
          (do
            (println (:message result))
            ;; Show help for unknown commands
            (when (= :show-help (get-in result [:context :action]))
              (println)
              (println (help-text)))
            ;; Exit with appropriate code
            (let [action (get-in result [:context :action] :exit-with-error)]
              (System/exit (if (= action :exit-gracefully) 0 1))))

          ;; Fallback
          :else result)))))
