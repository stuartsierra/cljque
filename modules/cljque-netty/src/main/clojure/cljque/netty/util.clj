(ns cljque.netty.util
  "Convenience functions for working with Netty from Clojure."
  (:use [clojure.java.io :only (reader resource)])
  (:import (java.net InetSocketAddress)))

;;; Class imports

(defmacro import-netty
  "Import all public Netty class names into the current namespace,
  except those that have external dependencies beyond Netty itself."
  []
  (with-open [r (reader (resource "cljque/netty-classes.txt"))]
    (list* 'clojure.core/import
	   (doall (map symbol (line-seq r))))))

(import-netty)

;;; Channel handlers

(defn channel-upstream-handler
  "Returns a ChannelUpstreamHandler which invokes f on each upstream
  event. f takes two arguments: the ChannelHandlerContext and the
  ChannelEvent."
  [f]
  (reify ChannelUpstreamHandler
	 (handleUpstream [this channel-handler-context channel-event]
			 (f channel-handler-context channel-event))))

(defn channel-downstream-handler
  "Returns a ChannelDownstreamHandler which invokes f on each
  downstream event. f takes two arguments: the ChannelHandlerContext
  and the ChannelEvent."
  [f]
  (reify ChannelDownstreamHandler
	 (handleDownstream [this channel-handler-context channel-event]
			   (f channel-handler-context channel-event))))

(defn encoder
  "Returns a ChannelDownstreamHandler which invokes f on each message
  and forwards the result to the next handler."
  [f]
  (channel-downstream-handler
   (fn [channel-handler-context channel-event]
     (if (instance? MessageEvent channel-event)
       (Channels/write channel-handler-context
		       (.getFuture channel-event)
		       (f (.getMessage channel-event))
		       (.getRemoteAddress channel-event))
       (.sendDownstream channel-handler-context channel-event)))))

(defn decoder
  "Returns a ChannelUpstreamHandler which invokes f on each message
  and forwards the result to the next handler. If f returns nil, no
  message is forwarded."
  [f]
  (channel-upstream-handler
   (fn [channel-handler-context channel-event]
     (if (instance? MessageEvent channel-event)
       (when-let [decoded-message (f (.getMessage channel-event))]
	 (Channels/fireMessageReceived channel-handler-context
				       decoded-message
				       (.getRemoteAddress channel-event)))
       (.sendUpstream channel-handler-context channel-event)))))

;;; Channel pipelines

(defn channel-pipeline-factory [f]
  (reify ChannelPipelineFactory
	 (getPipeline [this] (f))))

(defn add-to-pipeline
  "Adds handlers to the end of a ChannelPipeline. names-handlers are
  pairs of a handler name (String) and a ChannelHandler instance; they
  will be added to the pipeline in order."
  [pipeline & names-handlers]
  (doseq [[name handler] (partition 2 names-handlers)]
    (.addLast pipeline name handler))
  pipeline)

(defn pipeline
  "Creates a new ChannelPipeline. names-handlers are pairs of a
  handler name (String) and a ChannelHandler instance; they will be
  added to the pipeline in order. With no arguments returns an empty
  pipeline."
  ([] (Channels/pipeline))
  ([& names-handlers]
     (apply add-to-pipeline (Channels/pipeline) names-handlers)))

(defn simple-clojure-pipeline
  "Returns a simple ChannelPipeline which encodes/decodes Clojure data
  structures as UTF-8 strings, delimited by newlines. A message may
  have a maximum length of 1 MB, and may not be nil."
  []
  (pipeline
   "frame" (DelimiterBasedFrameDecoder. 1048576 (Delimiters/lineDelimiter))
   "string-decode" (StringDecoder. CharsetUtil/UTF_8)
   "string-encode" (StringEncoder. CharsetUtil/UTF_8)
   "clojure-decode" (decoder read-string)
   "clojure-encode" (encoder prn-str)
   "log" (LoggingHandler.)))

;;; Futures and listeners

(defn channel-future-listener
  "Returns a ChannelFutureListener which invokes f on the
  ChannelFuture when it completes."
  [f]
  (reify ChannelFutureListener
	 (operationComplete [this channel-future] (f channel-future))))

(defn add-channel-future-listener
  "Adds a ChannelFutureListener to the ChannelFuture which invokes f
  when it completes."
  [channel-future f]
  (.addListener channel-future (channel-future-listener f)))

