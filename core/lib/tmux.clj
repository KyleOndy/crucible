(ns lib.tmux
  (:require
    [babashka.process :as process]
    [clojure.string :as str]))


(defn tmux-installed?
  "Check if tmux is installed"
  []
  (try
    (let [result (process/shell {:out :string :err :string} "which" "tmux")]
      (= 0 (:exit result)))
    (catch Exception _
      false)))


(defn session-exists?
  "Check if a tmux session exists"
  [session-name]
  (try
    (let [result (process/shell {:out :string :err :string} "tmux" "has-session" "-t" session-name)]
      (= 0 (:exit result)))
    (catch Exception _
      false)))


(defn create-session
  "Create a new tmux session"
  [session-name working-dir]
  (try
    (let [result (process/shell {:out :string :err :string}
                                "tmux" "new-session" "-d" "-s" session-name "-c" working-dir)]
      (if (= 0 (:exit result))
        {:success true}
        {:success false
         :error (:err result)}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))


(defn attach-session
  "Attach to a tmux session"
  [session-name]
  (try
    ;; Use inherit to attach to the terminal directly
    @(process/process ["tmux" "attach-session" "-t" session-name] {:inherit true})
    {:success true}
    (catch Exception e
      {:success false
       :error (.getMessage e)})))


(defn switch-to-session
  "Switch to a tmux session (create if doesn't exist)"
  [session-name working-dir]
  (if-not (tmux-installed?)
    {:success false
     :error "tmux is not installed. Please install tmux to use this feature."}
    (if (session-exists? session-name)
      (do
        (println (str "Attaching to existing session: " session-name))
        (attach-session session-name))
      (do
        (println (str "Creating new session: " session-name))
        (let [create-result (create-session session-name working-dir)]
          (if (:success create-result)
            (attach-session session-name)
            create-result))))))


(defn list-sessions
  "List all tmux sessions"
  []
  (try
    (let [result (process/shell {:out :string :err :string} "tmux" "list-sessions" "-F" "#{session_name}")]
      (if (= 0 (:exit result))
        {:success true
         :sessions (str/split-lines (:out result))}
        {:success false
         :sessions []}))
    (catch Exception _
      {:success false
       :sessions []})))


(defn kill-session
  "Kill a tmux session"
  [session-name]
  (try
    (let [result (process/shell {:out :string :err :string} "tmux" "kill-session" "-t" session-name)]
      (if (= 0 (:exit result))
        {:success true}
        {:success false
         :error (:err result)}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))


(defn inside-tmux?
  "Check if we're already inside a tmux session"
  []
  (not (nil? (System/getenv "TMUX"))))
