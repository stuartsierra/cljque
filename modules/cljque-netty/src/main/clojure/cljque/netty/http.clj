(ns cljque.netty.http
  (:use cljque.netty.util))

(import-netty)

(defn http-server-pipeline
  "Returns a simple ChannelPipeline for use in an HTTP
  server. Encodes/decodes HTTP requests and responses. Automatically
  aggregates chunked HTTP requests, which may have a maximum size of
  10 MB. Compression is handled automatically."
  []
  (pipeline
   "http-codec" (HttpServerCodec.)
   "http-aggregate" (HttpChunkAggregator. 10485760)
   "http-compress" (HttpContentCompressor.)
   "netty-log" (LoggingHandler.)))

(defn http-client-pipeline
  "Returns a simple ChannelPipeline for use in an HTTP
  client. Encodes/decodes HTTP requests and responses. Automatically
  aggregates chunked HTTP responses, which may have a maximum size of
  10 MB. Compression is handled automatically."
  []
  (pipeline
   "http-codec" (HttpClientCodec.)
   "http-decompress" (HttpContentDecompressor.)
   "http-aggregate" (HttpChunkAggregator. 10485760)
   "netty-log" (LoggingHandler.)))

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

(def netty-http-method
     {:get HttpMethod/GET
      :post HttpMethod/POST
      :put HttpMethod/PUT
      :delete HttpMethod/DELETE
      :head HttpMethod/HEAD
      :options HttpMethod/OPTIONS
      :patch HttpMethod/PATCH
      :trace HttpMethod/TRACE
      :connect HttpMethod/CONNECT})

(def netty-protocol-version
     {"1.1" HttpVersion/HTTP_1_1
      "1.0" HttpVersion/HTTP_1_0})

(defn set-netty-headers [^HttpMessage m headers]
  (doseq [[header-name value] headers]
    (.setHeader m header-name value)))

(defn set-netty-body [^HttpMessage m body]
  {:pre [(or (nil? body) (string? body))]}
  (when body
    (.setContent m (ChannelBuffers/copiedBuffer body CharsetUtil/UTF_8))))

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
      (set-netty-body body))))
