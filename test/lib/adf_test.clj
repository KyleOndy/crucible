(ns lib.adf-test
  (:require [clojure.test :refer [deftest is testing]]
            [lib.adf :as adf]))


(deftest create-document-test
  (testing "create-document with empty content"
    (let [result (adf/create-document [])]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (= (:content result) []))))
  (testing "create-document with content"
    (let [paragraph (adf/create-paragraph [(adf/create-text-node "test")])
          result (adf/create-document [paragraph])]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (= (count (:content result)) 1)))))


(deftest text-node-test
  (testing "create-text-node without marks"
    (let [result (adf/create-text-node "hello")]
      (is (= (:type result) "text"))
      (is (= (:text result) "hello"))
      (is (nil? (:marks result)))))
  (testing "create-text-node with marks"
    (let [marks [(adf/create-strong-mark)]
          result (adf/create-text-node "bold" marks)]
      (is (= (:type result) "text"))
      (is (= (:text result) "bold"))
      (is (= (:marks result) marks)))))


(deftest formatting-marks-test
  (testing "strong mark" (is (= (adf/create-strong-mark) {:type "strong"})))
  (testing "emphasis mark" (is (= (adf/create-em-mark) {:type "em"})))
  (testing "code mark" (is (= (adf/create-code-mark) {:type "code"})))
  (testing "strike mark" (is (= (adf/create-strike-mark) {:type "strike"})))
  (testing "link mark"
    (is (= (adf/create-link-mark "https://example.com")
           {:type "link", :attrs {:href "https://example.com"}}))))


(deftest node-constructors-test
  (testing "create-paragraph"
    (let [content [(adf/create-text-node "test")]
          result (adf/create-paragraph content)]
      (is (= (:type result) "paragraph"))
      (is (= (:content result) content))))
  (testing "create-heading"
    (let [content [(adf/create-text-node "Title")]
          result (adf/create-heading 2 content)]
      (is (= (:type result) "heading"))
      (is (= (:attrs result) {:level 2}))
      (is (= (:content result) content))))
  (testing "create-bullet-list"
    (let [items [(adf/create-list-item "item1")]
          result (adf/create-bullet-list items)]
      (is (= (:type result) "bulletList"))
      (is (= (:content result) items))))
  (testing "create-ordered-list"
    (let [items [(adf/create-list-item "item1")]
          result (adf/create-ordered-list items)]
      (is (= (:type result) "orderedList"))
      (is (= (:content result) items))))
  (testing "create-code-block without language"
    (let [result (adf/create-code-block "code content")]
      (is (= (:type result) "codeBlock"))
      (is (= (:attrs result) {:language "text"}))
      (is (= (:content result) [{:type "text", :text "code content"}]))))
  (testing "create-code-block with language"
    (let [result (adf/create-code-block "code content" "clojure")]
      (is (= (:type result) "codeBlock"))
      (is (= (:attrs result) {:language "clojure"}))
      (is (= (:content result) [{:type "text", :text "code content"}]))))
  (testing "create-blockquote"
    (let [content [(adf/create-paragraph [(adf/create-text-node "quote")])]
          result (adf/create-blockquote content)]
      (is (= (:type result) "blockquote"))
      (is (= (:content result) content))))
  (testing "create-rule"
    (let [result (adf/create-rule)] (is (= (:type result) "rule"))))
  (testing "create-media-single"
    (let [result (adf/create-media-single "https://example.com/image.png"
                                          "alt text")]
      (is (= (:type result) "mediaSingle"))
      (is (= (:attrs result) {:layout "center"}))
      (is (= (get-in result [:content 0 :type]) "media"))
      (is (= (get-in result [:content 0 :attrs :url])
             "https://example.com/image.png"))
      (is (= (get-in result [:content 0 :attrs :alt]) "alt text")))))


