(ns lib.ai-test
  "Tests for lib.ai response parsing with real provider data"
  (:require [clojure.test :refer [deftest is testing]]
            [lib.ai :as ai]
            [cheshire.core :as json]))

;; ============================================================================
;; FIXTURE DATA - JSON responses loaded from external files (no escaping!)
;; ============================================================================

;; Load JSON responses from fixture files - much cleaner than inline strings!
(def smg-provider-response-example 
  (slurp "test/fixtures/smg-provider-response-example.json"))

(def openai-response-example 
  (slurp "test/fixtures/openai-response-example.json"))

(def openrouter-linux-actual-response 
  (slurp "test/fixtures/openrouter-linux-response.json"))

;; ADD YOUR SMG PROVIDER RESPONSE HERE:
;; Create test/fixtures/smg-provider-actual-response.json and paste your response
(def smg-provider-actual-response
  (try 
    (slurp "test/fixtures/smg-provider-actual-response.json")
    (catch Exception _
      "PASTE_YOUR_SMG_PROVIDER_RESPONSE_IN_JSON_FILE")))

;; ============================================================================
;; UNIT TESTS  
;; ============================================================================

(deftest test-unwrap-json-code-block
  (testing "Extracts JSON from markdown code blocks"
    (let [code-block "```json\n{\n  \"title\": \"Test Title\",\n  \"description\": \"Test Desc\"\n}\n```"
          result (ai/unwrap-json-code-block code-block)]
      (is (map? result))
      (is (= (:title result) "Test Title"))
      (is (= (:description result) "Test Desc"))))
  
  (testing "Returns original text when no code block found"
    (let [plain-text "Just some plain text"
          result (ai/unwrap-json-code-block plain-text)]
      (is (= result plain-text))))
  
  (testing "Handles nil input"
    (let [result (ai/unwrap-json-code-block nil)]
      (is (nil? result)))))

(deftest test-extract-content
  (testing "Extracts content using SMG provider format and unwraps JSON"
    (let [config {}
          result (ai/extract-content smg-provider-response-example config)]
      (is (map? result))
      (is (= (:title result) "Enhanced Title Here"))
      (is (= (:description result) "Enhanced description here"))))
  
  (testing "Extracts content using OpenAI format"
    (let [config {}
          result (ai/extract-content openai-response-example config)]
      (is (string? result))
      (is (.contains result "Enhanced Title"))))
  
  (testing "Extracts content using actual OpenRouter Linux response"
    (let [config {}
          result (ai/extract-content openrouter-linux-actual-response config)]
      (is (string? result))
      (is (.contains result "webServer minimum replica count"))
      (is (.contains result "traffic resilience")))))

(deftest test-enhance-content-integration
  (testing "End-to-end parsing with SMG provider JSON code block response"
    (with-redefs [ai/call-ai-model (fn [_prompt _ai-config]
                                     {:success true
                                      :content (ai/extract-content smg-provider-response-example {})})]
      (let [result (ai/enhance-content {:title "Original Title" 
                                        :description "Original Desc"} 
                                       {})]
        (is (map? result))
        (is (contains? result :title))
        (is (contains? result :description))
        ;; Should get enhanced values from the JSON
        (is (= (:title result) "Enhanced Title Here"))
        (is (= (:description result) "Enhanced description here")))))
  
  (testing "End-to-end parsing with actual OpenRouter Linux response"
    (with-redefs [ai/call-ai-model (fn [_prompt _ai-config]
                                     {:success true
                                      :content (ai/extract-content openrouter-linux-actual-response {})})]
      (let [result (ai/enhance-content {:title "Update minReplicas of webServer to 6" 
                                        :description "we can't scale down far anymore"} 
                                       {})]
        (is (map? result))
        (is (contains? result :title))
        (is (contains? result :description))
        ;; Should get enhanced values from the parsed JSON
        (is (.contains (:title result) "webServer minimum replica count"))
        (is (.contains (:description result) "traffic resilience"))))))

;; Helper function for interactive testing
(defn test-response-parsing 
  "Helper function to test parsing of a raw response string"
  [response-string]
  (let [config {:debug true}]
    (println "\n=== Testing Response Parsing ===")
    (println "Input:" response-string)
    (println "\nExtracted content:")
    (let [result (ai/extract-content response-string config)]
      (println (pr-str result))
      (println "\nType:" (type result))
      result)))