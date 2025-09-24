(ns lib.config.validation
  "Configuration validation and password manager integration"
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [lib.config.core :as config-core]))

(defn check-pass-command-availability
  "I/O function to check if pass command is available"
  []
  (try (let [result (process/shell {:out :string, :err :string} "which" "pass")]
         (if (= 0 (:exit result))
           {:success true, :result :available}
           {:error :pass-not-installed,
            :message "Pass command not found in PATH"}))
       (catch Exception e
         {:error :pass-check-failed,
          :message "Failed to check pass command availability",
          :context {:exception (.getMessage e)}})))

(defn execute-pass-command
  "I/O function to execute pass command and retrieve password"
  [pass-path]
  (try (let [result (process/shell {:out :string, :err :string, :continue true}
                                   "pass"
                                   "show"
                                   pass-path)]
         (if (= 0 (:exit result))
           {:success true, :result (str/trim (:out result))}
           {:error :pass-command-failed,
            :context {:exit-code (:exit result),
                      :stderr (:err result),
                      :pass-path pass-path}}))
       (catch Exception e
         {:error :pass-execution-failed,
          :message "Unexpected error executing pass command",
          :context {:pass-path pass-path, :exception (.getMessage e)}})))

(defn format-pass-error-message
  "Pure function to format detailed error messages for pass failures"
  [error-context]
  (let [{:keys [pass-path stderr]} error-context]
    (cond (str/includes? stderr "is not in the password store")
            (str "\n"
                 (str "ERROR: Password entry not found: " pass-path)
                 "\n\n"
                 "The entry '" pass-path
                 "' does not exist in your password store.\n\n"
                   "To check available entries:\n"
                 "  pass ls\n\n" "To add this entry:\n"
                 "  pass insert " pass-path
                 "\n\n" "Or update your config to use a different entry.")
          (str/includes? stderr "gpg: decryption failed")
            (str "\n"
                 "ERROR: GPG decryption failed" "\n\n"
                 "Could not decrypt the password entry.\n" "Possible causes:\n"
                 "  • GPG key not available or expired\n"
                   "  • GPG agent not running\n"
                 "  • Wrong GPG key used\n\n" "Try:\n"
                 "  gpg --list-secret-keys\n"
                   "  gpgconf --kill gpg-agent && gpgconf --launch gpg-agent")
          (str/includes? stderr "not initialized")
            (str "\n" "ERROR: Password store not initialized"
                 "\n\n" "Your password store has not been initialized.\n\n"
                 "To initialize:\n" "  pass init <your-gpg-id>\n\n"
                 "To find your GPG ID:\n" "  gpg --list-secret-keys")
          :else (str "\n"
                     (str "ERROR: Failed to retrieve password from pass: "
                          pass-path)
                     "\n\n"
                     "Pass command output:\n"
                     (when-not (str/blank? stderr)
                       (str "  " (str/replace stderr "\n" "\n  ") "\n\n"))
                     "To debug:\n"
                     "  pass show "
                     pass-path))))

(defn resolve-pass-value
  "Resolve a pass: prefixed value using decomposed I/O functions"
  [value]
  (if (and (string? value) (str/starts-with? value "pass:"))
    (let [pass-path (subs value 5)
          ;; I/O: Check if pass command is available
          availability-check (check-pass-command-availability)]
      (if (:success availability-check)
        ;; I/O: Execute pass command
        (let [pass-result (execute-pass-command pass-path)]
          (if (:success pass-result)
            (:result pass-result)
            ;; Handle pass execution errors
            (let [error-context (get pass-result :context {})
                  formatted-message
                    (case (:error pass-result)
                      :pass-command-failed (format-pass-error-message
                                             error-context)
                      :pass-execution-failed
                        (str "\n"
                             "ERROR: Unexpected error using pass"
                             "\n\n"
                             "Failed to retrieve: "
                             pass-path
                             "\n"
                             "Error: "
                             (get error-context :exception "Unknown error"))
                      ;; Default fallback
                      (str "Failed to retrieve password from: " pass-path))]
              (throw (ex-info formatted-message
                              (merge error-context {:pass-path pass-path}))))))
        ;; Handle availability check errors
        (let
          [error-message
             (case (:error availability-check)
               :pass-not-installed
                 (str
                   "\nERROR: 'pass' command not found\n\n"
                   "The password manager 'pass' is not installed or not in PATH.\n"
                     "Please install it first:\n"
                   "  • macOS:  brew install pass\n"
                     "  • Linux:  apt install pass  (or your distro's package manager)\n"
                   "  • Then:   pass init <your-gpg-id>\n\n"
                     "Learn more: https://www.passwordstore.org/")
               ;; Default fallback
               (str "Failed to check pass command availability: "
                    (get availability-check :message "Unknown error")))]
          (throw (ex-info error-message
                          {:pass-path pass-path,
                           :error-type (:error availability-check)})))))
    value))

(defn resolve-pass-references
  "Recursively resolve all pass: references in the config"
  [config]
  (cond (map? config)
          (into {} (map (fn [[k v]] [k (resolve-pass-references v)]) config))
        (string? config) (resolve-pass-value config)
        :else config))

(defn validate-jira-config
  "Validate that required Jira configuration is present"
  [config]
  (let
    [jira-config (:jira config)
     errors
       (cond-> []
         (not (:base-url jira-config))
           (conj
             "Missing Jira base URL (set in config file or CRUCIBLE_JIRA_URL)")
         (not (:username jira-config))
           (conj
             "Missing Jira username (set in config file or CRUCIBLE_JIRA_USER)")
         (not (:api-token jira-config))
           (conj
             "Missing Jira API token (set in config file or CRUCIBLE_JIRA_TOKEN)"))]
    (when (seq errors) errors)))

(defn print-config-error
  "Print a configuration error with helpful context"
  [errors]
  (println "Configuration error:")
  (doseq [error errors] (println (str "  - " error)))
  (println)
  (println (config-core/config-locations))
  (println)
  (println "Example configuration (~/.config/crucible/config.edn):")
  (println "{:jira {:base-url \"https://company.atlassian.net\"")
  (println "        :username \"user@company.com\"")
  (println "        :api-token \"pass:work/jira-token\"}}"))

(defn apply-debug-flags
  "Apply debug flags to config, overriding file-based settings"
  [config flags]
  (let [{:keys [debug debug-ai debug-jira debug-sprint]} flags]
    (cond-> config
      ;; Global debug flag enables all debugging
      debug (-> (assoc-in [:ai :debug] true)
                (assoc-in [:jira :debug] true)
                (assoc-in [:sprint :debug] true))
      ;; Specific debug flags override individual services
      debug-ai (assoc-in [:ai :debug] true)
      debug-jira (assoc-in [:jira :debug] true)
      debug-sprint (assoc-in [:sprint :debug] true))))