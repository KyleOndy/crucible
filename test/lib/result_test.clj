(ns lib.result-test
  "Tests for lib.result error handling utilities"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lib.result :as result]))


(deftest test-success
  (testing "Creating success results"
    (let [data {:ticket-id "PROJ-123"}
          res (result/success data)]
      (is (= {:success true, :result data} res))
      (is (result/success? res))
      (is (not (result/failure? res)))))
  (testing "Success with nil data"
    (let [res (result/success nil)]
      (is (= {:success true, :result nil} res))
      (is (result/success? res)))))


(deftest test-failure
  (testing "Creating failure results with context"
    (let [res (result/failure :validation "Invalid format" {:input "ABC"})]
      (is (= {:success false,
              :error {:type :validation,
                      :message "Invalid format",
                      :context {:input "ABC"}}}
             res))
      (is (result/failure? res))
      (is (not (result/success? res)))))
  (testing "Creating failure results without context"
    (let [res (result/failure :network "Connection timeout")]
      (is (= {:success false,
              :error {:type :network, :message "Connection timeout"}}
             res))
      (is (result/failure? res)))))


(deftest test-error-types
  (testing "Standard error types are defined"
    (is (= :validation (:validation result/error-types)))
    (is (= :authentication (:auth result/error-types)))
    (is (= :network (:network result/error-types)))
    (is (= :not-found (:not-found result/error-types)))
    (is (= :configuration (:config result/error-types)))
    (is (= :file-io (:file-io result/error-types)))
    (is (= :external-api (:external result/error-types)))
    (is (= :parse (:parse result/error-types)))
    (is (= :internal (:internal result/error-types)))))


(deftest test-exit-codes
  (testing "Standard exit codes are defined"
    (is (= 0 (:success result/exit-codes)))
    (is (= 1 (:general-error result/exit-codes)))
    (is (= 2 (:misuse result/exit-codes)))
    (is (= 3 (:config-error result/exit-codes)))
    (is (= 4 (:auth-error result/exit-codes)))
    (is (= 5 (:network-error result/exit-codes)))
    (is (= 6 (:not-found result/exit-codes)))
    (is (= 7 (:file-error result/exit-codes)))))


(deftest test-exit-code-for-error
  (testing "Error types map to correct exit codes"
    (is (= 2 (result/exit-code-for-error :validation)))
    (is (= 4 (result/exit-code-for-error :authentication)))
    (is (= 5 (result/exit-code-for-error :network)))
    (is (= 6 (result/exit-code-for-error :not-found)))
    (is (= 3 (result/exit-code-for-error :configuration)))
    (is (= 7 (result/exit-code-for-error :file-io)))
    (is (= 1 (result/exit-code-for-error :unknown-type)))))


(deftest test-with-effects
  (testing "Adding effects to success result"
    (let [success-res (result/success "data")
          with-effects (result/with-effects success-res
                                            [:printed-output :wrote-file])]
      (is (= {:success true,
              :result "data",
              :effects [:printed-output :wrote-file]}
             with-effects))))
  (testing "Adding effects to failure result"
    (let [failure-res (result/failure :network "Connection failed")
          with-effects (result/with-effects failure-res
                                            [:attempted-connection])]
      (is (= {:success false,
              :error {:type :network, :message "Connection failed"},
              :effects [:attempted-connection]}
             with-effects)))))


(deftest test-chain
  (testing "Chaining successful operations"
    (let [parse-id (fn [id]
                     (result/success {:project "PROJ",
                                      :number (Integer/parseInt id)}))
          validate-project (fn [ticket]
                             (if (= "PROJ" (:project ticket))
                               (result/success ticket)
                               (result/failure :validation "Invalid project")))
          add-prefix (fn [ticket]
                       (result/success (str "Valid: " (:project ticket)
                                            "-" (:number ticket))))
          res (result/chain "123" parse-id validate-project add-prefix)]
      (is (result/success? res))
      (is (= "Valid: PROJ-123" (:result res)))))
  (testing "Chaining with failure stops early"
    (let [parse-id (fn [id]
                     (result/success {:project "PROJ",
                                      :number (Integer/parseInt id)}))
          validate-project (fn [ticket]
                             (result/failure :validation "Invalid project"))
          add-prefix (fn [ticket]
                       (result/success (str "Valid: " (:project ticket)
                                            "-" (:number ticket))))
          res (result/chain "123" parse-id validate-project add-prefix)]
      (is (result/failure? res))
      (is (= :validation (get-in res [:error :type])))))
  (testing "Chaining with exception should be wrapped with safely"
    ;; If functions can throw exceptions, they should be wrapped with
    ;; safely
    (let [parse-bad-number (fn [id]
                             (result/safely #(result/success
                                               {:project "PROJ",
                                                :number (Integer/parseInt
                                                          "bad")})
                                            :parse))
          validate-project (fn [ticket] (result/success ticket))
          res (result/chain "123" parse-bad-number validate-project)]
      (is (result/failure? res))
      (is (= :parse (get-in res [:error :type]))))))


