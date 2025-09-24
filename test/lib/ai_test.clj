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
;; Create test/fixtures/smg-provider-actual-response.json and paste your
;; response
(def smg-provider-actual-response
  (try (slurp "test/fixtures/smg-provider-actual-response.json")
       (catch Exception _ "PASTE_YOUR_SMG_PROVIDER_RESPONSE_IN_JSON_FILE")))

;; ============================================================================
;; UNIT TESTS
;; ============================================================================

(deftest test-parse-json-content
  (testing "Extracts JSON from markdown code blocks"
    (let
      [code-block
         "```json\n{\n  \"title\": \"Test Title\",\n  \"description\": \"Test Desc\"\n}\n```"
       result (ai/parse-json-content code-block)]
      (is (map? result))
      (is (= (:title result) "Test Title"))
      (is (= (:description result) "Test Desc"))))
  (testing "Parses direct JSON strings"
    (let [json-string
            "{\"title\": \"Direct Title\", \"description\": \"Direct Desc\"}"
          result (ai/parse-json-content json-string)]
      (is (map? result))
      (is (= (:title result) "Direct Title"))
      (is (= (:description result) "Direct Desc"))))
  (testing "Returns original text when no JSON found"
    (let [plain-text "Just some plain text"
          result (ai/parse-json-content plain-text)]
      (is (= result plain-text))))
  (testing "Handles malformed JSON gracefully"
    (let [bad-json "{\"title\": \"Test\", \"desc\": incomplete"
          result (ai/parse-json-content bad-json)]
      (is (= result bad-json)))) ; Should return original on parse failure
  (testing "Handles nil input"
    (let [result (ai/parse-json-content nil)] (is (nil? result)))))

(deftest test-extract-content
  (testing "Extracts and parses content from SMG provider format"
    (let [config {}
          result (ai/extract-content smg-provider-response-example config)]
      (is (map? result))
      (is (= (:title result) "Title here"))
      (is (= (:description result)
             "Description goes here\nsometime newlines."))))
  (testing "Extracts and parses content from OpenAI format"
    (let [config {}
          result (ai/extract-content openai-response-example config)]
      (is (map? result))
      (is (= (:title result) "Enhanced Title"))
      (is (= (:description result) "Enhanced description"))))
  (testing "Extracts and parses content from OpenRouter Linux response"
    (let [config {}
          result (ai/extract-content openrouter-linux-actual-response config)]
      (is (map? result))
      (is
        (=
          (:title result)
          "Increase webServer minimum replica count from current to 6 for traffic resilience"))
      (is (.contains
            (:description result)
            "Currently, the system's ability to handle traffic bursts"))
      (is (.contains (:description result) "Technical Requirements"))))
  (testing "Shows debug information when debug config enabled"
    (let [config {:debug true}]
      (with-out-str ;; Capture debug output
        (let [result (ai/extract-content openai-response-example config)]
          (is (map? result))
          (is (contains? result :title))))))
  (testing "Uses custom response paths when provided"
    (let [config {:response-paths [[:choices 0 :message :content]]}
          result (ai/extract-content openai-response-example config)]
      (is (map? result))
      (is (= (:title result) "Enhanced Title")))))

(deftest test-enhance-content-integration
  (testing "End-to-end enhancement with SMG provider response"
    (with-redefs [ai/call-ai-model (fn [_prompt _ai-config]
                                     {:success true,
                                      :content (ai/extract-content
                                                 smg-provider-response-example
                                                 {})})]
      (let [result (ai/enhance-content {:title "Original Title",
                                        :description "Original Desc"}
                                       {})]
        (is (map? result))
        (is (contains? result :title))
        (is (contains? result :description))
        ;; Should get enhanced values from the parsed SMG fixture JSON
        (is (= (:title result) "Title here"))
        (is (= (:description result)
               "Description goes here\nsometime newlines.")))))
  (testing "End-to-end enhancement with OpenAI response"
    (with-redefs [ai/call-ai-model (fn [_prompt _ai-config]
                                     {:success true,
                                      :content (ai/extract-content
                                                 openai-response-example
                                                 {})})]
      (let [result (ai/enhance-content {:title "Original Title",
                                        :description "Original Desc"}
                                       {})]
        (is (map? result))
        (is (contains? result :title))
        (is (contains? result :description))
        ;; Should get enhanced values from the parsed OpenAI fixture JSON
        (is (= (:title result) "Enhanced Title"))
        (is (= (:description result) "Enhanced description")))))
  (testing "End-to-end enhancement with OpenRouter Linux response"
    (with-redefs [ai/call-ai-model (fn [_prompt _ai-config]
                                     {:success true,
                                      :content
                                        (ai/extract-content
                                          openrouter-linux-actual-response
                                          {})})]
      (let [result (ai/enhance-content
                     {:title "Update minReplicas of webServer to 6",
                      :description "we can't scale down far anymore"}
                     {})]
        (is (map? result))
        (is (contains? result :title))
        (is (contains? result :description))
        ;; Should get enhanced values from the parsed OpenRouter JSON
        (is
          (=
            (:title result)
            "Increase webServer minimum replica count from current to 6 for traffic resilience"))
        (is (.contains
              (:description result)
              "Currently, the system's ability to handle traffic bursts")))))
  (testing "Handles AI call failure gracefully"
    (with-redefs [ai/call-ai-model (fn [_prompt _ai-config]
                                     {:success false,
                                      :error :timeout,
                                      :message "Timeout occurred"})]
      (let [result (ai/enhance-content {:title "Original Title",
                                        :description "Original Desc"}
                                       {})]
        (is (map? result))
        ;; Should fall back to original content on failure
        (is (= (:title result) "Original Title"))
        (is (= (:description result) "Original Desc")))))
  (testing "Handles missing content gracefully"
    (with-redefs [ai/call-ai-model (fn [_prompt _ai-config]
                                     {:success true, :content nil})]
      (let [result (ai/enhance-content {:title "Original Title",
                                        :description "Original Desc"}
                                       {})]
        (is (map? result))
        ;; Should fall back to original content when no content returned
        (is (= (:title result) "Original Title"))
        (is (= (:description result) "Original Desc"))))))

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