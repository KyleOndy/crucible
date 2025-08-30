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
  (:require
   [clojure.string :as str]))

;; =============================================================================
;; ADF Document Structure
;; =============================================================================

(defn create-document
  "Create an ADF document with the given content nodes."
  [content]
  {:version 1
   :type "doc"
   :content (if (empty? content)
              []
              content)})

;; =============================================================================
;; ADF Node Constructors
;; =============================================================================

(defn create-text-node
  "Create a text node with optional marks (formatting)."
  ([text] (create-text-node text nil))
  ([text marks]
   (cond-> {:type "text" :text text}
     marks (assoc :marks marks))))

(defn create-paragraph
  "Create a paragraph node containing text content."
  [content]
  (when (seq content)
    {:type "paragraph"
     :content content}))

(defn create-heading
  "Create a heading node with the specified level (1-6) and content."
  [level content]
  (when (and (<= 1 level 6) (seq content))
    {:type "heading"
     :attrs {:level level}
     :content content}))

(defn create-bullet-list
  "Create an unordered (bullet) list with the given items."
  [items]
  (when (seq items)
    {:type "bulletList"
     :content items}))

(defn create-ordered-list
  "Create an ordered (numbered) list with the given items."
  [items]
  (when (seq items)
    {:type "orderedList"
     :content items}))

(defn create-list-item
  "Create a list item containing paragraph content."
  [content]
  {:type "listItem"
   :content (if (vector? content) content [content])})

(defn create-code-block
  "Create a code block with optional language specification."
  ([content] (create-code-block content nil))
  ([content language]
   {:type "codeBlock"
    :attrs (if language
             {:language language}
             {:language "text"})
    :content [{:type "text" :text content}]}))

(defn create-blockquote
  "Create a blockquote containing the given content."
  [content]
  (when (seq content)
    {:type "blockquote"
     :content content}))

(defn create-rule
  "Create a horizontal rule (divider)."
  []
  {:type "rule"})

(defn create-media-single
  "Create a media node for images."
  [url alt-text]
  {:type "mediaSingle"
   :attrs {:layout "center"}
   :content [{:type "media"
              :attrs {:type "external"
                      :url url
                      :alt (or alt-text "")}}]})

(defn create-table
  "Create a table with rows containing header and/or data cells."
  [rows]
  (when (seq rows)
    {:type "table"
     :attrs {:isNumberColumnEnabled false
             :layout "default"}
     :content rows}))

(defn create-table-row
  "Create a table row containing cells."
  [cells]
  (when (seq cells)
    {:type "tableRow"
     :content cells}))

(defn create-table-header
  "Create a table header cell with content."
  [content]
  (when (seq content)
    {:type "tableHeader"
     :attrs {}
     :content content}))

(defn create-table-cell
  "Create a table data cell with content."
  [content]
  (when (seq content)
    {:type "tableCell"
     :attrs {}
     :content content}))

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
  {:type "link" :attrs {:href href}})

 ;; =============================================================================
;; Parsing Functions
;; =============================================================================

