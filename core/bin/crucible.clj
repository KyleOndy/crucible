#!/usr/bin/env bb

(ns crucible.main
  (:require [babashka.cli :as cli]
            [clojure.string :as str]))

(def cli-spec
  {:help {:desc "Show help"
          :alias :h}})

(defn help-text []
  (str "Crucible - AI-powered SRE productivity system\n\n"
       "Usage: bb crucible <command> [options]\n\n"
       "Commands:\n"
       "  help              Show this help\n"
       "  log daily         Open today's daily log\n"
       "  pipe              Pipe stdin to daily log\n"
       "  work-on <ticket>  Start working on a ticket\n"
       "  sync-docs         Sync documentation from Confluence\n\n"
       "Development Commands:\n"
       "  nrepl             Start nREPL server for MCP integration\n"
       "  dev               Start development environment\n"
       "  clean             Clean REPL artifacts\n\n"
       "Use 'bb <command>' for convenience aliases:\n"
       "  bb l              Alias for 'bb crucible log daily'\n"
       "  bb pipe           Alias for 'bb crucible pipe'\n"
       "  bb work-on <id>   Alias for 'bb crucible work-on <id>'\n"))

(defn log-command [subcommand]
  (case subcommand
    "daily" (println "Opening daily log... (not implemented yet)")
    (println (str "Unknown log subcommand: " subcommand))))

(defn pipe-command []
  (println "Piping stdin to daily log... (not implemented yet)"))

(defn work-on-command [ticket-id]
  (if ticket-id
    (println (str "Starting work on ticket: " ticket-id " (not implemented yet)"))
    (println "Error: ticket ID required")))

(defn sync-docs-command []
  (println "Syncing documentation... (not implemented yet)"))

(defn dispatch-command [command args]
  (case command
    "help" (println (help-text))
    "log" (log-command (first args))
    "pipe" (pipe-command)
    "work-on" (work-on-command (first args))
    "sync-docs" (sync-docs-command)
    (do
      (println (str "Unknown command: " command))
      (println)
      (println (help-text))
      (System/exit 1))))

(defn -main [& args]
  (let [command (first args)
        remaining-args (rest args)]
    (cond
      (or (= command "help") (= command "-h") (= command "--help")) (println (help-text))
      (or (empty? args) (nil? command)) (println (help-text))
      :else (dispatch-command command remaining-args))))

;; For bb execution
 ;; For bb execution
(apply -main *command-line-args*)