(ns lib.adf.nodes
  "ADF node constructors and document structure"
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