(deftest markdown->adf-test
  (testing "nil input"
    (let [result (adf/markdown->adf nil)]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (= (:content result) []))))
  (testing "empty string"
    (let [result (adf/markdown->adf "")]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (= (:content result) []))))
  (testing "simple text"
    (let [result (adf/markdown->adf "Hello world")]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (= (count (:content result)) 1))
      (let [paragraph (first (:content result))]
        (is (= (:type paragraph) "paragraph"))
        (is (= (count (:content paragraph)) 1))
        (let [text-node (first (:content paragraph))]
          (is (= (:type text-node) "text"))
          (is (= (:text text-node) "Hello world"))))))
  (testing "bold formatting"
    (let [result (adf/markdown->adf "This is **bold** text")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 0)) "This is "))
        (is (= (:text (nth content 1)) "bold"))
        (is (= (:marks (nth content 1)) [{:type "strong"}]))
        (is (= (:text (nth content 2)) " text")))))
  (testing "italic formatting"
    (let [result (adf/markdown->adf "This is *italic* text")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "italic"))
        (is (= (:marks (nth content 1)) [{:type "em"}])))))
  (testing "strikethrough formatting"
    (let [result (adf/markdown->adf "This is ~~strikethrough~~ text")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "strikethrough"))
        (is (= (:marks (nth content 1)) [{:type "strike"}])))))
  (testing "code formatting"
    (let [result (adf/markdown->adf "Run `kubectl get pods` command")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "kubectl get pods"))
        (is (= (:marks (nth content 1)) [{:type "code"}])))))
  (testing "custom link"
    (let [result (adf/markdown->adf
                   "Visit [GitHub](https://github.com) for code")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "GitHub"))
        (is (= (:marks (nth content 1))
               [{:type "link", :attrs {:href "https://github.com"}}])))))
  (testing "plain URL"
    (let [result (adf/markdown->adf "Visit https://github.com for code")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "https://github.com"))
        (is (= (:marks (nth content 1))
               [{:type "link", :attrs {:href "https://github.com"}}])))))
  (testing "headers"
    (let [result (adf/markdown->adf "# Main Title\n## Subtitle")]
      (let [content (:content result)]
        (is (= (count content) 2))
        ;; Level 1 header
        (is (= (:type (nth content 0)) "heading"))
        (is (= (get-in (nth content 0) [:attrs :level]) 1))
        (is (= (:text (first (:content (nth content 0)))) "Main Title"))
        ;; Level 2 header
        (is (= (:type (nth content 1)) "heading"))
        (is (= (get-in (nth content 1) [:attrs :level]) 2))
        (is (= (:text (first (:content (nth content 1)))) "Subtitle")))))
  (testing "bullet lists"
    (let [result (adf/markdown->adf "- First item\n- Second item")]
      (let [bullet-list (first (:content result))]
        (is (= (:type bullet-list) "bulletList"))
        (is (= (count (:content bullet-list)) 2))
        (let [first-item (first (:content bullet-list))]
          (is (= (:type first-item) "listItem"))))))
  (testing "ordered lists"
    (let [result (adf/markdown->adf "1. First item\n2. Second item")]
      (let [ordered-list (first (:content result))]
        (is (= (:type ordered-list) "orderedList"))
        (is (= (count (:content ordered-list)) 2))
        (let [first-item (first (:content ordered-list))]
          (is (= (:type first-item) "listItem"))))))
  (testing "blockquotes"
    (let [result (adf/markdown->adf "> This is a quote")]
      (let [blockquote (first (:content result))]
        (is (= (:type blockquote) "blockquote"))
        (is (= (count (:content blockquote)) 1)))))
  (testing "horizontal rules"
    (let [result (adf/markdown->adf "---")]
      (let [rule (first (:content result))] (is (= (:type rule) "rule")))))
  (testing "code blocks"
    (let [result (adf/markdown->adf "```clojure\n(+ 1 2)\n```")]
      (let [code-block (first (:content result))]
        (is (= (:type code-block) "codeBlock"))
        (is (= (get-in code-block [:attrs :language]) "clojure"))
        (is (= (:text (first (:content code-block))) "(+ 1 2)")))))
  (testing "multi-paragraph text"
    (let [result (adf/markdown->adf "First paragraph.\n\nSecond paragraph.")]
      (let [content (:content result)]
        (is (= (count content) 2))
        (is (every? #(= (:type %) "paragraph") content))
        (is (= (:text (first (:content (nth content 0)))) "First paragraph."))
        (is (= (:text (first (:content (nth content 1))))
               "Second paragraph.")))))
  (testing "mixed formatting"
    (let [result (adf/markdown->adf
                   "**Bold** and *italic* and ~~strike~~ and `code`")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 7)) ; bold, text, italic, text, strike,
                                   ; text, code
        (is (= (:marks (nth content 0)) [{:type "strong"}]))
        (is (= (:marks (nth content 2)) [{:type "em"}]))
        (is (= (:marks (nth content 4)) [{:type "strike"}]))
        (is (= (:marks (nth content 6)) [{:type "code"}])))))
  (testing "error handling - malformed input"
    (let [result (adf/markdown->adf "**unclosed bold")]
      ;; Should still create a valid ADF document
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (seq (:content result))))))


