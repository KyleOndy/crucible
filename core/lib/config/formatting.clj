(ns lib.config.formatting
  "Configuration display, formatting, and workspace management"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn ensure-single-directory
  "I/O function to ensure a single directory exists with structured result"
  [dir-path]
  (if (fs/exists? dir-path)
    {:success true, :result {:created false, :exists true, :path dir-path}}
    (try (fs/create-dirs dir-path)
         {:success true, :result {:created true, :exists true, :path dir-path}}
         (catch Exception e
           {:error :directory-creation-failed,
            :context {:path dir-path, :exception (.getMessage e)}}))))

(defn build-directory-results
  "Pure function to build directory creation results map"
  [directories workspace-config directory-operations-fn]
  (->> directories
       (reduce (fn [results [dir-key description]]
                 (let [dir-path (get workspace-config dir-key)
                       operation-result (directory-operations-fn dir-path)]
                   (if (:success operation-result)
                     (assoc results
                       dir-key (merge (:result operation-result)
                                      {:description description}))
                     (assoc results
                       dir-key {:created false,
                                :exists false,
                                :path dir-path,
                                :description description,
                                :error (get-in operation-result
                                               [:context :exception]
                                               "Unknown error")}))))
         {})))

(defn ensure-workspace-directories
  "Create missing workspace directories using decomposed I/O functions"
  [workspace-config]
  (let [directories [[:root-dir "Root workspace directory"]
                     [:logs-dir "Logs directory"]
                     [:tickets-dir "Tickets directory"]
                     [:docs-dir "Documentation directory"]
                     [:prompts-dir "Prompts directory"]]]
    ;; Use pure function to build results, passing I/O function as
    ;; parameter
    (build-directory-results directories
                             workspace-config
                             ensure-single-directory)))

(defn check-workspace-directories
  "Check which workspace directories are missing and return summary"
  [workspace-config]
  (let [directories [[:root-dir "Root workspace directory"]
                     [:logs-dir "Logs directory"]
                     [:tickets-dir "Tickets directory"]
                     [:docs-dir "Documentation directory"]]
        missing (->> directories
                     (filter (fn [[dir-key _]]
                               (not (fs/exists? (get workspace-config
                                                     dir-key))))))]
    {:total-dirs (count directories),
     :missing-dirs (count missing),
     :missing-list (->> missing
                        (map (fn [[dir-key desc]]
                               {:key dir-key,
                                :path (get workspace-config dir-key),
                                :description desc})))}))

(defn terminal-supports-color?
  "Check if the terminal supports color output"
  []
  (and (not (System/getenv "NO_COLOR")) ; Respect NO_COLOR standard
       (or (System/getenv "FORCE_COLOR") ; FORCE_COLOR overrides detection
           (and (System/console) ; Check if we're in a real terminal
                (or (System/getenv "COLORTERM") ; Modern terminal indicator
                    (when-let [term (System/getenv "TERM")]
                      (and (not= term "dumb") ; Not a dumb terminal
                           (or (str/includes? term "color")
                               (str/includes? term "xterm")
                               (str/includes? term "screen")
                               (str/includes? term "tmux")))))))))

(def color-codes
  "ANSI color codes for terminal output"
  {:red "\033[31m",
   :yellow "\033[33m",
   :green "\033[32m",
   :blue "\033[34m",
   :reset "\033[0m"})

(defn colorize
  "Apply color to text if terminal supports it"
  [text color]
  (when-not (string? text)
    (throw (ex-info "Text must be a string"
                    {:type :invalid-input, :value text})))
  (when-not (keyword? color)
    (throw (ex-info "Color must be a keyword"
                    {:type :invalid-input, :value color})))
  (if (terminal-supports-color?)
    (str (get color-codes color "") text (get color-codes :reset ""))
    text))

(defn red [text] (colorize text :red))

(defn yellow [text] (colorize text :yellow))

(defn green [text] (colorize text :green))

(defn blue [text] (colorize text :blue))

;; ASCII status indicators with optional color

(defn format-success
  "Format success indicator with optional color"
  [text]
  (str (green "[OK]") " " text))

(defn format-error
  "Format error indicator with optional color"
  [text]
  (str (red "[ERR]") " " text))

(defn format-warning
  "Format warning indicator with optional color"
  [text]
  (str (yellow "[WARN]") " " text))

(defn format-info
  "Format info indicator with optional color"
  [text]
  (str (blue "[INFO]") " " text))

(defn debug-log
  "Log a debug message to stderr with timestamp if debug is enabled for the section"
  [section config message]
  (when (get-in config [section :debug] false)
    (let [timestamp (.format (java.time.LocalDateTime/now)
                             (java.time.format.DateTimeFormatter/ofPattern
                               "yyyy-MM-dd HH:mm:ss"))
          section-name (str/upper-case (name section))]
      (binding [*out* *err*]
        (println (str "[" timestamp "] [" section-name "-DEBUG] " message))))))