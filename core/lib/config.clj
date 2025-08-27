(ns lib.config
  (:require
    [babashka.fs :as fs]
    [babashka.process :as process]
    [clojure.edn :as edn]
    [clojure.string :as str]))


(def default-config
  {:jira {:base-url nil
          :username nil
          :api-token nil
          :default-project nil
          :default-issue-type "Task"
          :default-story-points 1
          :auto-assign-self true
          :auto-add-to-sprint true}


   :workspace {:root-dir "workspace"
               :logs-dir "logs"
               :docs-dir "docs"}

   :editor nil})


(defn expand-path
  "Expand ~ to user home directory"
  [path]
  (when path
    (if (str/starts-with? path "~")
      (str (System/getProperty "user.home") (subs path 1))
      path)))


(defn resolve-pass-value
  "Resolve a pass: prefixed value by calling the pass command"
  [value]
  (if (and (string? value) (str/starts-with? value "pass:"))
    (let [pass-path (subs value 5)]
      (try
        (let [result (process/shell {:out :string :err :string} "pass" "show" pass-path)]
          (str/trim (:out result)))
        (catch Exception e
          (throw (ex-info (str "Failed to retrieve password from pass: " pass-path)
                          {:pass-path pass-path
                           :error (.getMessage e)})))))
    value))


(defn resolve-pass-references
  "Recursively resolve all pass: references in the config"
  [config]
  (cond
    (map? config) (into {} (map (fn [[k v]] [k (resolve-pass-references v)]) config))
    (string? config) (resolve-pass-value config)
    :else config))


(defn deep-merge
  "Deep merge two maps, with m2 values taking precedence"
  [m1 m2]
  (cond
    (and (map? m1) (map? m2))
    (merge-with deep-merge m1 m2)

    (nil? m2) m1
    :else m2))


(defn load-edn-file
  "Load and parse an EDN file, returning nil if it doesn't exist"
  [path]
  (when (fs/exists? path)
    (try
      (edn/read-string (slurp path))
      (catch Exception e
        (throw (ex-info (str "Failed to parse config file: " path)
                        {:path path
                         :error (.getMessage e)}))))))


(defn load-home-config
  "Load config from user's home directory (tries XDG and legacy locations)"
  []
  (let [xdg-config-home (or (System/getenv "XDG_CONFIG_HOME")
                            (str (System/getProperty "user.home") "/.config"))
        xdg-config-path (fs/path xdg-config-home "crucible" "config.edn")
        legacy-config-path (fs/path (System/getProperty "user.home") ".crucible" "config.edn")]
    ;; Try XDG location first, then fall back to legacy location
    (or (load-edn-file xdg-config-path)
        (load-edn-file legacy-config-path))))


(defn load-project-config
  "Load config from current project directory"
  []
  (load-edn-file "crucible.edn"))


(defn get-env-override
  "Get environment variable override for a config path"
  [env-map [path-key & path-rest]]
  (case path-key
    :jira (case (first path-rest)
            :base-url (get env-map "CRUCIBLE_JIRA_URL")
            :username (get env-map "CRUCIBLE_JIRA_USER")
            :api-token (get env-map "CRUCIBLE_JIRA_TOKEN")
            nil)
    :workspace (when (= (first path-rest) :root-dir)
                 (get env-map "CRUCIBLE_WORKSPACE_DIR"))
    :editor (get env-map "EDITOR")
    nil))


(defn apply-env-overrides
  "Apply environment variable overrides to config"
  [config]
  (let [env-map (into {} (System/getenv))]
    (-> config
        (update-in [:jira :base-url] #(or (get-env-override env-map [:jira :base-url]) %))
        (update-in [:jira :username] #(or (get-env-override env-map [:jira :username]) %))
        (update-in [:jira :api-token] #(or (get-env-override env-map [:jira :api-token]) %))
        (update-in [:workspace :root-dir] #(or (get-env-override env-map [:workspace :root-dir]) %))
        (update [:editor] #(or (get-env-override env-map [:editor]) %)))))


(defn expand-workspace-paths
  "Expand workspace paths to absolute paths"
  [config]
  (let [root-dir (expand-path (get-in config [:workspace :root-dir]))]
    (-> config
        (assoc-in [:workspace :root-dir] root-dir)
        (update-in [:workspace :logs-dir] #(fs/path root-dir %))
        (update-in [:workspace :tickets-dir] #(fs/path root-dir %))
        (update-in [:workspace :docs-dir] #(fs/path root-dir %)))))


(defn load-config
  "Load configuration from all sources with proper precedence"
  []
  (-> default-config
      (deep-merge (load-home-config))
      (deep-merge (load-project-config))
      (apply-env-overrides)
      (resolve-pass-references)
      (expand-workspace-paths)))


(defn validate-jira-config
  "Validate that required Jira configuration is present"
  [config]
  (let [jira-config (:jira config)
        errors (cond-> []
                 (not (:base-url jira-config))
                 (conj "Missing Jira base URL (set in config file or CRUCIBLE_JIRA_URL)")

                 (not (:username jira-config))
                 (conj "Missing Jira username (set in config file or CRUCIBLE_JIRA_USER)")

                 (not (:api-token jira-config))
                 (conj "Missing Jira API token (set in config file or CRUCIBLE_JIRA_TOKEN)"))]
    (when (seq errors)
      errors)))


(defn config-locations
  "Return a string describing where config files are loaded from"
  []
  (str "Configuration is loaded from (in order of precedence):\n"
       "  1. ./crucible.edn (project-specific)\n"
       "  2. ~/.config/crucible/config.edn or ~/.crucible/config.edn (user config)\n"
       "  3. Environment variables (CRUCIBLE_*)\n"
       "  4. Built-in defaults"))


(defn print-config-error
  "Print a configuration error with helpful context"
  [errors]
  (println "Configuration error:")
  (doseq [error errors]
    (println (str "  - " error)))
  (println)
  (println (config-locations))
  (println)
  (println "Example configuration (~/.crucible/config.edn):")
  (println "{:jira {:base-url \"https://company.atlassian.net\"")
  (println "        :username \"user@company.com\"")
  (println "        :api-token \"pass:work/jira-token\"}}"))
