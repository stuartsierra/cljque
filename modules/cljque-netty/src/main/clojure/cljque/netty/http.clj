(ns cljque.netty.http
  (:use cljque.netty.util))

(import-netty)

(defn default-http-pipeline
  "Returns a simple ChannelPipeline which encodes/decodes HTTP
  requests and responses. Automatically aggregates chunked HTTP
  requests, which may have a maximum size of 10 MB. Compression is
  handled automatically."
  []
  (pipeline
   "http-decode" (HttpRequestDecoder.)
   "http-decompress" (HttpContentDecompressor.)
   "http-aggregate" (HttpChunkAggregator. 10485760)
   "http-encode" (HttpResponseEncoder.)
   "http-compress" (HttpContentCompressor.)
   "netty-log" (LoggingHandler.)))