(defn channel-group-future-listener
  "Returns a ChannelGroupFutureListener which invokes f on the
  ChannelGroupFuture when it completes."
  [f]
  (reify ChannelGroupFutureListener
	 (operationComplete [this channel-group-future]
			    (f channel-group-future))))

(defn add-channel-group-future-listener
  "Adds a ChannelGroupFutureListener to the channel-group-future which
  invokes f when it completes."
  [channel-group-future f]
  (.addListener channel-group-future (channel-group-future-listener f)))

;;; Server and client bootstrap

(defn virtual-executor-service
  "Returns a Netty VirtualExecutorService wrapping Clojure's send-off
  Agent Executor."
  []
  (VirtualExecutorService. clojure.lang.Agent/soloExecutor))

(defn nio-server-bootstrap
  "Returns a new ServerBootstrap using NIO server sockets and cached
  thread pools."
  []
  (ServerBootstrap.
   (NioServerSocketChannelFactory.
    (virtual-executor-service)
    (virtual-executor-service))))

(defn nio-client-bootstrap
  "Returns a new ClientBootstrap using NIO sockets and cached thread
  pools."
  []
  (ClientBootstrap.
   (NioClientSocketChannelFactory.
    (virtual-executor-service)
    (virtual-executor-service))))

(defn set-channel-pipeline-factory
  "Sets the channel pipeline factory of bootstrap to a
  ChannelPipelineFactory which invokes f (without arguments)."
  [bootstrap f]
  (.setPipelineFactory bootstrap (channel-pipeline-factory f)))

(defn bind
  "Binds a server bootstrap to an integer port."
  [bootstrap port]
  (.bind bootstrap (InetSocketAddress. port)))

(defn connect
  "Connects a client bootstrap to a host (String) and port (Integer)."
  [bootstrap host port]
  (.connect bootstrap (InetSocketAddress. host port)))

(defn channel-group-handler
  "Returns a ChannelUpstreamHandler which adds a newly-opened Channel
  to the given ChannelGroup and then removes itself from the
  ChannelPipeline."
  [channel-group]
  (channel-upstream-handler
   (fn [context event]
     (when (and (instance? ChannelStateEvent event)
		(= ChannelState/OPEN (.getState event))
		(.getValue event))
       (let [channel (.getChannel event)]
	 (.add channel-group (.getChannel event))
	 (.remove (.getPipeline channel) "cljque.netty.util/channel-group-handler")))
     (.sendUpstream context event))))

(defn add-channel-group-handler
  "Adds a handler to the head of the pipeline which adds any
  newly-opened channel to the channel-group."
  [pipeline channel-group]
  (doto pipeline
    (.addFirst "cljque.netty.util/channel-group-handler"
	       (channel-group-handler channel-group))))

(defn create-nio-server
  "Creates, starts, and returns an NIO socket server in one step.
  The server will be listening for connections on port, responding
  using the pipeline returned by pipeline-factory-fn.

  Returns a map containing the ServerBootstrap object, the server
  Channel, and the ChannelGroup. Inserts a handler into the pipeline
  which automatically adds new channels to the ChannelGroup as they
  are opened."
  [port pipeline-factory-fn]
  (let [bootstrap (nio-server-bootstrap)
	channel-group (DefaultChannelGroup.)]
    (set-channel-pipeline-factory
     bootstrap
     #(add-channel-group-handler (pipeline-factory-fn) channel-group))
    (let [server-channel (bind bootstrap port)]
      (.add channel-group server-channel)
      {:bootstrap bootstrap
       :server-channel server-channel
       :channel-group channel-group})))

(defn create-nio-client
  "Creates, starts, and returns an NIO client in one step.
  The client will attempt to connect to host (String) and
  port (Integer), using the pipeline returned by pipeline-factory-fn."
  [host port pipeline-factory-fn]
  (let [bootstrap (nio-client-bootstrap)]
    (set-channel-pipeline-factory bootstrap pipeline-factory-fn)
    (let [client-channel (connect bootstrap host port)]
      {:bootstrap bootstrap
       :channel client-channel})))

(defn shutdown-server
  "Synchronously shuts down a server, given the map returned by
  create-nio-server."
  [server-state]
  (let [{:keys [bootstrap channel-group]} server-state]
    (.. channel-group close awaitUninterruptibly)
    (.releaseExternalResources bootstrap)))

(defn shutdown-client
  "Synchronously shuts down a client, given the map returned by
  create-nio-client."
  [client-state]
  (let [{:keys [bootstrap channel]} client-state]
    (.. channel close awaitUninterruptibly)
    (.releaseExternalResources bootstrap)))