(deftest parse-inline-formatting-test
  (testing "empty text" (is (= (adf/parse-inline-formatting "") [])))
  (testing "plain text"
    (let [result (adf/parse-inline-formatting "hello world")]
      (is (= (count result) 1))
      (is (= (:text (first result)) "hello world"))))
  (testing "mixed formatting"
    (let [result (adf/parse-inline-formatting "**bold** and *italic*")]
      (is (= (count result) 3)) ; bold, " and ", italic
      (is (= (:marks (nth result 0)) [{:type "strong"}]))
      (is (= (:marks (nth result 2)) [{:type "em"}])))))


(deftest find-pattern-matches-test
  (testing "find bold patterns"
    (let [result (adf/find-pattern-matches "**bold** text **more**" :bold)]
      (is (= (count result) 2))
      (is (= (:text (first result)) "bold"))
      (is (= (:start (first result)) 0))
      (is (= (:end (first result)) 8))
      (is (= (:text (second result)) "more"))
      (is (= (:start (second result)) 14))
      (is (= (:end (second result)) 22))))
  (testing "find italic patterns"
    (let [result (adf/find-pattern-matches "*italic* text" :italic)]
      (is (= (count result) 1))
      (is (= (:text (first result)) "italic"))
      (is (= (:start (first result)) 0))
      (is (= (:end (first result)) 8))))
  (testing "find code patterns"
    (let [result (adf/find-pattern-matches "`code` text" :code)]
      (is (= (count result) 1))
      (is (= (:text (first result)) "code"))
      (is (= (:start (first result)) 0))
      (is (= (:end (first result)) 6))))
  (testing "find custom link patterns"
    (let [result (adf/find-pattern-matches "[text](url) more" :custom-link)]
      (is (= (count result) 1))
      (is (= (:text (first result)) "text"))
      (is (= (:href (first result)) "url"))
      (is (= (:start (first result)) 0))
      (is (= (:end (first result)) 11))))
  (testing "find URL patterns"
    (let [result (adf/find-pattern-matches "Visit https://example.com today"
                                           :url)]
      (is (= (count result) 1))
      (is (= (:text (first result)) "https://example.com"))
      (is (= (:href (first result)) "https://example.com"))
      (is (= (:start (first result)) 6))
      (is (= (:end (first result)) 25))))
  (testing "find strike patterns"
    (let [result (adf/find-pattern-matches "~~strike~~ text" :strike)]
      (is (= (count result) 1))
      (is (= (:text (first result)) "strike"))
      (is (= (:start (first result)) 0))
      (is (= (:end (first result)) 10))))
  (testing "no matches found"
    (let [result (adf/find-pattern-matches "plain text" :bold)]
      (is (= (count result) 0))))
  (testing "empty text"
    (let [result (adf/find-pattern-matches "" :bold)]
      (is (= (count result) 0)))))


