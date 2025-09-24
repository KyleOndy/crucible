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
  (:require [lib.adf.nodes :as nodes]
            [lib.adf.parsing :as parsing]))

;; Re-export document structure functions
(def create-document nodes/create-document)

;; Re-export ADF node constructors
(def create-text-node nodes/create-text-node)
(def create-paragraph nodes/create-paragraph)
(def create-heading nodes/create-heading)
(def create-bullet-list nodes/create-bullet-list)
(def create-ordered-list nodes/create-ordered-list)
(def create-list-item nodes/create-list-item)
(def create-code-block nodes/create-code-block)
(def create-blockquote nodes/create-blockquote)
(def create-rule nodes/create-rule)
(def create-media-single nodes/create-media-single)
(def create-table nodes/create-table)
(def create-table-row nodes/create-table-row)
(def create-table-header nodes/create-table-header)
(def create-table-cell nodes/create-table-cell)

;; Re-export inline formatting functions
(def create-strong-mark nodes/create-strong-mark)
(def create-em-mark nodes/create-em-mark)
(def create-code-mark nodes/create-code-mark)
(def create-strike-mark nodes/create-strike-mark)
(def create-link-mark nodes/create-link-mark)

;; Re-export parsing functions
(def find-pattern-matches parsing/find-pattern-matches)
(def remove-overlapping-matches parsing/remove-overlapping-matches)
(def extract-formatting-patterns parsing/extract-formatting-patterns)
(def build-content-nodes parsing/build-content-nodes)
(def parse-inline-formatting parsing/parse-inline-formatting)
(def parse-table-lines parsing/parse-table-lines)
(def parse-nested-list parsing/parse-nested-list)
(def parse-block-elements parsing/parse-block-elements)

;; Re-export main conversion functions
(def markdown->adf parsing/markdown->adf)
(def text->adf parsing/text->adf)