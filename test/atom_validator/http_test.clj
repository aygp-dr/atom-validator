(ns atom-validator.http-test
  "Tests for HTTP feed fetching using an in-process HTTP server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [atom-validator.http :as http]
            [atom-validator.core :as v])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.io OutputStream]
           [java.util.concurrent Executors ThreadFactory]))

;; =============================================================================
;; In-process HTTP mock server
;; =============================================================================

(def ^:dynamic *server* nil)
(def ^:dynamic *handlers* nil)
(def ^:dynamic *base-url* nil)

(def sample-atom-feed
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>
<feed xmlns=\"http://www.w3.org/2005/Atom\">
  <title>Test Feed</title>
  <id>urn:uuid:test-feed-1</id>
  <updated>2026-06-19T00:00:00Z</updated>
  <link href=\"https://example.com/\" rel=\"alternate\"/>
  <author><name>Tester</name></author>
  <entry>
    <id>urn:uuid:entry-1</id>
    <title>Friday Brief</title>
    <updated>2026-06-19T00:00:00Z</updated>
    <link href=\"https://example.com/1/\" rel=\"alternate\"/>
    <summary>First entry</summary>
  </entry>
</feed>")

(def sample-json-feed
  "{
  \"version\": \"https://jsonfeed.org/version/1.1\",
  \"title\": \"Test JSON Feed\",
  \"home_page_url\": \"https://example.com/\",
  \"feed_url\": \"https://example.com/feed.json\",
  \"items\": [
    {\"id\": \"1\", \"content_text\": \"Hello\", \"url\": \"https://example.com/1/\"}
  ]
}")

(defn- write-response
  "Write a response on the given HttpExchange."
  [^HttpExchange exchange status content-type body & {:keys [headers]}]
  (let [bytes (.getBytes (or body "") "UTF-8")
        rh (.getResponseHeaders exchange)]
    (when content-type
      (.add rh "Content-Type" content-type))
    (doseq [[k v] (or headers {})]
      (.add rh k v))
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [^OutputStream os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- make-handler
  "Build an HttpHandler that dispatches by path to (handlers path) and falls
  back to 404. Each handler is a function (fn [exchange] ...)."
  [handlers-atom]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [path (-> exchange .getRequestURI .getPath)
              method (.getRequestMethod exchange)
              handler (get @handlers-atom path)]
          (if handler
            (handler exchange method)
            (write-response exchange 404 "text/plain" "Not Found")))
        (catch Throwable t
          (write-response exchange 500 "text/plain"
                          (str "Server error: " (.getMessage t))))))))

(defn- daemon-thread-factory
  "ThreadFactory producing daemon threads so the mock server never blocks JVM
  exit. HttpServer.stop does NOT shut down a user-supplied executor, so without
  this the non-daemon worker thread lingers and hangs `clj -X:test`."
  [name]
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable ^String name)
        (.setDaemon true)))))

(defn- start-server!
  "Start an HTTP server on an ephemeral port.
  Returns [server handlers-atom port executor]."
  []
  (let [handlers-atom (atom {})
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newSingleThreadExecutor
                  (daemon-thread-factory "mock-http-server"))]
    (.createContext server "/" (make-handler handlers-atom))
    (.setExecutor server executor)
    (.start server)
    [server handlers-atom (.getPort (.getAddress server)) executor]))

(defn with-server
  "Fixture: start a fresh mock HTTP server for each test."
  [f]
  (let [[server handlers-atom port executor] (start-server!)]
    (try
      (binding [*server* server
                *handlers* handlers-atom
                *base-url* (str "http://127.0.0.1:" port)]
        (f))
      (finally
        (.stop server 0)
        ;; stop does not touch the executor; shut it down explicitly.
        (.shutdownNow ^java.util.concurrent.ExecutorService executor)))))

(use-fixtures :each with-server)

(defn- register!
  "Register a handler function for the given path."
  [path handler]
  (swap! *handlers* assoc path handler))

;; =============================================================================
;; valid-feed-content-type?
;; =============================================================================

