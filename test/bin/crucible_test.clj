(ns bin.crucible-test
  (:require [clojure.test :refer [deftest is testing]]
            [crucible :as crucible]))

(deftest process-template-test
  (testing "process-template replaces all template variables"
    (let [template "Today is {{DATE}} ({{DAY_NAME}}) - {{FULL_DATE}}"
          date-info {:date "2024-01-15"
                     :day-name "Monday"
                     :full-date "January 15, 2024"}
          result (crucible/process-template template date-info)]
      (is (= result "Today is 2024-01-15 (Monday) - January 15, 2024"))))

  (testing "process-template with only DATE variable"
    (let [template "Date: {{DATE}}"
          date-info {:date "2024-12-25"
                     :day-name "Wednesday"
                     :full-date "December 25, 2024"}
          result (crucible/process-template template date-info)]
      (is (= result "Date: 2024-12-25"))))

  (testing "process-template with only DAY_NAME variable"
    (let [template "It's {{DAY_NAME}}!"
          date-info {:date "2024-07-04"
                     :day-name "Thursday"
                     :full-date "July 4, 2024"}
          result (crucible/process-template template date-info)]
      (is (= result "It's Thursday!"))))

  (testing "process-template with only FULL_DATE variable"
    (let [template "Welcome to {{FULL_DATE}}"
          date-info {:date "2024-02-29"
                     :day-name "Thursday"
                     :full-date "February 29, 2024"}
          result (crucible/process-template template date-info)]
      (is (= result "Welcome to February 29, 2024"))))

  (testing "process-template with no template variables"
    (let [template "Static content with no variables"
          date-info {:date "2024-01-01"
                     :day-name "Monday"
                     :full-date "January 1, 2024"}
          result (crucible/process-template template date-info)]
      (is (= result "Static content with no variables"))))

  (testing "process-template with repeated variables"
    (let [template "{{DATE}} - {{DATE}} - {{DATE}}"
          date-info {:date "2024-03-14"
                     :day-name "Thursday"
                     :full-date "March 14, 2024"}
          result (crucible/process-template template date-info)]
      (is (= result "2024-03-14 - 2024-03-14 - 2024-03-14"))))

  (testing "process-template with empty template"
    (let [template ""
          date-info {:date "2024-01-01"
                     :day-name "Monday"
                     :full-date "January 1, 2024"}
          result (crucible/process-template template date-info)]
      (is (= result ""))))

  (testing "process-template with malformed variables"
    (let [template "{{DATE}} {{INVALID}} {{DAY_NAME}}"
          date-info {:date "2024-01-01"
                     :day-name "Monday"
                     :full-date "January 1, 2024"}
          result (crucible/process-template template date-info)]
      (is (= result "2024-01-01 {{INVALID}} Monday")))))

(deftest parse-editor-content-test
  (testing "parse-editor-content with title and description"
    (let [content "My Task Title\nThis is the description\nWith multiple lines"
          result (crucible/parse-editor-content content)]
      (is (= (:title result) "My Task Title"))
      (is (= (:description result) "This is the description\nWith multiple lines"))))

  (testing "parse-editor-content with only title"
    (let [content "Single Line Title"
          result (crucible/parse-editor-content content)]
      (is (= (:title result) "Single Line Title"))
      (is (= (:description result) ""))))

  (testing "parse-editor-content filters comments"
    (let [content "# This is a comment\nTask Title\n# Another comment\nDescription line\n# Final comment"
          result (crucible/parse-editor-content content)]
      (is (= (:title result) "Task Title"))
      (is (= (:description result) "Description line"))))

  (testing "parse-editor-content handles empty lines"
    (let [content "Title Here\n\n\nDescription after empty lines\n\n\nMore description"
          result (crucible/parse-editor-content content)]
      (is (= (:title result) "Title Here"))
      (is (= (:description result) "Description after empty lines\nMore description"))))

  (testing "parse-editor-content with only comments returns nil"
    (let [content "# Comment 1\n# Comment 2\n# Comment 3"
          result (crucible/parse-editor-content content)]
      (is (nil? result))))

  (testing "parse-editor-content with empty content returns nil"
    (let [content ""
          result (crucible/parse-editor-content content)]
      (is (nil? result))))

  (testing "parse-editor-content with only whitespace returns nil"
    (let [content "   \n\t\n  \n"
          result (crucible/parse-editor-content content)]
      (is (nil? result))))

  (testing "parse-editor-content handles whitespace-only lines"
    (let [content "  \n  Title with spaces  \n\n  Description with spaces  \n  "
          result (crucible/parse-editor-content content)]
      (is (= (:title result) "  Title with spaces  "))
      (is (= (:description result) "  Description with spaces  "))))

  (testing "parse-editor-content with mixed comments and content"
    (let [content "# Setup instructions\nProject Setup\n# Details\nInstall dependencies\nRun tests\n# End"
          result (crucible/parse-editor-content content)]
      (is (= (:title result) "Project Setup"))
      (is (= (:description result) "Install dependencies\nRun tests")))))