(ns lib.process-detection
  "Process detection utilities for identifying parent commands in pipe operations"
  (:require
   [babashka.process :as process]
   [clojure.string :as str]))

(defn get-parent-command-java
  "Use Java ProcessHandle API to detect parent command (cross-platform)"
  []
  (try
    (let [current-handle (java.lang.ProcessHandle/current)
          parent-handle (.parent current-handle)]
      (when (.isPresent parent-handle)
        (let [parent (.get parent-handle)
              info (.info parent)
              command-line (.commandLine info)]
          (when (.isPresent command-line)
            (let [full-cmd (.get command-line)]
              ;; Extract just the command that's piping, not the full shell invocation
              (if (str/includes? full-cmd " | ")
                ;; If it's a pipeline, extract the part before the pipe
                (let [pipe-parts (str/split full-cmd #" \| ")
                      sending-command (str/trim (first pipe-parts))]
                  ;; Remove "bash -c" wrapper if present
                  ;; Remove shell wrapper if present and clean up
                  (let [cleaned-command (cond
                                          ;; Handle "bash -c command" format
                                          (str/starts-with? sending-command "bash -c ")
                                          (str/trim (str/replace sending-command "bash -c " ""))
                                          ;; Handle full path bash commands
                                          (re-find #"/bin/bash -c (.+)" sending-command)
                                          (str/trim (second (re-find #"/bin/bash -c (.+)" sending-command)))
                                          ;; Return as-is if no wrapper detected
                                          :else sending-command)]
                    cleaned-command))
                nil))))))
    (catch Exception _
      nil)))

(defn get-parent-command-ps
  "Try to detect the command that's piping data to us using ps (fallback method)"
  []
  (try
    (let [current-pid (.pid (java.lang.ProcessHandle/current))
          ppid (-> (process/shell {:out :string} (str "ps -o ppid= -p " current-pid))
                   :out
                   str/trim)
          parent-cmd (when (not (str/blank? ppid))
                       (-> (process/shell {:out :string} (str "ps -o args= -p " ppid))
                           :out
                           str/trim))]
      (when parent-cmd
        ;; Extract just the command that's piping, not the full shell invocation
        (if (str/includes? parent-cmd " | ")
          ;; If it's a pipeline, extract the part before the pipe
          (let [pipe-parts (str/split parent-cmd #" \| ")
                sending-command (str/trim (first pipe-parts))]
            ;; Remove "bash -c" wrapper if present
            ;; Remove shell wrapper if present and clean up
            (let [cleaned-command (cond
                                    ;; Handle "bash -c command" format
                                    (str/starts-with? sending-command "bash -c ")
                                    (str/trim (str/replace sending-command "bash -c " ""))
                                    ;; Handle full path bash commands
                                    (re-find #"/bin/bash -c (.+)" sending-command)
                                    (str/trim (second (re-find #"/bin/bash -c (.+)" sending-command)))
                                    ;; Return as-is if no wrapper detected
                                    :else sending-command)]
              cleaned-command))
          nil)))
    (catch Exception _
      nil)))

(defn get-parent-command
  "Unified parent command detection with fallback strategy"
  []
  (let [java-result (get-parent-command-java)
        ps-result (when-not java-result (get-parent-command-ps))]
    {:command (or java-result ps-result)
     :method (cond
               java-result :processhandle
               ps-result :ps
               :else :none)}))