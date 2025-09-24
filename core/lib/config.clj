(ns lib.config
  "Configuration management facade - delegates to specialized modules"
  (:require [lib.config.core :as core]
            [lib.config.validation :as validation]
            [lib.config.formatting :as formatting]))

;; Re-export core configuration constants and functions
(def default-ai-prompt core/default-ai-prompt)
(def default-config core/default-config)

;; Re-export core configuration functions
(def expand-path core/expand-path)
(def deep-merge core/deep-merge)
(def read-file-content core/read-file-content)
(def parse-edn-content core/parse-edn-content)
(def load-edn-file core/load-edn-file)
(def load-home-config core/load-home-config)
(def load-project-config core/load-project-config)
(def get-env-override core/get-env-override)
(def apply-env-overrides core/apply-env-overrides)
(def expand-workspace-paths core/expand-workspace-paths)
(def normalize-sprint-config core/normalize-sprint-config)
(def resolve-prompt-file-path core/resolve-prompt-file-path)
(def load-prompt-file core/load-prompt-file)
(def resolve-prompt-files core/resolve-prompt-files)
(def config-locations core/config-locations)
(def get-config-file-status core/get-config-file-status)
(def get-env-var-status core/get-env-var-status)

;; Re-export validation functions
(def check-pass-command-availability validation/check-pass-command-availability)
(def execute-pass-command validation/execute-pass-command)
(def format-pass-error-message validation/format-pass-error-message)
(def resolve-pass-value validation/resolve-pass-value)
(def resolve-pass-references validation/resolve-pass-references)
(def validate-jira-config validation/validate-jira-config)
(def print-config-error validation/print-config-error)
(def apply-debug-flags validation/apply-debug-flags)

;; Re-export formatting functions
(def ensure-single-directory formatting/ensure-single-directory)
(def build-directory-results formatting/build-directory-results)
(def ensure-workspace-directories formatting/ensure-workspace-directories)
(def check-workspace-directories formatting/check-workspace-directories)
(def terminal-supports-color? formatting/terminal-supports-color?)
(def color-codes formatting/color-codes)
(def colorize formatting/colorize)
(def red formatting/red)
(def yellow formatting/yellow)
(def green formatting/green)
(def blue formatting/blue)
(def format-success formatting/format-success)
(def format-error formatting/format-error)
(def format-warning formatting/format-warning)
(def format-info formatting/format-info)
(def debug-log formatting/debug-log)

;; Main configuration loading function
(defn load-config
  "Load configuration from all sources with proper precedence"
  []
  (-> core/default-config
      (core/deep-merge (core/load-home-config))
      (core/deep-merge (core/load-project-config))
      core/apply-env-overrides
      validation/resolve-pass-references
      core/expand-workspace-paths
      core/normalize-sprint-config
      core/resolve-prompt-files))