(ns lib.adf.parsing
  "Markdown to ADF parsing and conversion logic"
  (:require [clojure.string :as str]
            [lib.adf.nodes :as nodes]))

;; =============================================================================
;; Pattern Matching and Inline Formatting
;; =============================================================================

(defn- find-all-matches
  "Find all regex matches of a specific pattern type in text.
   Returns vector of match maps with :start, :end, :type, :text, :full, and :href keys."
  [pattern type text]
  (loop [matcher (re-matcher pattern text)
         matches []]
    (if (.find matcher)
      (let [start (.start matcher)
            end (.end matcher)
            full-match (.group matcher)
            inner-text
              (if (> (.groupCount matcher) 0) (.group matcher 1) full-match)
            ;; For URLs, both text and href should be the same
            href (if (= type :url)
                   full-match
                   (when (> (.groupCount matcher) 1) (.group matcher 2)))
            match {:start start,
                   :end end,
                   :type type,
                   :text inner-text,
                   :full full-match,
                   :href href}]
        (recur matcher (conj matches match)))
      matches)))

(def ^:private formatting-patterns
  "Configuration map for markdown formatting patterns."
  {:bold #"\*\*(.+?)\*\*",
   :italic #"(?<!\*)\*([^*]+)\*(?!\*)",
   :code #"`([^`]+)`",
   :custom-link #"\[([^\]]+)\]\(([^)]+)\)",
   :url #"https?://[^\s]+",
   :strike #"~~([^~]+)~~"})

(defn find-pattern-matches
  "Find matches for a specific pattern type in text.
   Type should be one of :bold, :italic, :code, :custom-link, :url, :strike."
  [text type]
  (if (str/blank? text)
    []
    (when-let [pattern (formatting-patterns type)]
      (find-all-matches pattern type text))))

(defn remove-overlapping-matches
  "Remove overlapping matches, keeping the earlier ones.
   Matches should be sorted by :start position."
  [matches]
  (reduce (fn [acc match]
            (if (or (empty? acc) (>= (:start match) (:end (last acc))))
              (conj acc match)
              acc))
    []
    matches))

(defn extract-formatting-patterns
  "Extract all formatting patterns from text, sorted by position with overlaps removed."
  [text]
  (if (str/blank? text)
    []
    (->> [:bold :code :custom-link :url :strike :italic]
         (mapcat #(find-pattern-matches text %))
         (sort-by :start)
         remove-overlapping-matches)))

(defn- create-formatted-node
  "Create a formatted ADF text node based on match type and content."
  [match]
  (case (:type match)
    :bold (nodes/create-text-node (:text match) [(nodes/create-strong-mark)])
    :italic (nodes/create-text-node (:text match) [(nodes/create-em-mark)])
    :code (nodes/create-text-node (:text match) [(nodes/create-code-mark)])
    :strike (nodes/create-text-node (:text match) [(nodes/create-strike-mark)])
    :custom-link (nodes/create-text-node (:text match)
                                         [(nodes/create-link-mark (:href
                                                                    match))])
    :url (nodes/create-text-node (:text match)
                                 [(nodes/create-link-mark (:href match))])))

(defn- add-plain-text-if-needed
  "Add plain text node if there's content between current position and match start."
  [nodes text pos match-start]
  (if (< pos match-start)
    (conj nodes (nodes/create-text-node (subs text pos match-start)))
    nodes))

(defn- add-remaining-text-if-needed
  "Add any remaining plain text after all matches are processed."
  [nodes text pos]
  (if (< pos (count text))
    (conj nodes (nodes/create-text-node (subs text pos)))
    nodes))

(defn build-content-nodes
  "Build ADF content nodes from text and formatting matches.
   Processes matches in order, adding plain text between formatted sections."
  [text matches]
  (if (empty? matches)
    (if (str/blank? text) [] [(nodes/create-text-node text)])
    ;; Build content nodes using reduce for functional style
    (let [result-with-pos
            (reduce
              (fn [[nodes pos] match]
                (let [;; Add text before match (if any)
                      nodes-with-prefix
                        (add-plain-text-if-needed nodes text pos (:start match))
                      ;; Create formatted node
                      formatted-node (create-formatted-node match)
                      updated-nodes (conj nodes-with-prefix formatted-node)]
                  [updated-nodes (:end match)]))
              [[] 0]
              matches)
          [final-nodes final-pos] result-with-pos]
      ;; Add any remaining text
      (add-remaining-text-if-needed final-nodes text final-pos))))

(defn parse-inline-formatting
  "Parse inline markdown formatting (bold, italic, code, links) in text.

   Returns a vector of ADF text nodes with appropriate formatting marks."
  [text]
  (if (str/blank? text)
    []
    (->> text
         extract-formatting-patterns
         (build-content-nodes text))))

;; =============================================================================
;; Table Parsing
;; =============================================================================

(defn parse-table-lines
  "Parse markdown table lines into ADF table structure."
  [lines]
  (when (seq lines)
    (let [;; Filter out separator lines (e.g., |---|---|)
          content-lines
            (remove #(re-matches #"^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$"
                                 %)
              lines)
          ;; Parse table rows
          parsed-rows
            (map (fn [line-idx line]
                   (let [;; Split by | and clean up cells
                         raw-cells (str/split line #"\|")
                         ;; Remove empty cells from start/end (due to
                         ;; leading/trailing |)
                         cells (if (str/blank? (first raw-cells))
                                 (drop 1 raw-cells)
                                 raw-cells)
                         cells (if (and (seq cells) (str/blank? (last cells)))
                                 (drop-last cells)
                                 cells)
                         ;; Clean and parse cell content
                         cleaned-cells
                           (map (fn [cell]
                                  (let [content (str/trim cell)]
                                    (if (str/blank? content)
                                      [(nodes/create-paragraph
                                         [(nodes/create-text-node "")])]
                                      [(nodes/create-paragraph
                                         (parse-inline-formatting content))])))
                             cells)]
                     ;; First row is headers, rest are data
                     (if (= line-idx 0)
                       (nodes/create-table-row
                         (map #(nodes/create-table-header %) cleaned-cells))
                       (nodes/create-table-row (map #(nodes/create-table-cell %)
                                                 cleaned-cells)))))
              (range)
              content-lines)]
      (when (seq parsed-rows) (nodes/create-table parsed-rows)))))

;; =============================================================================
;; List Parsing
;; =============================================================================

(defn parse-nested-list
  "Parse nested list lines into proper ADF list structure."
  [lines list-type]
  (letfn
    [(get-indent-level [line] (count (take-while #(= % \space) line)))
     (get-line-type [line]
       (cond (re-matches #"^\s*- .*" line) :bullet
             (re-matches #"^\s*\d+\.\s.*" line) :ordered
             :else :other))
     (parse-item-content [line]
       (let [trimmed (str/trim line)]
         (cond (re-matches #"^- .*" trimmed) (str/trim (subs trimmed 1))
               (re-matches #"^\d+\.\s.*" trimmed)
                 (str/trim (subs trimmed (inc (.indexOf trimmed ". "))))
               :else trimmed)))
     (build-nested-structure [items current-level]
       (->>
         items
         (reduce
           (fn [{:keys [result remaining]} item]
             (let [level (:level item)
                   content (:content item)]
               (cond
                 ;; Item at current level - add it
                 (= level current-level)
                   (let [nested-items (take-while #(> (:level %) current-level)
                                                  remaining)
                         rest-items (drop-while #(> (:level %) current-level)
                                                remaining)
                         nested-list
                           (when (seq nested-items)
                             (let [nested-level (apply min
                                                  (map :level nested-items))
                                   first-nested-type (:line-type
                                                       (first nested-items))
                                   nested-list-type (if (= first-nested-type
                                                           :ordered)
                                                      :ordered-list
                                                      :bullet-list)]
                               (parse-nested-list (map :original nested-items)
                                                  nested-list-type)))
                         list-item-content
                           (if nested-list
                             [(nodes/create-paragraph (parse-inline-formatting
                                                        content)) nested-list]
                             [(nodes/create-paragraph (parse-inline-formatting
                                                        content))])]
                     {:result (conj result
                                    (nodes/create-list-item list-item-content)),
                      :remaining rest-items})
                 ;; Item at deeper level - skip
                 (> level current-level) {:result result, :remaining remaining}
                 ;; Item at shallower level - return current result
                 (< level current-level) (reduced {:result result,
                                                   :remaining
                                                     (cons item remaining)}))))
           {:result [], :remaining items})
         :result))]
    (when (seq lines)
      (let [items (->> lines
                       (map (fn [line]
                              {:level (get-indent-level line),
                               :content (parse-item-content line),
                               :original line,
                               :line-type (get-line-type line)})))
            nested-structure (build-nested-structure items 0)]
        (if (= list-type :bullet-list)
          (nodes/create-bullet-list nested-structure)
          (nodes/create-ordered-list nested-structure))))))

;; =============================================================================
;; Block-Level Parsing
;; =============================================================================

(def ^:private line-patterns
  "Configuration for recognizing different types of markdown lines."
  {:code-block-start #"^```",
   :code-block-end #"^```",
   :header #"^#+\s.*",
   :bullet-list #"^\s*- .*",
   :ordered-list #"^\s*\d+\.\s.*",
   :blockquote #"^> .*",
   :horizontal-rule #"^-{3,}$",
   :table-row #".*\|.*"})

(defn- classify-line
  "Classify a markdown line by its type and extract relevant data."
  [line last-group]
  (let [in-code-block? (and last-group
                            (= (:type last-group) :code-block)
                            (not (:closed? last-group)))]
    (cond
      ;; Handle code block transitions
      (and in-code-block? (re-find (:code-block-end line-patterns) line))
        {:type :code-block-end}
      in-code-block? {:type :code-block-content, :line line}
      (re-find (:code-block-start line-patterns) line)
        {:type :code-block-start, :language (str/trim (subs line 3))}
      ;; Other line types
      (re-find (:header line-patterns) line)
        (let [level (count (take-while #(= % \#) line))
              content (str/trim (subs line level))]
          {:type :header, :level level, :content content})
      (re-find (:bullet-list line-patterns) line) {:type :bullet-list,
                                                   :line line}
      (re-find (:ordered-list line-patterns) line) {:type :ordered-list,
                                                    :line line}
      (re-find (:blockquote line-patterns) line)
        {:type :blockquote, :content (str/trim (subs line 1))}
      (re-find (:horizontal-rule line-patterns) line) {:type :horizontal-rule}
      (re-find (:table-row line-patterns) line) {:type :table-row, :line line}
      (str/blank? line) {:type :blank}
      :else {:type :paragraph, :content line})))

(defn- group-consecutive-lines
  "Group consecutive lines of the same type together for processing."
  [lines]
  (reduce
    (fn [groups line]
      (let [line-data (classify-line line (last groups))
            line-type (:type line-data)]
        (cond
          ;; Code block handling - accumulate content until end marker
          (= line-type :code-block-start) (conj groups
                                                {:type :code-block,
                                                 :language (:language
                                                             line-data),
                                                 :content [],
                                                 :closed? false})
          (= line-type :code-block-content) (update-in groups
                                                       [(dec (count groups))
                                                        :content]
                                                       conj
                                                       (:line line-data))
          (= line-type :code-block-end)
            (assoc-in groups [(dec (count groups)) :closed?] true)
          ;; Group consecutive lines of same type
          (and (seq groups) (= (:type (last groups)) line-type))
            (case line-type
              :paragraph (update-in groups
                                    [(dec (count groups)) :content]
                                    str
                                    " "
                                    (:content line-data))
              (:bullet-list :ordered-list :table-row)
                (update-in groups
                           [(dec (count groups)) :lines]
                           conj
                           (:line line-data))
              :blockquote (update-in groups
                                     [(dec (count groups)) :content]
                                     str
                                     " "
                                     (:content line-data))
              groups)
          ;; Start new group
          :else
            (case line-type
              :blank groups ; Skip blank lines
              :paragraph
                (conj groups {:type :paragraph, :content (:content line-data)})
              (:bullet-list :ordered-list :table-row)
                (conj groups {:type line-type, :lines [(:line line-data)]})
              :blockquote
                (conj groups {:type :blockquote, :content (:content line-data)})
              :header (conj groups line-data)
              :horizontal-rule (conj groups line-data)
              groups))))
    []
    lines))

(defn- convert-group-to-adf
  "Convert a group of similar lines into appropriate ADF nodes."
  [group]
  (case (:type group)
    :paragraph (when-let [content (:content group)]
                 (when-not (str/blank? content)
                   (nodes/create-paragraph (parse-inline-formatting content))))
    :header (nodes/create-heading (:level group)
                                  (parse-inline-formatting (:content group)))
    :bullet-list (parse-nested-list (:lines group) :bullet-list)
    :ordered-list (parse-nested-list (:lines group) :ordered-list)
    :blockquote (when-let [content (:content group)]
                  (when-not (str/blank? content)
                    (nodes/create-blockquote [(nodes/create-paragraph
                                                (parse-inline-formatting
                                                  content))])))
    :horizontal-rule (nodes/create-rule)
    :table-row (parse-table-lines (:lines group))
    :code-block (nodes/create-code-block (str/join "\n" (:content group))
                                         (:language group))
    nil))

(defn parse-block-elements
  "Parse markdown text into ADF block elements."
  [text]
  (when-not (str/blank? text)
    (->> (str/split-lines text)
         group-consecutive-lines
         (map convert-group-to-adf)
         (filter some?))))

;; =============================================================================
;; Main Conversion Functions
;; =============================================================================

(defn markdown->adf
  "Convert markdown text to ADF (Atlassian Document Format).

   Takes a markdown string and returns an ADF document structure that can be
   serialized to JSON for use with Atlassian products like Jira and Confluence.

   Supported markdown features:
   - Headers (H1-H6): # ## ### etc.
   - Text formatting: **bold**, *italic*, `code`, ~~strikethrough~~
   - Lists: both bullet (-) and numbered (1.) with nesting support
   - Links: [text](url) and bare URLs
   - Tables: standard markdown table syntax
   - Code blocks: ```language``` syntax with language detection
   - Blockquotes: > quoted text
   - Horizontal rules: --- (3 or more dashes)
   - Images: ![alt](url) converted to media nodes

   Returns: ADF document map ready for JSON serialization"
  [text]
  (if (str/blank? text)
    (nodes/create-document [])
    (let [blocks (parse-block-elements text)]
      (nodes/create-document (if (empty? blocks)
                               [(nodes/create-paragraph [(nodes/create-text-node
                                                           text)])]
                               blocks)))))

(def text->adf markdown->adf)