(ns cljque.netty.events
  (:use cljque.api
	cljque.netty.util))

(import-netty)

;;; Observables and Netty channel events

(defn observed-event [observable subscribers-map netty-event]
  (if (instance? ExceptionEvent netty-event)
    (doseq [subscriber (vals subscribers-map)]
      (error subscriber observable (.getCause netty-event)))
    (doseq [subscriber (vals subscribers-map)]
      (event subscriber observable netty-event))))

(defn add-observable-handler [pipeline observable subscribers]
  (add-to-pipeline
   pipeline
   "observable" (channel-upstream-handler
		 (fn [context event]
		   (observed-event observable
				   @subscribers
				   event)))))

;;; Observable servers

(defrecord ObservableNioServer
  [bootstrap channel-group server-channel subscribers]
  Observable
  (subscribe [this observer]
	     (let [key (Object.)]
	       (swap! subscribers assoc key observer)
	       (fn [] (swap! subscribers dissoc key)))))

(defn create-observable-nio-server [port pipeline-factory-fn]
  (let [bootstrap (nio-server-bootstrap)
	channel-group (DefaultChannelGroup.)
	subscribers (atom {})
	observable (ObservableNioServer. bootstrap channel-group nil
					 subscribers)]
    (set-channel-pipeline-factory
     bootstrap
     #(-> (pipeline-factory-fn)
	  (add-channel-group-handler channel-group)
	  (add-observable-handler observable subscribers)))
    (let [server-channel (bind bootstrap port)]
      (.add channel-group server-channel)
      (assoc observable :server-channel server-channel))))

;;; MessageTarget channels

(extend-protocol MessageTarget
  Channel
  (send! [target message] (Channels/write target message)))

;;; Observable futures

(extend-protocol Observable
  ChannelFuture
  (subscribe [channel-future observer]
	     (let [listener (channel-future-listener #(done observer %))]
	       (.addListener channel-future listener)
	       (fn [] (.removeListener channel-future listener)))))