(deftest content-type-detection
  (testing "Accepts known feed content types"
    (is (http/valid-feed-content-type? "application/atom+xml"))
    (is (http/valid-feed-content-type? "application/rss+xml"))
    (is (http/valid-feed-content-type? "application/feed+json"))
    (is (http/valid-feed-content-type? "application/xml"))
    (is (http/valid-feed-content-type? "text/xml"))
    (is (http/valid-feed-content-type? "application/json")))
  (testing "Handles parameters and case"
    (is (http/valid-feed-content-type? "application/atom+xml; charset=utf-8"))
    (is (http/valid-feed-content-type? "APPLICATION/ATOM+XML"))
    (is (http/valid-feed-content-type? "  application/atom+xml  ")))
  (testing "Rejects unknown content types"
    (is (not (http/valid-feed-content-type? "text/html")))
    (is (not (http/valid-feed-content-type? "image/png")))
    (is (not (http/valid-feed-content-type? nil)))
    (is (not (http/valid-feed-content-type? "")))))

;; =============================================================================
;; url?
;; =============================================================================

(deftest url-predicate
  (testing "Detects http and https URLs"
    (is (http/url? "http://example.com/feed.xml"))
    (is (http/url? "https://example.com/feed.xml")))
  (testing "Rejects non-URLs"
    (is (not (http/url? "<feed>...</feed>")))
    (is (not (http/url? "{\"version\":\"...\"}")))
    (is (not (http/url? "ftp://example.com/")))
    (is (not (http/url? nil)))
    (is (not (http/url? 42)))))

;; =============================================================================
;; fetch-feed: happy path
;; =============================================================================

(deftest fetch-feed-success
  (testing "Fetches a feed and returns the body"
    (register! "/feed.xml"
               (fn [exchange _method]
                 (write-response exchange 200 "application/atom+xml" sample-atom-feed)))
    (let [result (http/fetch-feed (str *base-url* "/feed.xml"))]
      (is (:ok? result))
      (is (= 200 (:status result)))
      (is (= sample-atom-feed (:body result)))
      (is (= "application/atom+xml" (:content-type result)))
      (is (empty? (:errors result)))
      (is (empty? (:redirects result))))))

(deftest fetch-feed-json
  (testing "Fetches a JSON feed"
    (register! "/feed.json"
               (fn [exchange _method]
                 (write-response exchange 200 "application/feed+json" sample-json-feed)))
    (let [result (http/fetch-feed (str *base-url* "/feed.json"))]
      (is (:ok? result))
      (is (= sample-json-feed (:body result))))))

(deftest fetch-feed-content-type-with-params
  (testing "Accepts content-type with parameters like charset"
    (register! "/feed.xml"
               (fn [exchange _method]
                 (write-response exchange 200
                                 "application/atom+xml; charset=utf-8"
                                 sample-atom-feed)))
    (let [result (http/fetch-feed (str *base-url* "/feed.xml"))]
      (is (:ok? result)))))

;; =============================================================================
;; fetch-feed: errors
;; =============================================================================

