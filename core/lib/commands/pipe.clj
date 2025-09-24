(ns lib.commands.pipe
  "Pipe command implementation for streaming stdin to daily log with command detection"
  (:require [clojure.string :as str]
            [lib.config :as config]
            [lib.daily-log :as daily-log]
            [lib.process-detection :as process-detection])
  (:import (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

;; Core pipe functionality

(defn process-stdin-input
  "Pure function to process and validate stdin content.
   Returns the cleaned content or nil if empty/blank."
  []
  (let [content (slurp *in*)] (when-not (str/blank? content) content)))

(defn detect-command-info
  "Detect command information from explicit args or auto-detection.
   Returns map with :command, :method, and :is-detected keys."
  [explicit-command stdin-content]
  (if explicit-command
    {:command explicit-command, :method nil, :is-detected false}
    (if (str/blank? stdin-content)
      {:command nil, :method nil, :is-detected false}
      (let [detection-result (process-detection/get-parent-command)
            command (get-in detection-result [:result :command])
            method (get-in detection-result [:result :method])]
        {:command command, :method method, :is-detected true}))))

(defn format-log-entry
  "Pure function to format log entry content with timestamp and command info.
   Returns formatted string ready for insertion into log."
  [stdin-content command-info]
  (let [timestamp (LocalDateTime/now)
        time-formatter (DateTimeFormatter/ofPattern "HH:mm:ss")
        formatted-time (.format timestamp time-formatter)
        working-dir (System/getProperty "user.dir")
        {:keys [command method is-detected]} command-info
        method-text (case method
                      :processhandle "(auto-detected via ProcessHandle)\n"
                      :ps "(auto-detected via ps)\n"
                      :none ""
                      nil "")
        header-text (if command
                      (str "### Command output at "
                           formatted-time
                           "\n"
                           "Working directory: "
                           working-dir
                           "\n"
                           "Command: `" command
                           "`\n" (when is-detected method-text))
                      (str "### Command output at "
                           formatted-time
                           "\n"
                           "Working directory: "
                           working-dir
                           "\n"))]
    (str "\n"
         header-text
         "\n"
         "```bash\n"
         stdin-content
         (when-not (str/ends-with? stdin-content "\n") "\n")
         "```\n")))

(defn insert-log-content
  "I/O function to insert content into the Commands & Outputs section of the daily log.
   Returns {:success true :result log-path} or {:error type :message msg :context {...}}"
  [log-path content-to-append]
  (try
    (daily-log/create-daily-log-from-template log-path)
    (let
      [current-content (slurp (str log-path))
       sections-pattern
         #"(?m)^## Commands & Outputs.*\n(?:<!-- .*? -->\n)?((?:(?!^##).*\n)*)"
       next-section-pattern #"(?m)^## (?!Commands & Outputs)"
       matcher (re-matcher sections-pattern current-content)]
      (if (.find matcher)
        (let [section-end (.end matcher)
              remaining-content (subs current-content section-end)
              next-matcher (re-matcher next-section-pattern remaining-content)
              insert-position (if (.find next-matcher)
                                (+ section-end (.start next-matcher))
                                (.length current-content))
              before-insert (subs current-content 0 insert-position)
              after-insert (subs current-content insert-position)
              new-content (str before-insert content-to-append after-insert)]
          (spit (str log-path) new-content)
          {:success true, :result log-path})
        ;; Fallback to appending at the end if section not found
        (do (spit (str log-path) content-to-append :append true)
            {:success true, :result log-path})))
    (catch Exception e
      {:error :file-operation-failed,
       :message (.getMessage e),
       :context {:log-path log-path, :operation :insert-content}})))

;; Helper functions for pipe pipeline

(defn- write-to-stdout
  "Write content to stdout with flushing for tee-like behavior.
   Side-effect function for output display."
  [content]
  (print content)
  (flush))

(defn- format-pipe-feedback
  "Format user feedback message for pipe command.
   Returns formatted string based on command detection."
  [log-path command-info]
  (if (:is-detected command-info)
    (let [method-value (:method command-info)
          command-value (:command command-info)
          method-name (cond (= method-value :processhandle) "ProcessHandle"
                            (= method-value :ps) "ps"
                            (= method-value :none) "auto-detection"
                            (nil? method-value) "auto-detection"
                            :else "auto-detection")]
      (if (and command-value (not (str/blank? command-value)))
        ;; We have a command - show it
        (config/format-success (str "Output piped to "
                                    log-path
                                    " (detected via "
                                    method-name
                                    ": "
                                    command-value
                                    ")"))
        ;; No command detected - show simpler message
        (config/format-success
          (str "Output piped to " log-path " (piped input detected)"))))
    (config/format-success (str "Output piped to " log-path))))

(defn- provide-user-feedback
  "Provide feedback to user about pipe operation.
   Side-effect function for user notification."
  [log-path command-info]
  (println (format-pipe-feedback log-path command-info)))

(defn- process-pipe-input
  "Process the complete pipe operation pipeline.
   Returns {:success true :result log-path} or error map."
  [stdin-content explicit-command]
  (let [command-info (detect-command-info explicit-command stdin-content)
        log-path (daily-log/get-daily-log-path)
        formatted-content (format-log-entry stdin-content command-info)]
    ;; Write to stdout (tee-like behavior)
    (write-to-stdout stdin-content)
    ;; Insert content into log
    (let [insert-result (insert-log-content log-path formatted-content)]
      (when (:success insert-result)
        (provide-user-feedback log-path command-info))
      insert-result)))

;; Main pipe command function

(defn pipe-command
  "Pipeline orchestration function for piping stdin to daily log with command detection.
   Returns {:success true :result log-path} or {:error type :message msg :context {...}}"
  [& args]
  (if-let [stdin-content (process-stdin-input)]
    (process-pipe-input stdin-content (first args))
    (do (println "No input received from stdin")
        {:success false,
         :error {:type :no-input, :message "No input received from stdin"}})))