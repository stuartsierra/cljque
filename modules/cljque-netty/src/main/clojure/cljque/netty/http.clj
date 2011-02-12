(ns cljque.netty.http
  (:use cljque.netty.util))

(import-netty)

(defn headers-map [^HttpMessage http-message]
  (reduce (fn [m header-name]
	    (let [values (.getHeaders http-message header-name)]
	      (assoc m header-name
		     (if (< 1 (count values))
		       (seq values)
		       (first values)))))
	  {} (.getHeaderNames http-message)))

(defn response-map [^HttpResponse r]
  {:status (.getCode (.getStatus r))
   :headers (headers-map r)
   :body (.getContent r)})

(defn request-map [^HttpRequest r]
  {:uri (.getUri r)
   :method (-> r .getMethod .getName .toLowerCase keyword)
   :headers (headers-map r)
   :body (.getContent r)})

(def netty-http-method
  (strict-map-fn {:get HttpMethod/GET
                  :post HttpMethod/POST
                  :put HttpMethod/PUT
                  :delete HttpMethod/DELETE
                  :head HttpMethod/HEAD
                  :options HttpMethod/OPTIONS
                  :patch HttpMethod/PATCH
                  :trace HttpMethod/TRACE
                  :connect HttpMethod/CONNECT}))

(def netty-protocol-version
  (strict-map-fn {"1.1" HttpVersion/HTTP_1_1
                  "1.0" HttpVersion/HTTP_1_0}))

(defn set-netty-headers [^HttpMessage m headers]
  (doseq [[header-name value] headers]
    (.setHeader m header-name value)))

(defn set-netty-body [^HttpMessage m body options]
  {:pre [(or (nil? body) (string? body))]}
  (.setContent m (channel-buffer body options)))

(defn netty-http-request [options]
  (let [{:keys [method protocol-version uri headers body]
	 :or {protocol-version "1.1"
	      method :get
	      uri "/"
	      headers {}
	      body nil}} options]
    (doto (DefaultHttpRequest.
	    (netty-protocol-version protocol-version)
	    (netty-http-method method)
	    uri)
      (set-netty-headers headers)
      (set-netty-body body options))))

(defn netty-http-response [options]
  (let [{:keys [status protocol-version headers body]
	 :or {protocol-version "1.1"
	      method :get
	      status 200
	      headers {}
	      body nil}} options]
    (doto (DefaultHttpResponse.
	    (netty-protocol-version protocol-version)
	    (HttpResponseStatus/valueOf status))
      (set-netty-headers headers)
      (set-netty-body body))))

(defn http-server-pipeline
  "Returns a simple ChannelPipeline for use in an HTTP
  server. Encodes/decodes HTTP requests and responses to/from Clojure
  maps. Automatically aggregates chunked HTTP requests, which may have
  a maximum size of 10 MB. Compression is handled automatically."
  []
  (pipeline
   "http-codec" (HttpServerCodec.)
   "http-aggregate" (HttpChunkAggregator. 10485760)
   "http-compress" (HttpContentCompressor.)
   "request-map" (decoder request-map)
   "netty-http-response" (encoder netty-http-response)
   "netty-log" (LoggingHandler.)))

(defn http-client-pipeline
  "Returns a simple ChannelPipeline for use in an HTTP
  client. Encodes/decodes HTTP requests and responses to/from Clojure
  maps. Automatically aggregates chunked HTTP responses, which may
  have a maximum size of 10 MB. Compression is handled automatically."
  []
  (pipeline
   "http-codec" (HttpClientCodec.)
   "http-decompress" (HttpContentDecompressor.)
   "http-aggregate" (HttpChunkAggregator. 10485760)
   "netty-http-request" (encoder netty-http-request)
   "response-map" (decoder response-map)
   "netty-log" (LoggingHandler.)))

(def content-type-string
  (strict-map-fn
   {:css "text/css"
    :csv "text/csv"
    :html "text/html"
    :text "text/plain"

    :gif "image/gif"
    :jpeg "image/jpeg"
    :png "image/png"
    :svg "image/svg+xml"
    :tiff "image/tiff"

    :form "application/x-www-form-urlencoded"
    :multipart-form "multipart/form-data"

    :javascript "application/javascript"
    :json "application/json"
    :pdf "application/pdf"
    :postscript "application/postscript"
    :soap "application/soap+xml"
    :xhtml "application/xhtml+xml"
    :xml "application/xml"
    :zip "application/zip"}))

(defn add-content-type
  ([response-map content-type]
     (add-content-type content-type "UTF-8"))
  ([response-map content-type charset]
     (assoc response-map
       :content-type (if (keyword? content-type)
		       (content-type-string content-type)
		       content-type)
       :charset charset)))
