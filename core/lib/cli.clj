(ns lib.cli
  "CLI infrastructure for command parsing, help text, and dispatch"
  (:require
   [clojure.string :as str]
   [lib.config :as config]
   [lib.daily-log :as daily-log]
   [lib.jira :as jira]))

(def cli-spec
  {:help {:desc "Show help"
          :alias :h}})

(defn help-text
  []
  (str "Crucible - SRE productivity system\n\n"
       "Commands:\n"
       "  help              Show this help\n"
       "  doctor            System health check\n"
       "  inspect-ticket <id> View ticket fields\n"
       "  l                 Open daily log\n"
       "  sd                Start day (enhanced log)\n"
       "  pipe [command]    Pipe stdin to log\n"
       "  qs <summary>      Create Jira story\n\n"
       "Quick Story Options:\n"
       "  -e, --editor      Open editor\n"
       "  -f, --file <file> From file\n"
       "  --dry-run         Preview only\n"
       "  --ai              Enable AI\n"
       "  --no-ai           Disable AI\n"
       "  --ai-only         AI test only\n"
       "  --list-drafts     Show drafts\n"
       "  --recover <file>  Recover draft\n"
       "  --clean-drafts    Clean old drafts\n"))

(defn parse-flags
  "Simple flag parsing for commands. Returns {:args [...] :flags {...}}"
  [args]
  (let [flags (atom {})
        remaining-args (atom [])
        arg-iter (atom args)]
    (while (seq @arg-iter)
      (let [arg (first @arg-iter)
            rest-args (rest @arg-iter)]
        (cond
          (or (= arg "-e") (= arg "--editor"))
          (do
            (swap! flags assoc :editor true)
            (reset! arg-iter rest-args))

          (= arg "--dry-run")
          (do
            (swap! flags assoc :dry-run true)
            (reset! arg-iter rest-args))

          (= arg "--ai")
          (do
            (swap! flags assoc :ai true)
            (reset! arg-iter rest-args))

          (= arg "--no-ai")
          (do
            (swap! flags assoc :no-ai true)
            (reset! arg-iter rest-args))

          (= arg "--ai-only")
          (do
            (swap! flags assoc :ai-only true)
            (reset! arg-iter rest-args))

          (or (= arg "-f") (= arg "--file"))
          (if (seq rest-args)
            (do
              (swap! flags assoc :file (first rest-args))
              (reset! arg-iter (rest rest-args)))
            (System/exit 1))

          (or (= arg "-d") (= arg "--desc"))
          (if (seq rest-args)
            (do
              (swap! flags assoc :desc (first rest-args))
              (reset! arg-iter (rest rest-args)))
            (System/exit 1))

          (= arg "--list-drafts")
          (do
            (swap! flags assoc :list-drafts true)
            (reset! arg-iter rest-args))

          (= arg "--clean-drafts")
          (do
            (swap! flags assoc :clean-drafts true)
            (reset! arg-iter rest-args))

          (= arg "--recover")
          (if (seq rest-args)
            (do
              (swap! flags assoc :recover (first rest-args))
              (reset! arg-iter (rest rest-args)))
            (System/exit 1))

          (str/starts-with? arg "-")
          (do

            (reset! arg-iter rest-args))

          :else
          (do
            (swap! remaining-args conj arg)
            (reset! arg-iter rest-args)))))
    {:args @remaining-args :flags @flags}))

(defn dispatch-command
  [command args command-registry]
  (case command
    "help" (println (help-text))
    ("log" "l") (if (or (= command "l") (nil? (first args)))
                  (daily-log/open-daily-log)
                  (case (first args)
                    "daily" (daily-log/open-daily-log)
                    (println (str "Unknown log subcommand: " (first args)))))
    "pipe" (apply (:pipe command-registry) args)
    ("start-day" "sd") (daily-log/start-day-command)
    ("quick-story" "qs") ((:quick-story command-registry) args)
    "inspect-ticket" ((:inspect-ticket command-registry) args)
    "doctor" ((:doctor command-registry))
    (do
      (println (str "Unknown command: " command))
      (println)
      (println (help-text))
      (System/exit 1))))

(defn -main
  [command-registry & args]
  (let [command (first args)
        remaining-args (rest args)]
    (cond
      (or (= command "help") (= command "-h") (= command "--help")) (println (help-text))
      (or (empty? args) (nil? command)) (println (help-text))
      :else (dispatch-command command remaining-args command-registry))))