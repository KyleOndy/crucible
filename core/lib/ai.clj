(ns lib.ai
  "Simple AI client for content enhancement via SMG gateway"
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]))


(defn enhance-content
  "Send content to AI gateway for enhancement"
  [{:keys [title description]} ai-config]
  (try
    (println "Calling AI gateway...")
    (let [request-body {:prompt (:prompt ai-config)
                        :title title
                        :description description
                        :format "jira"}

          response (http/post (:gateway-url ai-config)
                              {:headers {"Authorization" (str "Bearer " (:api-key ai-config))
                                         "Content-Type" "application/json"}
                               :body (json/generate-string request-body)
                               :timeout (:timeout-ms ai-config 5000)
                               :throw false})]

      (cond
        (= 200 (:status response))
        (let [result (json/parse-string (:body response) true)
              enhanced {:title (:enhanced_title result (:title result title))
                        :description (:enhanced_description result (:description result description))}]
          enhanced)

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
      {:title title :description description})))


(defn test-gateway
  "Test AI gateway connectivity and authentication"
  [ai-config]
  (try
    (let [response (http/get (str (:gateway-url ai-config) "/health")
                             {:headers {"Authorization" (str "Bearer " (:api-key ai-config))}
                              :timeout 3000
                              :throw false})]
      {:success (< (:status response) 400)
       :status (:status response)
       :message (if (< (:status response) 400)
                  "Gateway is accessible"
                  (str "Gateway error: " (:status response)))})
    (catch Exception e
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