(deftest remove-overlapping-matches-test
  (testing "no overlaps"
    (let [matches [{:start 0, :end 5, :type :bold}
                   {:start 10, :end 15, :type :italic}]
          result (adf/remove-overlapping-matches matches)]
      (is (= (count result) 2))
      (is (= result matches))))
  (testing "overlapping matches - keep earlier"
    (let [matches [{:start 0, :end 10, :type :bold}
                   {:start 5, :end 15, :type :italic}]
          result (adf/remove-overlapping-matches matches)]
      (is (= (count result) 1))
      (is (= (:type (first result)) :bold))))
  (testing "adjacent matches - no overlap"
    (let [matches [{:start 0, :end 5, :type :bold}
                   {:start 5, :end 10, :type :italic}]
          result (adf/remove-overlapping-matches matches)]
      (is (= (count result) 2))))
  (testing "multiple overlaps"
    (let [matches [{:start 0, :end 10, :type :bold}
                   {:start 5, :end 15, :type :italic}
                   {:start 8, :end 12, :type :code}
                   {:start 20, :end 25, :type :strike}]
          result (adf/remove-overlapping-matches matches)]
      (is (= (count result) 2))
      (is (= (:type (first result)) :bold))
      (is (= (:type (second result)) :strike))))
  (testing "empty matches"
    (let [result (adf/remove-overlapping-matches [])]
      (is (= (count result) 0)))))


(deftest build-content-nodes-test
  (testing "no matches - plain text"
    (let [text "plain text"
          matches []
          result (adf/build-content-nodes text matches)]
      (is (= (count result) 1))
      (is (= (:text (first result)) "plain text"))
      (is (nil? (:marks (first result))))))
  (testing "single match at start"
    (let [text "**bold** text"
          matches [{:start 0, :end 8, :type :bold, :text "bold"}]
          result (adf/build-content-nodes text matches)]
      (is (= (count result) 2))
      (is (= (:text (first result)) "bold"))
      (is (= (:marks (first result)) [{:type "strong"}]))
      (is (= (:text (second result)) " text"))))
  (testing "single match in middle"
    (let [text "text **bold** more"
          matches [{:start 5, :end 13, :type :bold, :text "bold"}]
          result (adf/build-content-nodes text matches)]
      (is (= (count result) 3))
      (is (= (:text (first result)) "text "))
      (is (= (:text (second result)) "bold"))
      (is (= (:marks (second result)) [{:type "strong"}]))
      (is (= (:text (nth result 2)) " more"))))
  (testing "multiple matches"
    (let [text "**bold** and *italic*"
          matches [{:start 0, :end 8, :type :bold, :text "bold"}
                   {:start 13, :end 21, :type :italic, :text "italic"}]
          result (adf/build-content-nodes text matches)]
      (is (= (count result) 3))
      (is (= (:text (first result)) "bold"))
      (is (= (:marks (first result)) [{:type "strong"}]))
      (is (= (:text (second result)) " and "))
      (is (= (:text (nth result 2)) "italic"))
      (is (= (:marks (nth result 2)) [{:type "em"}]))))
  (testing "match at end"
    (let [text "text **bold**"
          matches [{:start 5, :end 13, :type :bold, :text "bold"}]
          result (adf/build-content-nodes text matches)]
      (is (= (count result) 2))
      (is (= (:text (first result)) "text "))
      (is (= (:text (second result)) "bold"))
      (is (= (:marks (second result)) [{:type "strong"}]))))
  (testing "custom link formatting"
    (let [text "[GitHub](https://github.com)"
          matches [{:start 0,
                    :end 28,
                    :type :custom-link,
                    :text "GitHub",
                    :href "https://github.com"}]
          result (adf/build-content-nodes text matches)]
      (is (= (count result) 1))
      (is (= (:text (first result)) "GitHub"))
      (is (= (:marks (first result))
             [{:type "link", :attrs {:href "https://github.com"}}]))))
  (testing "URL formatting"
    (let [text "https://example.com"
          matches [{:start 0,
                    :end 19,
                    :type :url,
                    :text "https://example.com",
                    :href "https://example.com"}]
          result (adf/build-content-nodes text matches)]
      (is (= (count result) 1))
      (is (= (:text (first result)) "https://example.com"))
      (is (= (:marks (first result))
             [{:type "link", :attrs {:href "https://example.com"}}]))))
  (testing "empty text"
    (let [result (adf/build-content-nodes "" [])] (is (= (count result) 0)))))