(deftest test-collect-results
  (testing "Collecting all successful results"
    (let [results [(result/success 1) (result/success 2) (result/success 3)]
          collected (result/collect-results results)]
      (is (result/success? collected))
      (is (= [1 2 3] (:result collected)))))
  (testing "Collecting with one failure returns first failure"
    (let [results [(result/success 1) (result/failure :validation "Bad input")
                   (result/success 3)]
          collected (result/collect-results results)]
      (is (result/failure? collected))
      (is (= :validation (get-in collected [:error :type])))))
  (testing "Collecting empty results"
    (let [collected (result/collect-results [])]
      (is (result/success? collected))
      (is (= [] (:result collected))))))


(deftest test-validate
  (testing "Successful validation"
    (let [res (result/validate #(> % 0) "Must be positive" 5)]
      (is (result/success? res))
      (is (= 5 (:result res)))))
  (testing "Failed validation"
    (let [res (result/validate #(> % 0) "Must be positive" -1)]
      (is (result/failure? res))
      (is (= :validation (get-in res [:error :type])))
      (is (= "Must be positive" (get-in res [:error :message])))
      (is (= -1 (get-in res [:error :context :value]))))))


(deftest test-validate-required
  (testing "Valid non-empty string"
    (let [res (result/validate-required "ticket-id" "PROJ-123")]
      (is (result/success? res))
      (is (= "PROJ-123" (:result res)))))
  (testing "Valid non-string value"
    (let [res (result/validate-required "count" 42)]
      (is (result/success? res))
      (is (= 42 (:result res)))))
  (testing "Nil value fails"
    (let [res (result/validate-required "ticket-id" nil)]
      (is (result/failure? res))
      (is (= "ticket-id is required" (get-in res [:error :message])))))
  (testing "Empty string fails"
    (let [res (result/validate-required "ticket-id" "")]
      (is (result/failure? res))
      (is (= "ticket-id is required" (get-in res [:error :message])))))
  (testing "Blank string fails"
    (let [res (result/validate-required "ticket-id" "   ")]
      (is (result/failure? res))
      (is (= "ticket-id is required" (get-in res [:error :message]))))))


(deftest test-safely
  (testing "Successful function execution"
    (let [res (result/safely #(+ 1 2))]
      (is (result/success? res))
      (is (= 3 (:result res)))))
  (testing "Function throws exception with default error type"
    (let [res (result/safely #(Integer/parseInt "not-a-number"))]
      (is (result/failure? res))
      (is (= :internal (get-in res [:error :type])))
      (is (string? (get-in res [:error :message])))
      (is (contains? (:context (:error res)) :exception))))
  (testing "Function throws exception with custom error type"
    (let [res (result/safely #(Integer/parseInt "not-a-number") :parse)]
      (is (result/failure? res))
      (is (= :parse (get-in res [:error :type])))
      (is (string? (get-in res [:error :message])))
      (is (contains? (:context (:error res)) :exception))
      (is (contains? (:context (:error res)) :stack-trace))))
  (testing "Function returns nil successfully"
    (let [res (result/safely #(when false "not-returned"))]
      (is (result/success? res))
      (is (nil? (:result res))))))


(deftest test-result-composition
  (testing "Complex composition with multiple utilities"
    (let [process-ticket-id
            (fn [input]
              (result/chain
                input
                #(result/validate-required "ticket-id" %)
                #(result/validate (fn [s] (re-matches #"[A-Z]+-\d+" s))
                                  "Invalid format"
                                  %)
                #(result/safely (fn []
                                  (let [[project number] (str/split % #"-")]
                                    {:project project,
                                     :number (Integer/parseInt number)}))
                                :parse)))
          ;; Test successful path
          success-res (process-ticket-id "PROJ-123")]
      (is (result/success? success-res))
      (is (= {:project "PROJ", :number 123} (:result success-res)))
      ;; Test validation failure
      (let [nil-res (process-ticket-id nil)]
        (is (result/failure? nil-res))
        (is (= :validation (get-in nil-res [:error :type]))))
      ;; Test format validation failure
      (let [format-res (process-ticket-id "invalid")]
        (is (result/failure? format-res))
        (is (= :validation (get-in format-res [:error :type]))))
      ;; Test parse failure - PROJ-notanumber fails regex validation before
      ;; parsing. To test parse failure, we need input that passes
      ;; validation but fails parsing
      (let [parse-test-fn (fn [input]
                            (result/safely #(Integer/parseInt input) :parse))
            parse-res (parse-test-fn "not-a-number")]
        (is (result/failure? parse-res))
        (is (= :parse (get-in parse-res [:error :type])))))))
