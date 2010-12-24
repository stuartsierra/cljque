(ns cljque.netty.events
  (:use cljque.api
        cljque.netty.util))

(import-netty)

;;; Observables and Netty channel events

(defn observer-channel-handler [observer]
  (channel-upstream-handler
   (fn [context netty-event]
     (if (instance? ExceptionEvent netty-event)
       (error observer context (.getCause netty-event))
       (do (event observer context netty-event)
	   (when (and (instance? ChannelStateEvent netty-event)
		      (= ChannelState/OPEN (.getState netty-event))
		      (false? (.getValue netty-event)))
	     (done observer context)))))))

(defn add-observer-channel-handler [pipeline observer]
  (add-to-pipeline
   pipeline
   "observer" (observer-channel-handler observer)))

(defn stateful-pipeline-factory [init-fn handler-fn pipeline-factory-fn]
  (channel-pipeline-factory
   #(let [state (init-fn)]
      (add-to-pipeline
       (pipeline-factory-fn)
       "state-handler" (channel-upstream-handler
			(partial handler-fn state))))))

;;; Message targets

(extend-protocol MessageTarget
  Channel
  (send! [this message] (Channels/write this message))
  ChannelHandlerContext
  (send! [this message] (Channels/write (.getChannel this) message)))

;;; Observable futures

(extend-protocol Observable
  ChannelFuture
  (subscribe [channel-future observer]
             (let [listener (channel-future-listener #(done observer %))]
               (.addListener channel-future listener)
               (fn [] (.removeListener channel-future listener)))))
