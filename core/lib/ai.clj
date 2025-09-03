(ns lib.ai
  "Simple AI client for content enhancement via SMG gateway"
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn substitute-template-vars
  "Replace template variables in a string with actual values"
  [template vars]
  (reduce (fn [text [var-name var-value]]
            (str/replace text (str "{" (name var-name) "}") (str var-value)))
          template
          vars))

(defn build-messages-from-template
  "Build messages array from configurable template"
  [template vars]
  (mapv (fn [msg-template]
          (-> msg-template
              (update :content substitute-template-vars vars)))
        template))

(defn build-messages
  "Build messages for API request using configurable template"
  [{:keys [title description]} ai-config]
  (let [vars {:prompt (:prompt ai-config "You are helpful")
              :title title
              :description (or description "")
              :title_and_description (str "Title: " title
                                          (when (not (str/blank? description))
                                            (str "\nDescription: " description)))}
        template (:message-template ai-config)]
    (build-messages-from-template template vars)))

(defn enhance-content
  "Send content to AI gateway for enhancement"
  [{:keys [title description]} ai-config]
  (try
    (println "Calling AI gateway...")
    (let [messages (build-messages {:title title :description description} ai-config)
          request-body {:model (:model ai-config "gpt-4")
                        :max_tokens (:max-tokens ai-config 1024)
                        :messages messages}

          ;; Validate and generate JSON
          json-string (try
                        (json/generate-string request-body)
                        (catch Exception e
                          (println (str "ERROR: Failed to generate JSON: " (.getMessage e)))
                          (throw e)))

          ;; Debug: Show request details
          _ (when (:debug ai-config)
              (println "\n=== DEBUG: Request Details ===")
              (println (str "URL: " (:gateway-url ai-config)))
              (println (str "Model: " (:model request-body)))
              (println (str "Max tokens: " (:max_tokens request-body)))
              (println "\nMessage Structure Validation:")
              (println (str "  Messages is array? " (vector? messages)))
              (println (str "  Message count: " (count messages)))
              (doseq [[idx msg] (map-indexed vector messages)]
                (println (str "  Message " idx ":"
                              " has :role? " (contains? msg :role)
                              ", has :content? " (contains? msg :content)
                              ", role=" (:role msg))))
              (println "\nMessages:")
              (doseq [msg messages]
                (println (str "  Role: " (:role msg)))
                (println (str "  Content: " (subs (:content msg) 0 (min 200 (count (:content msg))))
                              (when (> (count (:content msg)) 200) "..."))))
              (println "\nRequest body (Clojure map):")
              (println (json/generate-string request-body {:pretty true}))
              (println "\nRaw JSON string to be sent:")
              (println json-string)
              (println (str "\nJSON byte length: " (count (.getBytes json-string "UTF-8")) " bytes"))
              (println "==============================\n"))

          response (http/post (:gateway-url ai-config)
                              {:headers {"Authorization" (str "Bearer " (:api-key ai-config))
                                         "Content-Type" "application/json"}
                               :body json-string
                               :timeout (:timeout-ms ai-config 5000)
                               :throw false})

          ;; Debug: Show response details
          _ (when (and (:debug ai-config) (not= 200 (:status response)))
              (println "\n=== DEBUG: Response Details ===")
              (println (str "Status: " (:status response)))
              (println (str "Headers: " (:headers response)))
              (println (str "Body: " (:body response)))
              (when (= 400 (:status response))
                (println "\nDiagnostics for 400 error:")
                (println "1. Check if the JSON structure matches API expectations")
                (println "2. Verify field names (e.g., max_tokens vs maxTokens)")
                (println "3. Ensure message roles are valid (system/user/assistant)")
                (println "4. Check if the model name is supported by the gateway"))
              (println "==============================\n"))]

      (cond
        (= 200 (:status response))
        (let [result (json/parse-string (:body response) true)
              enhanced {:title (:enhanced_title result (:title result title))
                        :description (:enhanced_description result (:description result description))}]
          ;; Show API response when debug is enabled
          (when (:debug ai-config)
            (println (str "\nAPI Response: " (:body response))))
          enhanced)

        (= 400 (:status response))
        (do
          (println "\n=== ERROR: Bad Request (400) ===")
          (println "The AI gateway rejected the request. Common causes:")
          (println "  - Invalid JSON structure")
          (println "  - Invalid message format")
          (println "  - Missing required fields")
          (println "  - Invalid model name")
          (println "  - Field naming mismatch (max_tokens vs maxTokens)")
          (println "\nResponse body:")
          (println (:body response))
          (println "\nTip: Enable debug mode to see full request details:")
          (println "  Add :debug true to :ai section in config")
          (println "================================\n")
          {:title title :description description})

        (= 429 (:status response))
        (do
          (println "AI gateway rate limited, using original content")
          {:title title :description description})

        :else
        (do
          (println (str "AI gateway returned " (:status response) ": " (:body response)))
          {:title title :description description})))

    (catch java.net.SocketTimeoutException _
      (println "AI gateway timeout, using original content")
      {:title title :description description})

    (catch Exception e
      (println (str "AI enhancement failed: " (.getMessage e)))
      (when (:debug ai-config)
        (println "Stack trace:")
        (.printStackTrace e))
      {:title title :description description})))

