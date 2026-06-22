(ns atom-validator.http
  "HTTP feed fetching with redirect handling and content-type validation.

  Uses java.net.http (built into JDK 11+) - no extra dependencies.

  Public API:
  - (fetch-feed url)         => {:ok? bool :body str :url str :content-type str :status int :errors [...]}
  - (fetch-and-validate url) => validation result (delegates to core/validate-feed)

  Errors emitted (all :type :error):
  - :http-error             - network failure / non-2xx status
  - :http-timeout           - request timeout
  - :invalid-content-type   - response Content-Type is not a known feed MIME type
  - :max-redirects-exceeded - redirect chain longer than max-redirects
  - :invalid-url            - URL could not be parsed"
  (:require [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient
                          HttpClient$Redirect
                          HttpClient$Version
                          HttpRequest
                          HttpRequest$BodyPublishers
                          HttpResponse
                          HttpResponse$BodyHandlers
                          HttpTimeoutException]
           [java.time Duration]
           [java.io IOException]
           [java.util.concurrent Executors ThreadFactory]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:const user-agent
  "User-Agent header sent with every request."
  "atom-validator/0.2.0 (+https://github.com/aygp-dr/atom-validator)")

(def ^:const default-timeout-seconds
  "Default request timeout in seconds."
  30)

(def ^:const default-max-redirects
  "Default maximum number of redirects to follow."
  5)

(def feed-content-types
  "Set of acceptable Content-Type values (case-insensitive, ignoring parameters)
  for feed responses."
  #{"application/atom+xml"
    "application/rss+xml"
    "application/feed+json"
    "application/xml"
    "text/xml"
    "application/json"
    "text/json"})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- parse-uri
  "Parse a URI string. Returns nil if invalid."
  [url]
  (try
    (let [uri (URI. url)]
      (when (and (.getScheme uri)
                 (#{"http" "https"} (str/lower-case (.getScheme uri)))
                 (.getHost uri))
        uri))
    (catch Exception _ nil)))

(defn- normalize-content-type
  "Strip parameters and lowercase a Content-Type header.
  e.g. 'application/atom+xml; charset=utf-8' => 'application/atom+xml'"
  [content-type]
  (when content-type
    (-> content-type
        (str/split #";")
        first
        str/trim
        str/lower-case)))

(defn valid-feed-content-type?
  "Returns true if content-type indicates a feed format."
  [content-type]
  (boolean
   (when-let [normalized (normalize-content-type content-type)]
     (contains? feed-content-types normalized))))

(def ^:private daemon-executor
  "Cached thread pool whose threads are daemons. By default
  java.net.http.HttpClient spawns its selector/worker threads as NON-daemon
  threads, which keep the JVM alive after the last request -- the JVM only
  exits once all non-daemon threads die. Under `clj -X:test` the cognitect
  runner returns without calling System/exit, so those lingering threads hang
  the process (CI was cancelled at the 10-minute cap). Supplying an executor
  with daemon threads makes the client's threads non-blocking for JVM exit."
  (delay
    (Executors/newCachedThreadPool
     (reify ThreadFactory
       (newThread [_ runnable]
         (doto (Thread. runnable "atom-validator-http")
           (.setDaemon true)))))))

(def ^:private shared-client
  "Single shared HttpClient reused across all requests.

  Two reasons for one shared instance built on a daemon executor:
  - Creating a client per request leaks selector/worker threads; sharing one
    keeps the thread count bounded.
  - The daemon executor (see daemon-executor) ensures those threads never
    block JVM exit, so callers under `clj -X:test` or a plain script don't hang.

  Automatic redirect handling is disabled so we can count redirects manually
  and enforce max-redirects."
  (delay
    (-> (HttpClient/newBuilder)
        (.followRedirects HttpClient$Redirect/NEVER)
        (.connectTimeout (Duration/ofSeconds default-timeout-seconds))
        (.version HttpClient$Version/HTTP_2)
        (.executor @daemon-executor)
        (.build))))

(defn- build-client
  "Return the shared HttpClient instance."
  []
  @shared-client)

(defn- build-request
  "Build an HttpRequest for the given URI and method (:get or :head)."
  [^URI uri method timeout-seconds]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri uri)
                    (.timeout (Duration/ofSeconds timeout-seconds))
                    (.header "User-Agent" user-agent)
                    (.header "Accept"
                             (str "application/atom+xml,"
                                  "application/rss+xml,"
                                  "application/feed+json,"
                                  "application/xml;q=0.9,"
                                  "application/json;q=0.9,"
                                  "text/xml;q=0.8,*/*;q=0.5")))]
    (case method
      :head (-> builder (.method "HEAD" (HttpRequest$BodyPublishers/noBody)) (.build))
      :get  (-> builder (.GET) (.build)))))

(defn- response-header
  "Get a single response header value (case-insensitive)."
  [^HttpResponse response header-name]
  (-> response .headers (.firstValue header-name) (.orElse nil)))

