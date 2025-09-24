(ns lib.ai
  "Simple AI client for content enhancement via SMG gateway"
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn substitute-template-vars
  "Replace template variables in a string with actual values"
  [template vars]
  (when-not (string? template)
    (throw (ex-info "Template must be a string"
                    {:type :invalid-input :value template})))
  (when-not (map? vars)
    (throw (ex-info "Variables must be a map"
                    {:type :invalid-input :value vars})))
  (->> vars
       (reduce (fn [text [var-name var-value]]
                 (str/replace text (str "{" (name var-name) "}") (str var-value)))
               template)))

(defn build-messages-from-template
  "Build messages array from configurable template"
  [template vars]
  (when-not (sequential? template)
    (throw (ex-info "Template must be a sequence"
                    {:type :invalid-input :value template})))
  (when-not (map? vars)
    (throw (ex-info "Variables must be a map"
                    {:type :invalid-input :value vars})))
  (->> template
       (mapv (fn [msg-template]
               (-> msg-template
                   (update :content substitute-template-vars vars))))))

(defn build-messages
  "Build messages for API request using configurable template"
  [{:keys [title description]} ai-config]
  (when-not (and title (string? title))
    (throw (ex-info "Title must be a non-empty string"
                    {:type :invalid-input :value title})))
  (when-not (map? ai-config)
    (throw (ex-info "AI config must be a map"
                    {:type :invalid-input :value ai-config})))
  (let [vars {:prompt (:prompt ai-config "You are helpful"),
              :title title,
              :description (or description ""),
              :title_and_description (str "Title: "
                                          title
                                          (when (not (str/blank? description))
                                            (str "\nDescription: "
                                                 description)))}
        template (:message-template ai-config)]
    (build-messages-from-template template vars)))

(defn build-headers
  "Build HTTP headers for AI requests with proper identification"
  [ai-config]
  (when-not (map? ai-config)
    (throw (ex-info "AI config must be a map"
                    {:type :invalid-input :value ai-config})))
  (let [base {"Authorization" (str "Bearer " (:api-key ai-config)),
              "Content-Type" "application/json",
              "User-Agent"
              "Crucible/1.0 (+https://github.com/KyleOndy/crucible)"}]
    (if (str/includes? (:gateway-url ai-config) "openrouter.ai")
      (merge base
             {"HTTP-Referer" "crucible-cli",
              "X-Title" "Crucible Development Tool"})
      base)))

(defn call-ai-model
  "Core function to call AI model with a simple prompt string"
  [prompt ai-config]
  (when-not (and prompt (string? prompt))
    (throw (ex-info "Prompt must be a non-empty string"
                    {:type :invalid-input :value prompt})))
  (when-not (map? ai-config)
    (throw (ex-info "AI config must be a map"
                    {:type :invalid-input :value ai-config})))
  (try
    (let [messages [{:role "user", :content prompt}]
          request-body {:model (:model ai-config "gpt-4"),
                        :max_tokens (:max-tokens ai-config 1024),
                        :messages messages}
          ;; Validate and generate JSON
          json-string (try (json/generate-string request-body)
                           (catch Exception e
                             (throw (ex-info "Failed to generate JSON"
                                             {:error (.getMessage e)}))))
          ;; Build headers
          headers (build-headers ai-config)
          ;; Debug: Show detailed request/response information
          _ (when (:debug ai-config)
              (println "\n=== DEBUG: AI Request/Response ===")
              (println (str "Timestamp: " (java.time.Instant/now)))
              (println (str "URL: " (:gateway-url ai-config)))
              (println (str "Model: " (:model request-body)))
              (println (str "Max tokens: " (:max_tokens request-body)))
              (println (str "Prompt length: " (count prompt) " characters"))
              (println "\n--- Request Headers ---")
              (doseq [[k v] headers]
                (if (= k "Authorization")
                  (println (str "  " k
                                ": "
                                (if (str/blank? v) "[EMPTY]" "[REDACTED]")))
                  (println (str "  " k ": " v))))
              (println "\n--- Request Body (JSON) ---")
              (try
                (let [pretty-json (json/generate-string request-body {:pretty true})]
                  (println pretty-json))
                (catch Exception e
                  (println (str "Failed to pretty-print JSON: " (.getMessage e)))
                  (println json-string)))
              (println (str "\nJSON byte length: "
                            (count (.getBytes json-string "UTF-8"))
                            " bytes"))
              (println "==================================\n"))
          response (http/post (:gateway-url ai-config)
                              {:headers headers,
                               :body json-string,
                               :timeout (:timeout-ms ai-config 5000),
                               :throw false})
          ;; Debug: Show response details for all responses when debugging
          _ (when (:debug ai-config)
              (println "\n=== DEBUG: AI Response ===")
              (println (str "Response timestamp: " (java.time.Instant/now)))
              (println (str "Status: " (:status response)))
              (println "\n--- Response Headers ---")
              (doseq [[k v] (:headers response)]
                (println (str "  " k ": " v)))
              (println "\n--- Response Body ---")
              (if-let [body (:body response)]
                (try
                  ;; Try to pretty-print JSON response
                  (let [parsed-body (json/parse-string body true)
                        pretty-body (json/generate-string parsed-body {:pretty true})]
                    (println pretty-body))
                  (catch Exception _
                    ;; If not JSON, just print raw body
                    (println body)))
                (println "[No response body]"))
              (println "==========================\n"))]
      ;; Return structured response using cond-> for cleaner flow
      (cond-> {:status (:status response)
               :body (:body response)}
        (= 200 (:status response))
        (assoc :success true
               :response (json/parse-string (:body response) true)
               :content (get-in (json/parse-string (:body response) true)
                                [:choices 0 :message :content]))
        (= 400 (:status response))
        (assoc :success false
               :error :bad-request
               :message "Bad request - invalid JSON or parameters"
               :details (:body response))
        (= 401 (:status response))
        (assoc :success false
               :error :unauthorized
               :message "Authentication failed - check API key"
               :details (:body response))
        (= 429 (:status response))
        (assoc :success false
               :error :rate-limited
               :message "Rate limited by AI gateway"
               :details (:body response))
        (and (not= 200 (:status response))
             (not= 400 (:status response))
             (not= 401 (:status response))
             (not= 429 (:status response)))
        (assoc :success false
               :error :api-error
               :message (str "AI gateway returned " (:status response))
               :details (:body response))))
    (catch java.net.SocketTimeoutException _
      {:success false, :error :timeout, :message "AI gateway timeout"})
    (catch Exception e
      (when (:debug ai-config) (println "Stack trace:") (.printStackTrace e))
      {:success false,
       :error :exception,
       :message (str "AI call failed: " (.getMessage e))})))

