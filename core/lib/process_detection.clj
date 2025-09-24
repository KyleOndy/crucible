(ns lib.process-detection
  "Process detection utilities for identifying parent commands in pipe operations"
  (:require [babashka.process :as process]
            [clojure.string :as str]))

(defn clean-shell-wrapper
  "Remove bash shell wrappers from command string"
  [command-str]
  (when-not (str/blank? command-str)
    (let [trimmed (str/trim command-str)]
      (cond
        ;; Handle "bash -c command" format
        (str/starts-with? trimmed "bash -c ")
        (str/trim (str/replace trimmed "bash -c " ""))

        ;; Handle full path bash commands  
        (re-find #"/bin/bash -c (.+)" trimmed)
        (str/trim (second (re-find #"/bin/bash -c (.+)" trimmed)))

        ;; Return as-is if no wrapper detected
        :else trimmed))))

(defn extract-pipeline-command
  "Extract the sending command from a pipeline string"
  [full-command]
  (when (and full-command (str/includes? full-command " | "))
    (->> full-command
         (#(str/split % #" \| "))
         first
         str/trim
         clean-shell-wrapper)))

(defn process-command-string
  "Process a raw command string to extract the relevant command for pipe detection"
  [raw-command]
  (if (and raw-command (str/includes? raw-command " | "))
    (extract-pipeline-command raw-command)
    nil))

(defn get-parent-command-java
  "Use Java ProcessHandle API to detect parent command (cross-platform)"
  []
  (try
    (let [current-handle (java.lang.ProcessHandle/current)
          parent-handle (.parent current-handle)]

      (if (.isPresent parent-handle)
        (let [parent (.get parent-handle)
              info (.info parent)
              command-line (.commandLine info)]
          (if (.isPresent command-line)
            (let [full-cmd (.get command-line)
                  processed-cmd (process-command-string full-cmd)]
              {:success true
               :result {:command processed-cmd
                        :raw-command full-cmd
                        :method :processhandle}})
            {:success true
             :result {:command nil
                      :raw-command nil
                      :method :processhandle
                      :reason :no-command-line}}))

        {:success true
         :result {:command nil
                  :raw-command nil
                  :method :processhandle
                  :reason :no-parent}}))

    (catch Exception e
      {:error :processhandle-failed
       :message "Failed to get parent command via ProcessHandle"
       :context {:exception (.getMessage e)}})))

(defn get-parent-command-ps
  "Try to detect the command that's piping data to us using ps (fallback method)"
  []
  (try
    (let [current-pid (.pid (java.lang.ProcessHandle/current))
          ppid-result (process/shell {:out :string}
                                     (str "ps -o ppid= -p " current-pid))
          ppid (str/trim (:out ppid-result))]

      (if-not (str/blank? ppid)
        (let [parent-cmd-result (process/shell {:out :string}
                                               (str "ps -o args= -p " ppid))
              parent-cmd (str/trim (:out parent-cmd-result))]
          (if-not (str/blank? parent-cmd)
            (let [processed-cmd (process-command-string parent-cmd)]
              {:success true
               :result {:command processed-cmd
                        :raw-command parent-cmd
                        :method :ps}})
            {:success true
             :result {:command nil
                      :raw-command nil
                      :method :ps
                      :reason :empty-command}}))

        {:success true
         :result {:command nil
                  :raw-command nil
                  :method :ps
                  :reason :no-ppid}}))

    (catch Exception e
      {:error :ps-failed
       :message "Failed to get parent command via ps"
       :context {:exception (.getMessage e)}})))

(defn get-parent-command
  "Unified parent command detection with fallback strategy"
  []
  (let [java-result (get-parent-command-java)]
    (if (and (:success java-result) (get-in java-result [:result :command]))
      java-result

      (let [ps-result (get-parent-command-ps)]
        (if (and (:success ps-result) (get-in ps-result [:result :command]))
          ps-result

          {:success true
           :result {:command nil
                    :method :none
                    :java-attempt java-result
                    :ps-attempt ps-result}})))))
