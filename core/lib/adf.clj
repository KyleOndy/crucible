(ns lib.adf
  "Atlassian Document Format (ADF) conversion library.

   Converts markdown text to ADF JSON format for use with Atlassian products
   like Jira and Confluence. Supports comprehensive markdown syntax including:

   - Text formatting (bold, italic, code, strikethrough)
   - Headers (H1-H6)
   - Lists (ordered, unordered, nested)
   - Links and images
   - Tables
   - Code blocks
   - Blockquotes
   - Horizontal rules"
  (:require [clojure.string :as str]))

;; =============================================================================
;; ADF Document Structure
;; =============================================================================

(defn create-document
  "Create an ADF document with the given content nodes."
  [content]
  {:version 1, :type "doc", :content (if (empty? content) [] content)})

;; =============================================================================
;; ADF Node Constructors
;; =============================================================================

(defn create-text-node
  "Create a text node with optional marks (formatting)."
  ([text] (create-text-node text nil))
  ([text marks] (cond-> {:type "text", :text text} marks (assoc :marks marks))))

(defn create-paragraph
  "Create a paragraph node containing text content."
  [content]
  (when (seq content) {:type "paragraph", :content content}))

(defn create-heading
  "Create a heading node with the specified level (1-6) and content."
  [level content]
  (when (and (<= 1 level 6) (seq content))
    {:type "heading", :attrs {:level level}, :content content}))

(defn create-bullet-list
  "Create an unordered (bullet) list with the given items."
  [items]
  (when (seq items) {:type "bulletList", :content items}))

(defn create-ordered-list
  "Create an ordered (numbered) list with the given items."
  [items]
  (when (seq items) {:type "orderedList", :content items}))

(defn create-list-item
  "Create a list item containing paragraph content."
  [content]
  {:type "listItem", :content (if (vector? content) content [content])})

(defn create-code-block
  "Create a code block with optional language specification."
  ([content] (create-code-block content nil))
  ([content language]
   {:type "codeBlock",
    :attrs (if language {:language language} {:language "text"}),
    :content [{:type "text", :text content}]}))

(defn create-blockquote
  "Create a blockquote containing the given content."
  [content]
  (when (seq content) {:type "blockquote", :content content}))

(defn create-rule "Create a horizontal rule (divider)." [] {:type "rule"})

(defn create-media-single
  "Create a media node for images."
  [url alt-text]
  {:type "mediaSingle",
   :attrs {:layout "center"},
   :content [{:type "media",
              :attrs {:type "external", :url url, :alt (or alt-text "")}}]})

(defn create-table
  "Create a table with rows containing header and/or data cells."
  [rows]
  (when (seq rows)
    {:type "table",
     :attrs {:isNumberColumnEnabled false, :layout "default"},
     :content rows}))

(defn create-table-row
  "Create a table row containing cells."
  [cells]
  (when (seq cells) {:type "tableRow", :content cells}))

(defn create-table-header
  "Create a table header cell with content."
  [content]
  (when (seq content) {:type "tableHeader", :attrs {}, :content content}))

(defn create-table-cell
  "Create a table data cell with content."
  [content]
  (when (seq content) {:type "tableCell", :attrs {}, :content content}))

;; =============================================================================
;; Inline Formatting Support
;; =============================================================================

(defn create-strong-mark
  "Create a strong (bold) formatting mark."
  []
  {:type "strong"})

(defn create-em-mark
  "Create an emphasis (italic) formatting mark."
  []
  {:type "em"})

(defn create-code-mark
  "Create an inline code formatting mark."
  []
  {:type "code"})

(defn create-strike-mark
  "Create a strikethrough formatting mark."
  []
  {:type "strike"})

(defn create-link-mark
  "Create a link formatting mark with the given href."
  [href]
  {:type "link", :attrs {:href href}})

;; =============================================================================
;; Parsing Functions
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
    :bold (create-text-node (:text match) [(create-strong-mark)])
    :italic (create-text-node (:text match) [(create-em-mark)])
    :code (create-text-node (:text match) [(create-code-mark)])
    :strike (create-text-node (:text match) [(create-strike-mark)])
    :custom-link (create-text-node (:text match)
                                   [(create-link-mark (:href match))])
    :url (create-text-node (:text match) [(create-link-mark (:href match))])))

(defn- add-plain-text-if-needed
  "Add plain text node if there's content between current position and match start."
  [nodes text pos match-start]
  (if (< pos match-start)
    (conj nodes (create-text-node (subs text pos match-start)))
    nodes))

(defn- add-remaining-text-if-needed
  "Add any remaining plain text after all matches are processed."
  [nodes text pos]
  (if (< pos (count text))
    (conj nodes (create-text-node (subs text pos)))
    nodes))