(defn test-gateway
  "Test AI gateway connectivity and authentication"
  [ai-config]
  (try
    ;; Use a minimal test request to the actual endpoint
    (let [test-messages [{:role "user" :content "test"}]
          request-body {:model (:model ai-config "gpt-4")
                        :max_tokens 10 ; Use minimal tokens for test
                        :messages test-messages}
          json-string (json/generate-string request-body)

          _ (when (:debug ai-config)
              (println "\n=== DEBUG: Gateway Test Request ===")
              (println (str "URL: " (:gateway-url ai-config)))
              (println (str "Test request JSON:"))
              (println json-string)
              (println "====================================\n"))

          response (http/post (:gateway-url ai-config)
                              {:headers {"Authorization" (str "Bearer " (:api-key ai-config))
                                         "Content-Type" "application/json"}
                               :body json-string
                               :timeout (:timeout-ms ai-config 3000)
                               :throw false})

          _ (when (:debug ai-config)
              (println "\n=== DEBUG: Gateway Test Response ===")
              (println (str "Status: " (:status response)))
              (when (not= 200 (:status response))
                (println (str "Headers: " (:headers response)))
                (println (str "Body: " (:body response))))
              (println "=====================================\n"))]

      {:success (= 200 (:status response))
       :status (:status response)
       :message (cond
                  (= 200 (:status response))
                  "Gateway is accessible and working"

                  (= 400 (:status response))
                  (str "Bad request (400): " (:body response))

                  (= 401 (:status response))
                  "Authentication failed (401) - check API key"

                  (= 403 (:status response))
                  "Access forbidden (403) - check permissions"

                  (= 404 (:status response))
                  "Endpoint not found (404) - check gateway URL"

                  (= 429 (:status response))
                  "Rate limited (429) - gateway is accessible but rate limited"

                  :else
                  (str "Gateway error: " (:status response)
                       (when (:body response)
                         (str " - " (:body response)))))})

    (catch Exception e
      (when (:debug ai-config)
        (println "\n=== DEBUG: Gateway Test Exception ===")
        (println (str "Error: " (.getMessage e)))
        (.printStackTrace e)
        (println "=====================================\n"))
      {:success false
       :error (.getMessage e)
       :message (str "Cannot reach gateway: " (.getMessage e))})))

(defn show-diff
  "Show difference between original and enhanced content"
  [original enhanced]
  (when (not= original enhanced)
    (println "\n=== AI Enhancement Results ===")
    (when (not= (:title original) (:title enhanced))
      (println "Title:")
      (println (str "  Before: " (:title original)))
      (println (str "  After:  " (:title enhanced))))
    (when (not= (:description original) (:description enhanced))
      (println "Description:")
      (println (str "  Before: " (str/replace (:description original) "\n" "\\n")))
      (println (str "  After:  " (str/replace (:description enhanced) "\n" "\\n"))))
    (println "==============================\n")))
