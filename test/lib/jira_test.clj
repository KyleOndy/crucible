(ns lib.jira-test
  (:require [clojure.test :refer [deftest is testing]]
            [lib.jira :as jira]
            [lib.adf :as adf]))

(deftest format-ticket-summary-test
  (testing "format-ticket-summary with complete ticket data"
    (let [ticket {:key "PROJ-123"
                  :fields {:summary "Test ticket summary"
                           :status {:name "In Progress"}
                           :assignee {:displayName "John Doe"}
                           :reporter {:displayName "Jane Smith"}
                           :priority {:name "High"}
                           :issuetype {:name "Story"}}}
          result (jira/format-ticket-summary ticket)]
      (is (= (:key result) "PROJ-123"))
      (is (= (:summary result) "Test ticket summary"))
      (is (= (:status result) "In Progress"))
      (is (= (:assignee result) "John Doe"))
      (is (= (:reporter result) "Jane Smith"))
      (is (= (:priority result) "High"))
      (is (= (:type result) "Story"))))

  (testing "format-ticket-summary with missing assignee"
    (let [ticket {:key "PROJ-456"
                  :fields {:summary "Unassigned ticket"
                           :status {:name "Open"}
                           :assignee nil
                           :reporter {:displayName "Jane Smith"}
                           :priority {:name "Medium"}
                           :issuetype {:name "Bug"}}}
          result (jira/format-ticket-summary ticket)]
      (is (= (:assignee result) "Unassigned"))))

  (testing "format-ticket-summary with minimal data"
    (let [ticket {:key "PROJ-789"
                  :fields {:summary "Minimal ticket"
                           :status {:name "Done"}
                           :assignee nil
                           :reporter {:displayName "Bob Johnson"}
                           :priority {:name "Low"}
                           :issuetype {:name "Task"}}}
          result (jira/format-ticket-summary ticket)]
      (is (= (:key result) "PROJ-789"))
      (is (= (:summary result) "Minimal ticket"))
      (is (= (:status result) "Done"))
      (is (= (:assignee result) "Unassigned"))
      (is (= (:reporter result) "Bob Johnson"))
      (is (= (:priority result) "Low"))
      (is (= (:type result) "Task"))))

  (testing "format-ticket-summary extracts nested field values correctly"
    (let [ticket {:key "PROJ-999"
                  :fields {:summary "Test nested extraction"
                           :status {:name "Resolved" :id "5"}
                           :assignee {:displayName "Alice Cooper" :accountId "123"}
                           :reporter {:displayName "Charlie Brown" :emailAddress "charlie@test.com"}
                           :priority {:name "Critical" :id "1"}
                           :issuetype {:name "Epic" :subtask false}}}
          result (jira/format-ticket-summary ticket)]
      (is (= (:status result) "Resolved"))
      (is (= (:assignee result) "Alice Cooper"))
      (is (= (:reporter result) "Charlie Brown"))
      (is (= (:priority result) "Critical"))
      (is (= (:type result) "Epic")))))

(deftest adf-conversion-test
  (testing "Basic ADF conversion functionality"
    ;; Test that jira/text->adf delegates properly to adf library
    (let [result (jira/text->adf "Hello world")]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (= (count (:content result)) 1))
      (let [paragraph (first (:content result))]
        (is (= (:type paragraph) "paragraph")))))

  (testing "Enhanced ADF features work via lib.adf"
    ;; Test strikethrough (new feature)
    (let [result (adf/markdown->adf "This is ~~strikethrough~~ text")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "strikethrough"))
        (is (= (:marks (nth content 1)) [{:type "strike"}]))))

    ;; Test ordered lists (new feature)  
    (let [result (adf/markdown->adf "1. First item\n2. Second item")]
      (let [ordered-list (first (:content result))]
        (is (= (:type ordered-list) "orderedList"))
        (is (= (count (:content ordered-list)) 2))))

    ;; Test blockquotes (new feature)
    (let [result (adf/markdown->adf "> This is a quote")]
      (let [blockquote (first (:content result))]
        (is (= (:type blockquote) "blockquote"))))

    ;; Test horizontal rule (new feature)  
    (let [result (adf/markdown->adf "---")]
      (let [rule (first (:content result))]
        (is (= (:type rule) "rule"))))

    ;; Test code blocks (enhanced feature)
    (let [result (adf/markdown->adf "```clojure\n(+ 1 2)\n```")]
      (let [code-block (first (:content result))]
        (is (= (:type code-block) "codeBlock"))
        (is (= (get-in code-block [:attrs :language]) "clojure"))
        (is (= (:text (first (:content code-block))) "(+ 1 2)"))))))