(deftest extract-formatting-patterns-test
  (testing "extracts all pattern types"
    (let
      [text
         "**bold** *italic* `code` ~~strike~~ [link](url) https://example.com"
       result (adf/extract-formatting-patterns text)]
      (is (>= (count result) 6)) ; Should find all 6 patterns
      (is (some #(= (:type %) :bold) result))
      (is (some #(= (:type %) :italic) result))
      (is (some #(= (:type %) :code) result))
      (is (some #(= (:type %) :strike) result))
      (is (some #(= (:type %) :custom-link) result))
      (is (some #(= (:type %) :url) result))))
  (testing "handles overlapping patterns correctly"
    (let [text "**bold *italic*** text"
          result (adf/extract-formatting-patterns text)]
      ;; Should prefer the earlier bold pattern over the overlapping italic
      (is (some #(= (:type %) :bold) result))
      ;; The italic should be filtered out due to overlap
      (is (not (some #(= (:type %) :italic) result)))))
  (testing "empty text"
    (let [result (adf/extract-formatting-patterns "")]
      (is (= (count result) 0))))
  (testing "no patterns"
    (let [result (adf/extract-formatting-patterns "plain text")]
      (is (= (count result) 0)))))


(deftest advanced-features-test
  (testing "table support"
    ;; Simple table
    (let [result (adf/markdown->adf
                   "| Name | Age |\n|------|-----|\n| John | 30  |")]
      (let [table (first (:content result))]
        (is (= (:type table) "table"))
        (is (= (count (:content table)) 2)) ; header + 1 row
        ;; Check header
        (let [header-row (first (:content table))
              first-header (first (:content header-row))]
          (is (= (:type header-row) "tableRow"))
          (is (= (:type first-header) "tableHeader")))))
    ;; Table with formatting
    (let [result
            (adf/markdown->adf
              "| **Name** | *Role* |\n|----------|--------|\n| John | `dev` |")]
      (let [table (first (:content result))
            header-row (first (:content table))
            first-header (first (:content header-row))
            header-paragraph (first (:content first-header))
            header-text (first (:content header-paragraph))]
        (is (= (:text header-text) "Name"))
        (is (= (:marks header-text) [{:type "strong"}])))))
  (testing "basic lists work"
    ;; Simple bullet list
    (let [result (adf/markdown->adf "- Item 1\n- Item 2")]
      (let [list (first (:content result))]
        (is (= (:type list) "bulletList"))
        (is (= (count (:content list)) 2))))
    ;; Simple ordered list
    (let [result (adf/markdown->adf "1. Item 1\n2. Item 2")]
      (let [list (first (:content result))]
        (is (= (:type list) "orderedList"))
        (is (= (count (:content list)) 2)))))
  (testing "complex mixed content"
    ;; Everything together
    (let
      [result
         (adf/markdown->adf
           "# Title\n\n**Bold** and *italic* text.\n\n> This is a quote\n\n```clojure\n(+ 1 2)\n```\n\n- List item\n\n| Col1 | Col2 |\n|------|------|\n| A    | B    |\n\n---")]
      (let [content (:content result)]
        (is (>= (count content) 6)) ; At least: heading, paragraph,
                                    ; blockquote, code, list, table, rule
        (is (= (:type (nth content 0)) "heading"))
        (is (= (:type (nth content 1)) "paragraph"))
        (is (= (:type (nth content 2)) "blockquote"))
        (is (= (:type (nth content 3)) "codeBlock"))
        (is (= (:type (nth content 4)) "bulletList"))))))


(deftest edge-cases-test
  (testing "empty table cells"
    (let [result (adf/markdown->adf
                   "| A |   | C |\n|---|---|---|\n|   | B |   |")]
      (let [table (first (:content result))
            data-row (second (:content table))
            empty-cell (first (:content data-row))
            cell-paragraph (first (:content empty-cell))
            cell-text (first (:content cell-paragraph))]
        (is (= (:text cell-text) "")))))
  (testing "malformed markdown graceful handling"
    ;; Unclosed table
    (let [result (adf/markdown->adf "| Name | Age\n| John | 30")]
      (is (= (:version result) 1))
      (is (seq (:content result))))
    ;; Basic list parsing works
    (let [result (adf/markdown->adf "- Item 1\n- Item 2")]
      (let [list (first (:content result))]
        (is (= (:type list) "bulletList"))))))