(deftest fetch-feed-invalid-url
  (testing "Invalid URL returns :invalid-url error"
    (let [result (http/fetch-feed "not-a-url")]
      (is (not (:ok? result)))
      (is (some #(= :invalid-url (:code %)) (:errors result))))))

(deftest fetch-feed-non-http-scheme
  (testing "Non-http(s) scheme returns :invalid-url error"
    (let [result (http/fetch-feed "ftp://example.com/feed.xml")]
      (is (not (:ok? result)))
      (is (some #(= :invalid-url (:code %)) (:errors result))))))

(deftest fetch-feed-404
  (testing "404 returns :http-error"
    (let [result (http/fetch-feed (str *base-url* "/missing"))]
      (is (not (:ok? result)))
      (is (= 404 (:status result)))
      (is (some #(= :http-error (:code %)) (:errors result))))))

(deftest fetch-feed-server-500
  (testing "5xx returns :http-error"
    (register! "/broken"
               (fn [exchange _method]
                 (write-response exchange 500 "text/plain" "boom")))
    (let [result (http/fetch-feed (str *base-url* "/broken"))]
      (is (not (:ok? result)))
      (is (= 500 (:status result)))
      (is (some #(= :http-error (:code %)) (:errors result))))))

(deftest fetch-feed-invalid-content-type
  (testing "HTML response returns :invalid-content-type"
    (register! "/page.html"
               (fn [exchange _method]
                 (write-response exchange 200 "text/html" "<html></html>")))
    (let [result (http/fetch-feed (str *base-url* "/page.html"))]
      (is (not (:ok? result)))
      (is (some #(= :invalid-content-type (:code %)) (:errors result))))))

(deftest fetch-feed-content-type-validation-disabled
  (testing "Content-type check can be disabled"
    (register! "/raw"
               (fn [exchange _method]
                 (write-response exchange 200 "text/html" sample-atom-feed)))
    (let [result (http/fetch-feed (str *base-url* "/raw")
                                  {:validate-content-type? false})]
      (is (:ok? result))
      (is (= sample-atom-feed (:body result))))))

;; =============================================================================
;; fetch-feed: redirects
;; =============================================================================

(deftest fetch-feed-follows-redirects
  (testing "Follows 301 redirect to final URL"
    (register! "/old"
               (fn [exchange _method]
                 (write-response exchange 301 nil ""
                                 :headers {"Location" "/new"})))
    (register! "/new"
               (fn [exchange _method]
                 (write-response exchange 200 "application/atom+xml" sample-atom-feed)))
    (let [result (http/fetch-feed (str *base-url* "/old"))]
      (is (:ok? result))
      (is (= sample-atom-feed (:body result)))
      (is (= 1 (count (:redirects result))))
      (is (.endsWith ^String (first (:redirects result)) "/old")))))

(deftest fetch-feed-max-redirects-exceeded
  (testing "Redirect loop exceeds max-redirects"
    (register! "/loop1"
               (fn [exchange _method]
                 (write-response exchange 302 nil ""
                                 :headers {"Location" "/loop2"})))
    (register! "/loop2"
               (fn [exchange _method]
                 (write-response exchange 302 nil ""
                                 :headers {"Location" "/loop1"})))
    (let [result (http/fetch-feed (str *base-url* "/loop1") {:max-redirects 3})]
      (is (not (:ok? result)))
      (is (some #(= :max-redirects-exceeded (:code %)) (:errors result))))))

;; =============================================================================
;; fetch-feed: User-Agent header
;; =============================================================================

(deftest fetch-feed-sends-user-agent
  (testing "Sends the documented User-Agent header"
    (let [captured-ua (atom nil)]
      (register! "/ua"
                 (fn [exchange _method]
                   (reset! captured-ua
                           (-> exchange .getRequestHeaders (.getFirst "User-Agent")))
                   (write-response exchange 200 "application/atom+xml" sample-atom-feed)))
      (http/fetch-feed (str *base-url* "/ua"))
      (is (= http/user-agent @captured-ua)))))

;; =============================================================================
;; fetch-and-validate
;; =============================================================================

(deftest fetch-and-validate-success
  (testing "Fetches and validates a real feed"
    (register! "/feed.xml"
               (fn [exchange _method]
                 (write-response exchange 200 "application/atom+xml" sample-atom-feed)))
    (let [result (http/fetch-and-validate (str *base-url* "/feed.xml"))]
      (is (:valid? result))
      (is (contains? result :http))
      (is (= 200 (get-in result [:http :status]))))))

(deftest fetch-and-validate-network-error
  (testing "Network errors propagate as validation errors"
    (let [result (http/fetch-and-validate (str *base-url* "/missing"))]
      (is (not (:valid? result)))
      (is (some #(= :http-error (:code %)) (:errors result)))
      (is (contains? result :http)))))

;; =============================================================================
;; core/validate-feed URL integration
;; =============================================================================

(deftest core-validate-feed-with-url
  (testing "core/validate-feed auto-fetches URL strings"
    (register! "/feed.xml"
               (fn [exchange _method]
                 (write-response exchange 200 "application/atom+xml" sample-atom-feed)))
    (let [result (v/validate-feed (str *base-url* "/feed.xml"))]
      (is (:valid? result))
      (is (contains? result :http)))))

(deftest core-validate-feed-fetch-disabled
  (testing "When :fetch? false, URL string is NOT auto-fetched"
    ;; With :fetch? false the URL string is treated as raw content. It is not
    ;; valid XML/JSON, so validation returns an :invalid-xml error -- and
    ;; crucially carries NO :http key, proving no network call was made (the
    ;; registered handler must never run).
    (register! "/should-not-be-called"
               (fn [exchange _method]
                 (write-response exchange 200 "application/atom+xml" sample-atom-feed)))
    (let [result (v/validate-feed (str *base-url* "/should-not-be-called")
                                  {:fetch? false})]
      (is (not (:valid? result)))
      (is (some #(= :invalid-xml (:code %)) (:errors result))
          "URL string with :fetch? false is parsed as raw content, not fetched")
      (is (not (contains? result :http))
          "No HTTP metadata: the network was never contacted"))))

(deftest core-validate-feed-string-unchanged
  (testing "Raw XML string still validates without HTTP (backwards compatible)"
    (let [result (v/validate-feed sample-atom-feed)]
      (is (:valid? result))
      (is (not (contains? result :http))))))
