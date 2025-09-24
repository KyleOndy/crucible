(ns lib.result
  "Error handling and result utilities following consistent patterns.
  
  This namespace provides utilities for structured error handling that replace
  System/exit calls with data returns, enabling better composition and testing.")


;; Core Result Types

(defn success
  "Create a success result map.
  
  Args:
    result - The successful result data
    
  Returns:
    {:success true :result data}
    
  Example:
    (success {:ticket-id \"PROJ-123\"})
    ;; => {:success true :result {:ticket-id \"PROJ-123\"}}"
  [result]
  {:success true, :result result})


(defn failure
  "Create a failure result map.
  
  Args:
    error-type - Keyword identifying the error category
    message - Human-readable error message
    context - Optional map with additional error context
    
  Returns:
    {:success false :error {:type :keyword :message \"...\" :context {...}}}
    
  Examples:
    (failure :validation \"Invalid ticket ID format\" {:input \"ABC\"})
    (failure :network \"Connection timeout\")
    (failure :auth \"Invalid credentials\" {:status 401})"
  ([error-type message] (failure error-type message nil))
  ([error-type message context]
   {:success false,
    :error (cond-> {:type error-type, :message message}
             context (assoc :context context))}))


(defn success?
  "Check if result indicates success.
  
  Args:
    result - Result map to check
    
  Returns:
    Boolean indicating success
    
  Example:
    (success? (success \"data\")) ;; => true
    (success? (failure :error \"msg\")) ;; => false"
  [result]
  (= true (:success result)))


(defn failure?
  "Check if result indicates failure.
  
  Args:
    result - Result map to check
    
  Returns:
    Boolean indicating failure
    
  Example:
    (failure? (failure :error \"msg\")) ;; => true
    (success? (success \"data\")) ;; => false"
  [result]
  (= false (:success result)))


;; Error Type Constants

(def error-types
  "Standard error type keywords for consistent categorization."
  {:validation :validation, ; Input validation failures
   :auth :authentication, ; Authentication/authorization failures
   :network :network, ; Network connectivity issues
   :not-found :not-found, ; Resource not found
   :config :configuration, ; Configuration problems
   :file-io :file-io, ; File system operations
   :external :external-api, ; External service failures
   :parse :parse, ; Data parsing/format errors
   :internal :internal}) ; Internal application errors

;; Exit Code Handling

(def exit-codes
  "Standard exit codes for CLI applications."
  {:success 0,
   :general-error 1,
   :misuse 2,
   :config-error 3,
   :auth-error 4,
   :network-error 5,
   :not-found 6,
   :file-error 7})


(defn exit-code-for-error
  "Map error type to appropriate exit code.
  
  Args:
    error-type - Keyword error type
    
  Returns:
    Integer exit code
    
  Example:
    (exit-code-for-error :authentication) ;; => 4
    (exit-code-for-error :unknown) ;; => 1"
  [error-type]
  (case error-type
    :validation (:misuse exit-codes)
    :authentication (:auth-error exit-codes)
    :network (:network-error exit-codes)
    :not-found (:not-found exit-codes)
    :configuration (:config-error exit-codes)
    :file-io (:file-error exit-codes)
    (:general-error exit-codes)))


;; Effect Tracking

(defn with-effects
  "Add effect tracking to a result.
  
  Args:
    result - Success or failure result map
    effects - Vector of effect keywords that occurred
    
  Returns:
    Result map with :effects key added
    
  Examples:
    (with-effects (success data) [:printed-output :wrote-file])
    (with-effects (failure :error \"msg\") [:attempted-file-read])"
  [result effects]
  (assoc result :effects effects))


;; Result Composition Utilities

(defn chain
  "Chain multiple operations that return results, stopping on first failure.
  
  Args:
    initial-value - Starting value
    & operations - Functions that take a value and return result maps
    
  Returns:
    Final result or first failure
    
  Example:
    (chain \"PROJ-123\"
           parse-ticket-id
           fetch-ticket-data
           validate-ticket)
    ;; Stops at first failure, returns success with final value if all succeed"
  [initial-value & operations]
  (reduce (fn [acc op] (if (success? acc) (op (:result acc)) acc))
    (success initial-value)
    operations))


(defn collect-results
  "Collect multiple results, returning success only if all succeed.
  
  Args:
    results - Sequence of result maps
    
  Returns:
    {:success true :result [all-results]} or first failure
    
  Example:
    (collect-results [(success 1) (success 2) (success 3)])
    ;; => {:success true :result [1 2 3]}
    
    (collect-results [(success 1) (failure :error \"bad\") (success 3)])
    ;; => {:success false :error {...}}"
  [results]
  (let [failures (filter failure? results)]
    (if (empty? failures) (success (mapv :result results)) (first failures))))


;; Validation Helpers

(defn validate
  "Validate a value against a predicate, returning result.
  
  Args:
    predicate - Function that returns truthy for valid values
    error-message - Message for validation failure
    value - Value to validate
    
  Returns:
    Success with value if valid, failure if invalid
    
  Example:
    (validate #(> % 0) \"Must be positive\" 5)
    ;; => {:success true :result 5}
    
    (validate #(> % 0) \"Must be positive\" -1)
    ;; => {:success false :error {:type :validation :message \"Must be positive\"}}"
  [predicate error-message value]
  (if (predicate value)
    (success value)
    (failure :validation error-message {:value value})))


(defn validate-required
  "Validate that a value is present (not nil or blank).
  
  Args:
    field-name - Name of field for error message
    value - Value to check
    
  Returns:
    Success with value if present, failure if missing
    
  Example:
    (validate-required \"ticket-id\" \"PROJ-123\")
    ;; => {:success true :result \"PROJ-123\"}
    
    (validate-required \"ticket-id\" nil)
    ;; => {:success false :error {:type :validation :message \"ticket-id is required\"}}"
  [field-name value]
  (validate #(and % (not (and (string? %) (clojure.string/blank? %))))
            (str field-name " is required")
            value))


;; Safe Execution

(defn safely
  "Execute function safely, catching exceptions and returning result.
  
  Args:
    f - Function to execute
    error-type - Error type keyword for failures (default :internal)
    
  Returns:
    Success with function result or failure with exception info
    
  Example:
    (safely #(Integer/parseInt \"123\"))
    ;; => {:success true :result 123}
    
    (safely #(Integer/parseInt \"abc\") :parse)
    ;; => {:success false :error {:type :parse :message \"...\" :context {...}}}"
  ([f] (safely f :internal))
  ([f error-type]
   (try (success (f))
        (catch Exception e
          (failure error-type
                   (.getMessage e)
                   {:exception (str (type e)),
                    :stack-trace (take 5 (.getStackTrace e))})))))
