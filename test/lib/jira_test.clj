(ns lib.jira-test
  (:require [clojure.test :refer [deftest is testing]]
            [lib.jira :as jira]))

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

(deftest text->adf-test
  (testing "text->adf with nil input"
    (let [result (jira/text->adf nil)]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (= (:content result) []))))

  (testing "text->adf with empty string"
    (let [result (jira/text->adf "")]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (= (:content result) []))))

  (testing "text->adf with simple text"
    (let [result (jira/text->adf "Hello world")]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (= (count (:content result)) 1))
      (let [paragraph (first (:content result))]
        (is (= (:type paragraph) "paragraph"))
        (is (= (count (:content paragraph)) 1))
        (let [text-node (first (:content paragraph))]
          (is (= (:type text-node) "text"))
          (is (= (:text text-node) "Hello world"))))))

  (testing "text->adf with bold formatting"
    (let [result (jira/text->adf "This is **bold** text")]
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (:type paragraph) "paragraph"))
        (is (= (count content) 3))
        ;; Before bold
        (is (= (:text (nth content 0)) "This is "))
        ;; Bold text
        (is (= (:text (nth content 1)) "bold"))
        (is (= (:marks (nth content 1)) [{:type "strong"}]))
        ;; After bold
        (is (= (:text (nth content 2)) " text")))))

  (testing "text->adf with italic formatting"
    (let [result (jira/text->adf "This is *italic* text")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "italic"))
        (is (= (:marks (nth content 1)) [{:type "em"}])))))

  (testing "text->adf with code formatting"
    (let [result (jira/text->adf "Run `kubectl get pods` command")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "kubectl get pods"))
        (is (= (:marks (nth content 1)) [{:type "code"}])))))

  (testing "text->adf with custom link"
    (let [result (jira/text->adf "Visit [GitHub](https://github.com) for code")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "GitHub"))
        (is (= (:marks (nth content 1)) [{:type "link" :attrs {:href "https://github.com"}}])))))

  (testing "text->adf with plain URL"
    (let [result (jira/text->adf "Visit https://github.com for code")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 3))
        (is (= (:text (nth content 1)) "https://github.com"))
        (is (= (:marks (nth content 1)) [{:type "link" :attrs {:href "https://github.com"}}])))))

  (testing "text->adf with ticket ID (no auto-linking needed)"
    (let [result (jira/text->adf "See ticket PROJ-123 for details")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 1))
        (is (= (:text (first content)) "See ticket PROJ-123 for details"))
        (is (nil? (:marks (first content)))))))

  (testing "text->adf with headers"
    (let [result (jira/text->adf "# Main Title\n## Subtitle\n### Section")]
      (let [content (:content result)]
        (is (= (count content) 3))
        ;; Level 1 header
        (is (= (:type (nth content 0)) "heading"))
        (is (= (get-in (nth content 0) [:attrs :level]) 1))
        (is (= (:text (first (:content (nth content 0)))) "Main Title"))
        ;; Level 2 header
        (is (= (:type (nth content 1)) "heading"))
        (is (= (get-in (nth content 1) [:attrs :level]) 2))
        (is (= (:text (first (:content (nth content 1)))) "Subtitle"))
        ;; Level 3 header
        (is (= (:type (nth content 2)) "heading"))
        (is (= (get-in (nth content 2) [:attrs :level]) 3))
        (is (= (:text (first (:content (nth content 2)))) "Section")))))

  (testing "text->adf with bullet list"
    (let [result (jira/text->adf "- First item\n- Second item\n- Third item")]
      (let [bullet-list (first (:content result))]
        (is (= (:type bullet-list) "bulletList"))
        (is (= (count (:content bullet-list)) 3))
        (let [first-item (first (:content bullet-list))]
          (is (= (:type first-item) "listItem"))
          (is (= (:text (first (:content (first (:content first-item))))) "First item"))))))

  (testing "text->adf with multi-paragraph text"
    (let [result (jira/text->adf "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.")]
      (let [content (:content result)]
        (is (= (count content) 3))
        (is (every? #(= (:type %) "paragraph") content))
        (is (= (:text (first (:content (nth content 0)))) "First paragraph."))
        (is (= (:text (first (:content (nth content 1)))) "Second paragraph."))
        (is (= (:text (first (:content (nth content 2)))) "Third paragraph.")))))

  (testing "text->adf with mixed formatting"
    (let [result (jira/text->adf "**Bold** and *italic* and `code` in one line")]
      (let [paragraph (first (:content result))
            content (:content paragraph)]
        (is (= (count content) 6)) ;; bold, text, italic, text, code, text
        (is (= (:marks (nth content 0)) [{:type "strong"}])) ;; Bold is first
        (is (= (:marks (nth content 2)) [{:type "em"}])) ;; italic is third
        (is (= (:marks (nth content 4)) [{:type "code"}]))))) ;; code is fifth

  (testing "text->adf with complex mixed content"
    (let [result (jira/text->adf "# Bug Report\n\nThe **login** function fails when:\n\n- User enters *special* characters\n- Password contains `$` symbols\n\nSee [documentation](https://example.com) or JIRA-123.")]
      (let [content (:content result)]
        (is (= (count content) 4)) ;; header, paragraph, list, paragraph
        ;; Header
        (is (= (:type (nth content 0)) "heading"))
        (is (= (:text (first (:content (nth content 0)))) "Bug Report"))
        ;; Paragraph with bold
        (is (= (:type (nth content 1)) "paragraph"))
        ;; Bullet list with italic
        (is (= (:type (nth content 2)) "bulletList"))
        ;; Paragraph with link
        (is (= (:type (nth content 3)) "paragraph")))))

  (testing "text->adf with invalid header (7 levels)"
    (let [result (jira/text->adf "####### Invalid header")]
      (let [paragraph (first (:content result))]
        ;; Should be treated as regular text, not header
        (is (= (:type paragraph) "paragraph"))
        (is (= (:text (first (:content paragraph))) "####### Invalid header")))))

  (testing "text->adf error handling - malformed input"
    (let [result (jira/text->adf "**unclosed bold")]
      ;; Should still create a valid ADF document
      (is (= (:version result) 1))
      (is (= (:type result) "doc"))
      (is (seq (:content result)))))

  (testing "text->adf preserves whitespace in code"
    (let [result (jira/text->adf "Command: `ls -la /home/user`")]
      (let [content (:content (first (:content result)))]
        (is (= (:text (nth content 1)) "ls -la /home/user"))
        (is (= (:marks (nth content 1)) [{:type "code"}]))))))