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