(defn enhance-content
  "Send content to AI gateway for enhancement using Jira ticket format"
  [{:keys [title description]} ai-config]
  (when-not (and title (string? title))
    (throw (ex-info "Title must be a non-empty string"
                    {:type :invalid-input :value title})))
  (when-not (map? ai-config)
    (throw (ex-info "AI config must be a map"
                    {:type :invalid-input :value ai-config})))
  (println "Enhancing content with AI...")
  ;; Build Jira-specific prompt from title and description
  (let [vars {:prompt
              (:prompt
               ai-config
               "Enhance this Jira ticket for clarity and professionalism."),
              :title title,
              :description (or description ""),
              :title_and_description (str "Title: "
                                          title
                                          (when (not (str/blank? description))
                                            (str "\nDescription: "
                                                 description)))}
        template (:message-template ai-config)
        ;; Build the enhancement prompt using the message template
        messages (if template
                   (build-messages-from-template template vars)
                   ;; Fallback to simple prompt if no template
                   [{:role "user",
                     :content (str (:prompt vars)
                                   "\n\n"
                                   (:title_and_description vars))}])
        ;; Create a simple prompt string from the messages (take the last user message)
        prompt (->> messages
                    (filter #(= "user" (:role %)))
                    last
                    :content
                    (or (str (:prompt vars) "\n\n" (:title_and_description vars))))
        ;; Call the core AI function
        result (call-ai-model prompt ai-config)]
    (if (:success result)
      ;; Parse the response for ticket enhancement
      (let [content-text (:content result)
            _ (when (:debug ai-config)
                (println "\n=== DEBUG: Content Parsing ===")
                (println (str "Raw AI content: " (pr-str content-text)))
                (println "Attempting to parse response..."))
            enhanced
            (if content-text
                ;; Try to parse JSON response for structured enhancement
              (try (let [parsed-content (json/parse-string content-text true)
                         _ (when (:debug ai-config)
                             (println "Successfully parsed as JSON:")
                             (println (json/generate-string parsed-content {:pretty true})))]
                     {:title (or (:title parsed-content)
                                 (:enhanced_title parsed-content)
                                 title),
                      :description (or (:description parsed-content)
                                       (:enhanced_description parsed-content)
                                       description)})
                   (catch Exception e
                     (when (:debug ai-config)
                       (println (str "JSON parsing failed: " (.getMessage e)))
                       (println "Using content as enhanced description"))
                       ;; If parsing fails, use the content as enhanced description
                     {:title title,
                      :description (or content-text description)}))
                ;; Fallback to original content if no content found
              (do
                (when (:debug ai-config)
                  (println "No content received from AI - using original"))
                {:title title, :description description}))
            _ (when (:debug ai-config)
                (println "\n--- Final Enhancement Result ---")
                (println (str "Original title: " (pr-str title)))
                (println (str "Enhanced title: " (pr-str (:title enhanced))))
                (println (str "Title changed: " (not= title (:title enhanced))))
                (println (str "Original description: " (pr-str description)))
                (println (str "Enhanced description: " (pr-str (:description enhanced))))
                (println (str "Description changed: " (not= description (:description enhanced))))
                (println "===============================\n"))]
        enhanced)
      ;; Handle errors - print error and return original content
      (do (case (:error result)
            :unauthorized
            (println
             "AI authentication failed - check your API key configuration")
            :timeout (println "AI gateway timeout, using original content")
            :rate-limited (println
                           "AI gateway rate limited, using original content")
            (println (:message result)))
          (when (:debug ai-config)
            (println "\n=== DEBUG: AI Error - Using Original Content ===")
            (println (str "Error type: " (:error result)))
            (println (str "Error message: " (:message result)))
            (println "================================================\n"))
          {:title title, :description description}))))

(defn test-gateway
  "Test AI gateway connectivity and authentication"
  [ai-config]
  (when-not (map? ai-config)
    (throw (ex-info "AI config must be a map"
                    {:type :invalid-input :value ai-config})))
  (try
    ;; Use a minimal test request to the actual endpoint
    (let [test-messages [{:role "user", :content "test"}]
          request-body {:model (:model ai-config "gpt-4"),
                        :max_tokens 10, ; Use minimal tokens for test
                        :messages test-messages}
          json-string (json/generate-string request-body)
          _ (when (:debug ai-config)
              (println "\n=== DEBUG: Gateway Test Request ===")
              (println (str "URL: " (:gateway-url ai-config)))
              (println (str "Test request JSON:"))
              (println json-string)
              (println "====================================\n"))
          response (http/post (:gateway-url ai-config)
                              {:headers (build-headers ai-config),
                               :body json-string,
                               :timeout (:timeout-ms ai-config 3000),
                               :throw false})
          _ (when (:debug ai-config)
              (println "\n=== DEBUG: Gateway Test Response ===")
              (println (str "Status: " (:status response)))
              (when (not= 200 (:status response))
                (println (str "Headers: " (:headers response)))
                (println (str "Body: " (:body response))))
              (println "=====================================\n"))]
      {:success (= 200 (:status response)),
       :status (:status response),
       :message
       (cond (= 200 (:status response)) "Gateway is accessible and working"
             (= 400 (:status response)) (str "Bad request (400): "
                                             (:body response))
             (= 401 (:status response))
             "Authentication failed (401) - check API key"
             (= 403 (:status response))
             "Access forbidden (403) - check permissions"
             (= 404 (:status response))
             "Endpoint not found (404) - check gateway URL"
             (= 429 (:status response))
             "Rate limited (429) - gateway is accessible but rate limited"
             :else (str "Gateway error: "
                        (:status response)
                        (when (:body response)
                          (str " - " (:body response)))))})
    (catch Exception e
      (when (:debug ai-config)
        (println "\n=== DEBUG: Gateway Test Exception ===")
        (println (str "Error: " (.getMessage e)))
        (.printStackTrace e)
        (println "=====================================\n"))
      {:success false,
       :error (.getMessage e),
       :message (str "Cannot reach gateway: " (.getMessage e))})))

(defn show-enhanced-content
  "Show enhanced content with appropriate formatting based on mode"
  ([original enhanced]
   (show-enhanced-content original enhanced false))
  ([original enhanced ai-only-mode?]
   (when-not (map? original)
     (throw (ex-info "Original content must be a map"
                     {:type :invalid-input :value original})))
   (when-not (map? enhanced)
     (throw (ex-info "Enhanced content must be a map"
                     {:type :invalid-input :value enhanced})))
   (when (not= original enhanced)
     (println "\n=== AI Enhancement Results ===")
     (if ai-only-mode?
       ;; Clean output for --ai-only mode - just show the enhanced content
       (do
         (when (:title enhanced)
           (println (str "Title: " (:title enhanced))))
         (when (:description enhanced)
           (println (str "Description: " (:description enhanced)))))
       ;; Before/after comparison for regular enhancement mode
       (do
         (when (not= (:title original) (:title enhanced))
           (println "Title:")
           (println (str "  Before: " (:title original)))
           (println (str "  After:  " (:title enhanced))))
         (when (not= (:description original) (:description enhanced))
           (println "Description:")
           (println (str "  Before: "
                         (str/replace (:description original) "\n" "\\n")))
           (println (str "  After:  "
                         (str/replace (:description enhanced) "\n" "\\n"))))))
     (println "==============================\n"))))