(defn build-content-nodes
  "Build ADF content nodes from text and formatting matches.
   Processes matches in order, adding plain text between formatted sections."
  [text matches]
  (if (empty? matches)
    (if (str/blank? text) [] [(create-text-node text)])
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
                         cleaned-cells (map (fn [cell]
                                              (let [content (str/trim cell)]
                                                (if (str/blank? content)
                                                  [(create-paragraph
                                                     [(create-text-node "")])]
                                                  [(create-paragraph
                                                     (parse-inline-formatting
                                                       content))])))
                                         cells)]
                     ;; First row is headers, rest are data
                     (if (= line-idx 0)
                       (create-table-row (map #(create-table-header %)
                                           cleaned-cells))
                       (create-table-row (map #(create-table-cell %)
                                           cleaned-cells)))))
              (range)
              content-lines)]
      (when (seq parsed-rows) (create-table parsed-rows)))))

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
                             [(create-paragraph (parse-inline-formatting
                                                  content)) nested-list]
                             [(create-paragraph (parse-inline-formatting
                                                  content))])]
                     {:result (conj result
                                    (create-list-item list-item-content)),
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
          (create-bullet-list nested-structure)
          (create-ordered-list nested-structure))))))

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
      (re-matches (:header line-patterns) line) {:type :header, :line line}
      (re-matches (:bullet-list line-patterns) line)
        {:type :bullet-list,
         :line line,
         :indent (count (take-while #(= % \space) line))}
      (re-matches (:ordered-list line-patterns) line)
        {:type :ordered-list,
         :line line,
         :indent (count (take-while #(= % \space) line))}
      (re-matches (:blockquote line-patterns) line) {:type :blockquote,
                                                     :line line}
      (re-matches (:horizontal-rule line-patterns) line) {:type :rule}
      (and (str/includes? line "|") (not (str/starts-with? line ">")))
        {:type :table, :line line}
      (not (str/blank? line)) {:type :paragraph, :line line}
      :else {:type :empty})))

(defn- group-consecutive-lines
  "Group consecutive lines of the same type together."
  [lines]
  (reduce
    (fn [acc line]
      (let [last-group (last acc)
            classified (classify-line line last-group)]
        (case (:type classified)
          :code-block-end (update acc (dec (count acc)) assoc :closed? true)
          :code-block-content (update
                                acc
                                (dec (count acc))
                                #(update % :lines conj (:line classified)))
          :code-block-start (conj acc
                                  {:type :code-block,
                                   :language (when-not (str/blank?
                                                         (:language classified))
                                               (:language classified)),
                                   :lines [],
                                   :closed? false})
          :header (conj acc {:type :header, :line (:line classified)})
          (:bullet-list :ordered-list)
            (if (and last-group
                     (or (= (:type last-group) :bullet-list)
                         (= (:type last-group) :ordered-list))
                     (or (> (:indent classified) 0)
                         (= (:type last-group) (:type classified))))
              (update acc
                      (dec (count acc))
                      #(update % :lines conj (:line classified)))
              (conj acc
                    {:type (:type classified), :lines [(:line classified)]}))
          (:blockquote :table)
            (if (and last-group (= (:type last-group) (:type classified)))
              (update acc
                      (dec (count acc))
                      #(update % :lines conj (:line classified)))
              (conj acc
                    {:type (:type classified), :lines [(:line classified)]}))
          :rule (conj acc {:type :rule})
          :paragraph (conj acc {:type :paragraph, :line (:line classified)})
          :empty acc)))
    []
    lines))

(defn- convert-group-to-adf
  "Convert a grouped line structure to ADF format."
  [group]
  (case (:type group)
    :header (let [level (count (take-while #(= % \#) (:line group)))
                  heading-text (str/trim (subs (:line group) level))]
              (when (and (<= 1 level 6) (not (str/blank? heading-text)))
                (create-heading level (parse-inline-formatting heading-text))))
    :bullet-list (parse-nested-list (:lines group) :bullet-list)
    :ordered-list (parse-nested-list (:lines group) :ordered-list)
    :blockquote (let [quote-lines (map #(str/trim (subs % 2)) (:lines group))
                      quote-text (str/join "\n" quote-lines)]
                  (create-blockquote [(create-paragraph (parse-inline-formatting
                                                          quote-text))]))
    :code-block (when (:closed? group)
                  (create-code-block (str/join "\n" (:lines group))
                                     (:language group)))
    :rule (create-rule)
    :table (parse-table-lines (:lines group))
    :paragraph (create-paragraph (parse-inline-formatting (:line group)))
    nil))

(defn parse-block-elements
  "Parse block-level markdown elements (headers, lists, paragraphs, code blocks)"
  [text]
  (if (str/blank? text)
    []
    (->> text
         str/split-lines
         group-consecutive-lines
         (keep convert-group-to-adf))))

;; =============================================================================
;; Core API - Enhanced Version of text->adf
;; =============================================================================

(defn markdown->adf
  "Convert markdown text to Atlassian Document Format (ADF).

   This enhanced version supports comprehensive markdown syntax including:
   - Text formatting (bold, italic, code, strikethrough)
   - Headers (H1-H6)
   - Lists (ordered, unordered, nested)
   - Links and images
   - Tables
   - Code blocks
   - Blockquotes
   - Horizontal rules"
  [text]
  (if (str/blank? text)
    (create-document [])
    (let [paragraphs (str/split text #"\n\s*\n")
          content (->> paragraphs
                       (mapcat parse-block-elements)
                       (remove nil?))
          final-content (if (empty? content)
                          [(create-paragraph [(create-text-node text)])]
                          content)]
      (create-document final-content))))

;; =============================================================================
;; Parsing Functions (Temporary - will be enhanced)
;; =============================================================================

;; =============================================================================
;; Public API Aliases
;; =============================================================================

;; Maintain backward compatibility
(def text->adf markdown->adf)

;; Temporary debug function