(defn- redirect-status?
  "Is the HTTP status a redirect?"
  [status]
  (#{301 302 303 307 308} status))

(defn- resolve-redirect
  "Resolve a (possibly relative) Location header against the base URI.
  Returns a URI or nil."
  [^URI base location]
  (when location
    (try
      (.resolve base (URI. location))
      (catch Exception _ nil))))

(defn- error
  "Construct an error map."
  [code message]
  {:type :error :code code :message message})

;; =============================================================================
;; Public API
;; =============================================================================

(defn fetch-feed
  "Fetch a feed from a URL. Follows redirects (up to max-redirects).

  Options:
  - :timeout-seconds   - request timeout (default 30)
  - :max-redirects     - max number of redirects to follow (default 5)
  - :validate-content-type? - whether to enforce feed content-types (default true)
  - :head-check?       - issue a HEAD request first to validate content-type
                         before downloading the body (default false)

  Returns a map:
  {:ok?           boolean
   :url           str   ; final URL (after redirects)
   :status        int   ; HTTP status of final response (when network reached)
   :content-type  str   ; final Content-Type
   :body          str   ; response body (only when :ok? is true)
   :redirects     [str] ; chain of URLs visited (excluding final)
   :errors        [{:type :error :code kw :message str}]}"
  ([url] (fetch-feed url {}))
  ([url {:keys [timeout-seconds max-redirects validate-content-type? head-check?]
         :or   {timeout-seconds default-timeout-seconds
                max-redirects default-max-redirects
                validate-content-type? true
                head-check? false}}]
   (if-let [initial-uri (parse-uri url)]
     (let [client (build-client)]
       (loop [^URI uri initial-uri
              redirects []
              hop 0]
         (cond
           (> hop max-redirects)
           {:ok? false
            :url (str uri)
            :redirects redirects
            :errors [(error :max-redirects-exceeded
                            (format "Exceeded maximum redirects (%d) starting from %s"
                                    max-redirects url))]}

           :else
           (let [send-result
                 (try
                   (let [head-resp
                         (when head-check?
                           (.send client
                                  (build-request uri :head timeout-seconds)
                                  (HttpResponse$BodyHandlers/discarding)))]
                     (if (and head-resp
                              (redirect-status? (.statusCode head-resp)))
                       {:type :redirect
                        :status (.statusCode head-resp)
                        :location (response-header head-resp "Location")}
                       (let [resp (.send client
                                         (build-request uri :get timeout-seconds)
                                         (HttpResponse$BodyHandlers/ofString))]
                         {:type :response
                          :head head-resp
                          :response resp})))
                   (catch HttpTimeoutException e
                     {:type :error
                      :error (error :http-timeout
                                    (format "Timeout fetching %s: %s"
                                            (str uri) (.getMessage e)))})
                   (catch IOException e
                     {:type :error
                      :error (error :http-error
                                    (format "Network error fetching %s: %s"
                                            (str uri) (.getMessage e)))})
                   (catch Exception e
                     {:type :error
                      :error (error :http-error
                                    (format "Error fetching %s: %s"
                                            (str uri) (.getMessage e)))}))]
             (case (:type send-result)
               :error
               {:ok? false
                :url (str uri)
                :redirects redirects
                :errors [(:error send-result)]}

               :redirect
               (let [next-uri (resolve-redirect uri (:location send-result))]
                 (if next-uri
                   (recur next-uri (conj redirects (str uri)) (inc hop))
                   {:ok? false
                    :url (str uri)
                    :redirects redirects
                    :errors [(error :http-error
                                    (format "Redirect from %s missing/invalid Location header"
                                            (str uri)))]}))

               :response
               (let [^HttpResponse resp (:response send-result)
                     status (.statusCode resp)]
                 (cond
                   (redirect-status? status)
                   (let [next-uri (resolve-redirect uri (response-header resp "Location"))]
                     (if next-uri
                       (recur next-uri (conj redirects (str uri)) (inc hop))
                       {:ok? false
                        :url (str uri)
                        :status status
                        :redirects redirects
                        :errors [(error :http-error
                                        (format "Redirect from %s missing/invalid Location header"
                                                (str uri)))]}))

                   (not (<= 200 status 299))
                   {:ok? false
                    :url (str uri)
                    :status status
                    :redirects redirects
                    :content-type (response-header resp "Content-Type")
                    :errors [(error :http-error
                                    (format "HTTP %d fetching %s" status (str uri)))]}

                   :else
                   (let [content-type (response-header resp "Content-Type")
                         body (.body resp)]
                     (if (and validate-content-type?
                              (not (valid-feed-content-type? content-type)))
                       {:ok? false
                        :url (str uri)
                        :status status
                        :content-type content-type
                        :redirects redirects
                        :body body
                        :errors [(error :invalid-content-type
                                        (format "Unexpected Content-Type '%s' for %s (expected feed type)"
                                                content-type (str uri)))]}
                       {:ok? true
                        :url (str uri)
                        :status status
                        :content-type content-type
                        :redirects redirects
                        :body body
                        :errors []})))))))))
     {:ok? false
      :url url
      :redirects []
      :errors [(error :invalid-url
                      (format "Cannot parse URL '%s' (must be absolute http/https)" url))]})))

(defn url?
  "Returns true if s looks like an http/https URL."
  [s]
  (and (string? s)
       (or (str/starts-with? s "http://")
           (str/starts-with? s "https://"))))

(defn fetch-and-validate
  "Convenience wrapper: fetch a feed by URL, then validate it.

  If the HTTP fetch fails, returns:
    {:valid? false
     :errors [...http-errors...]
     :warnings []
     :http {:url ... :status ... :redirects [...]}}

  On success, delegates to atom-validator.core/validate-feed and appends the
  :http metadata to the result.

  Options are forwarded to both fetch-feed and validate-feed."
  ([url] (fetch-and-validate url {}))
  ([url opts]
   ;; Require core lazily to avoid circular load if core requires us.
   (require 'atom-validator.core)
   (let [fetch-result (fetch-feed url opts)
         http-meta {:url (:url fetch-result)
                    :status (:status fetch-result)
                    :content-type (:content-type fetch-result)
                    :redirects (:redirects fetch-result)}]
     (if (:ok? fetch-result)
       (let [validate-fn (resolve 'atom-validator.core/validate-feed)
             result (validate-fn (:body fetch-result) opts)]
         (assoc result :http http-meta))
       {:valid? false
        :errors (:errors fetch-result)
        :warnings []
        :http http-meta}))))