(defn parse-inline-formatting
  "Parse inline markdown formatting (bold, italic, code, links) in text.
   
   Returns a vector of ADF text nodes with appropriate formatting marks."
  [text]
  (if (str/blank? text)
    []
    (let [;; Find matches for each pattern type, avoiding overlaps within each type
          find-all-matches (fn [pattern type text]
                             (let [matcher (re-matcher pattern text)
                                   matches (atom [])]
                               (while (.find matcher)
                                 (let [start (.start matcher)
                                       end (.end matcher)
                                       full-match (.group matcher)
                                       inner-text (if (> (.groupCount matcher) 0)
                                                    (.group matcher 1)
                                                    full-match)
                                       ;; For URLs, both text and href should be the same
                                       href (if (= type :url)
                                              full-match
                                              (when (> (.groupCount matcher) 1)
                                                (.group matcher 2)))]
                                   (swap! matches conj {:start start
                                                        :end end
                                                        :type type
                                                        :text inner-text
                                                        :full full-match
                                                        :href href})))
                               @matches))

          ;; Find all different types of formatting
          ;; Order matters: bold before italic to handle ** vs * correctly
          bold-matches (find-all-matches #"\*\*([^*]+)\*\*" :bold text)
          code-matches (find-all-matches #"`([^`]+)`" :code text)
          custom-link-matches (find-all-matches #"\[([^\]]+)\]\(([^)]+)\)" :custom-link text)
          url-matches (find-all-matches #"https?://[^\s]+" :url text)
          ;; Strikethrough matches
          strike-matches (find-all-matches #"~~([^~]+)~~" :strike text)
          ;; Italic last to avoid conflicts with bold
          italic-matches (find-all-matches #"(?<!\*)\*([^*]+)\*(?!\*)" :italic text)

          ;; Combine and sort all matches by position
          all-matches (concat bold-matches code-matches custom-link-matches url-matches strike-matches italic-matches)
          sorted-matches (sort-by :start all-matches)

          ;; Remove overlapping matches (keep the earlier one)
          non-overlapping (reduce (fn [acc match]
                                    (if (or (empty? acc)
                                            (>= (:start match) (:end (last acc))))
                                      (conj acc match)
                                      acc))
                                  [] sorted-matches)]

      ;; Build content nodes by processing matches in order
      (loop [pos 0
             matches non-overlapping
             nodes []]
        (if (empty? matches)
          ;; Add any remaining text
          (if (< pos (count text))
            (conj nodes (create-text-node (subs text pos)))
            nodes)
          ;; Process next match
          (let [match (first matches)
                remaining (rest matches)
                ;; Add text before match (if any)
                nodes-with-prefix (if (< pos (:start match))
                                    (conj nodes (create-text-node (subs text pos (:start match))))
                                    nodes)
                ;; Create formatted node
                formatted-node (case (:type match)
                                 :bold (create-text-node (:text match) [(create-strong-mark)])
                                 :italic (create-text-node (:text match) [(create-em-mark)])
                                 :code (create-text-node (:text match) [(create-code-mark)])
                                 :strike (create-text-node (:text match) [(create-strike-mark)])
                                 :custom-link (create-text-node (:text match) [(create-link-mark (:href match))])
                                 :url (create-text-node (:text match) [(create-link-mark (:href match))]))
                updated-nodes (conj nodes-with-prefix formatted-node)]
            (recur (:end match) remaining updated-nodes)))))))

(defn parse-table-lines
  "Parse markdown table lines into ADF table structure."
  [lines]
  (when (seq lines)
    (let [;; Filter out separator lines (e.g., |---|---|)
          content-lines (remove #(re-matches #"^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$" %) lines)
          ;; Parse table rows
          parsed-rows (map (fn [line-idx line]
                             (let [;; Split by | and clean up cells
                                   raw-cells (str/split line #"\|")
                                  ;; Remove empty cells from start/end (due to leading/trailing |)
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
                                                            [(create-paragraph [(create-text-node "")])]
                                                            [(create-paragraph (parse-inline-formatting content))])))
                                                      cells)]
                              ;; First row is headers, rest are data
                               (if (= line-idx 0)
                                 (create-table-row (map #(create-table-header %) cleaned-cells))
                                 (create-table-row (map #(create-table-cell %) cleaned-cells)))))
                           (range)
                           content-lines)]
      (when (seq parsed-rows)
        (create-table parsed-rows)))))

(defn parse-nested-list
  "Parse nested list lines into proper ADF list structure."
  [lines list-type]
  (letfn [(get-indent-level [line]
            (count (take-while #(= % \space) line)))

          (get-line-type [line]
            (cond
              (re-matches #"^\s*- .*" line) :bullet
              (re-matches #"^\s*\d+\.\s.*" line) :ordered
              :else :other))

          (parse-item-content [line]
            (let [trimmed (str/trim line)]
              (cond
                (re-matches #"^- .*" trimmed) (str/trim (subs trimmed 1))
                (re-matches #"^\d+\.\s.*" trimmed) (str/trim (subs trimmed (inc (.indexOf trimmed ". "))))
                :else trimmed)))

          (build-nested-structure [items current-level]
            (loop [items items
                   result []]
              (if (empty? items)
                result
                (let [item (first items)
                      remaining (rest items)
                      level (:level item)
                      content (:content item)]

                  (cond
                    ;; Item at current level - add it
                    (= level current-level)
                    (let [;; Look ahead for nested items
                          nested-items (take-while #(> (:level %) current-level) remaining)
                          rest-items (drop-while #(> (:level %) current-level) remaining)
                          nested-list (when (seq nested-items)
                                        (let [nested-level (apply min (map :level nested-items))
                                              ;; Determine nested list type based on first nested item
                                              first-nested-type (:line-type (first nested-items))
                                              nested-list-type (if (= first-nested-type :ordered) :ordered-list :bullet-list)]
                                          (parse-nested-list (map :original nested-items) nested-list-type)))]

                      (recur rest-items
                             (conj result
                                   (create-list-item
                                    (if nested-list
                                      [(create-paragraph (parse-inline-formatting content)) nested-list]
                                      [(create-paragraph (parse-inline-formatting content))])))))

                    ;; Item at deeper level - shouldn't happen in well-formed input
                    (> level current-level)
                    (recur remaining result)

                    ;; Item at shallower level - return what we have
                    (< level current-level)
                    result)))))]

    (when (seq lines)
      (let [;; Parse each line into items with indentation levels and preserve original
            items (map (fn [line]
                         {:level (get-indent-level line)
                          :content (parse-item-content line)
                          :original line
                          :line-type (get-line-type line)})
                       lines)
            ;; Build structure starting at level 0
            nested-structure (build-nested-structure items 0)]

        (if (= list-type :bullet-list)
          (create-bullet-list nested-structure)
          (create-ordered-list nested-structure))))))

(defn parse-block-elements
  "Parse block-level markdown elements (headers, lists, paragraphs, code blocks)"
  [text]
  (if (str/blank? text)
    []
    (let [lines (str/split-lines text)
          ;; Group consecutive list items and code blocks
          grouped-lines (reduce (fn [acc line]
                                  (let [last-group (last acc)
                                        in-code-block? (and last-group
                                                            (= (:type last-group) :code-block)
                                                            (not (:closed? last-group)))]
                                    (cond
                                      ;; Check for code block end while in code block
                                      (and in-code-block? (str/starts-with? line "```"))
                                      (update acc (dec (count acc)) assoc :closed? true)

                                      ;; Continue collecting code block lines
                                      in-code-block?
                                      (update acc (dec (count acc))
                                              #(update % :lines conj line))

                                      ;; Code block start
                                      (str/starts-with? line "```")
                                      (let [language (str/trim (subs line 3))]
                                        (conj acc {:type :code-block
                                                   :language (when-not (str/blank? language) language)
                                                   :lines []
                                                   :closed? false}))

                                      ;; Header
                                      (str/starts-with? line "#")
                                      (conj acc {:type :header :line line})

                                      ;; Bullet list item
                                      ;; Bullet list item (including nested with indentation)
                                      ;; Bullet list item (including nested with indentation)
                                      ;; List items (bullet or ordered, including nested with indentation)
                                      (or (re-matches #"^\s*- .*" line)
                                          (re-matches #"^\s*\d+\.\s.*" line))
                                      (let [is-bullet (re-matches #"^\s*- .*" line)
                                            current-type (if is-bullet :bullet-list :ordered-list)
                                            indent (count (take-while #(= % \space) line))]
                                        (if (and last-group
                                                 (or (= (:type last-group) :bullet-list)
                                                     (= (:type last-group) :ordered-list))
                                                 (or (> indent 0) ; nested item
                                                     (= (:type last-group) current-type))) ; same type
                                          (update acc (dec (count acc))
                                                  #(update % :lines conj line))
                                          (conj acc {:type current-type :lines [line]})))

                                      ;; Blockquote
                                      (str/starts-with? line "> ")
                                      (if (and last-group (= (:type last-group) :blockquote))
                                        (update acc (dec (count acc))
                                                #(update % :lines conj line))
                                        (conj acc {:type :blockquote :lines [line]}))

                                      ;; Horizontal rule
                                      ;; Horizontal rule
                                      ;; Horizontal rule
                                      (re-matches #"^-{3,}$" line)
                                      (conj acc {:type :rule})

                                      ;; Table row (contains |)
                                      (and (str/includes? line "|") (not (str/starts-with? line ">")))
                                      (if (and last-group (= (:type last-group) :table))
                                        (update acc (dec (count acc))
                                                #(update % :lines conj line))
                                        (conj acc {:type :table :lines [line]}))

                                      ;; Regular paragraph
                                      (not (str/blank? line))
                                      (conj acc {:type :paragraph :line line})

                                      ;; Empty line - ignore for grouping
                                      :else acc)))
                                [] lines)]

      ;; Convert groups to ADF nodes
      (keep (fn [group]
              (case (:type group)
                :header (let [level (count (take-while #(= % \#) (:line group)))
                              heading-text (str/trim (subs (:line group) level))]
                          (when (and (<= 1 level 6) (not (str/blank? heading-text)))
                            (create-heading level (parse-inline-formatting heading-text))))

                :bullet-list (parse-nested-list (:lines group) :bullet-list)

                :ordered-list (parse-nested-list (:lines group) :ordered-list)

                :blockquote (let [quote-lines (map #(str/trim (subs % 2)) (:lines group))
                                  quote-text (str/join "\n" quote-lines)]
                              (create-blockquote [(create-paragraph (parse-inline-formatting quote-text))]))

                :code-block (when (:closed? group)
                              (create-code-block (str/join "\n" (:lines group)) (:language group)))

                :rule (create-rule)

                :table (parse-table-lines (:lines group))

                :paragraph (create-paragraph (parse-inline-formatting (:line group)))))
            grouped-lines))))

;; =============================================================================
;; Core API - Enhanced Version of text->adf
;; =============================================================================

(defn markdown->adf
  "Convert markdown text to Atlassian Document Format (ADF).
   
   This is an enhanced version that will support comprehensive markdown syntax
   including tables, images, code blocks, and complex nesting.
   
   Currently supports basic functionality with plans for full marklassian parity."
  [text]
  (if (or (nil? text) (str/blank? text))
    (create-document [])
    ;; For now, use simplified implementation
    ;; TODO: Replace with comprehensive parser
    (try
      (let [paragraphs (str/split text #"\n\s*\n")
            content (mapcat #(parse-block-elements %) paragraphs)
            final-content (if (empty? content)
                            [(create-paragraph [(create-text-node text)])]
                            content)]
        (create-document final-content))
      (catch Exception e
        ;; Fallback to simple paragraph on any parsing error
        (create-document [(create-paragraph [(create-text-node text)])])))))

;; =============================================================================
;; Parsing Functions (Temporary - will be enhanced)
;; =============================================================================

;; =============================================================================
;; Public API Aliases
;; =============================================================================

;; Maintain backward compatibility
(def text->adf markdown->adf)