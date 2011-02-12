(ns cljque.netty.events
  (:use cljque.api
	cljque.combinators
        cljque.netty.util))

(import-netty)

;;; Observables and Netty channel events

(defn observer-channel-handler
  "Returns a ChannelUpstreamHandler that generates events on the given
  Observer. ExceptionEvents will trigger `error` on the Observer; all
  other events will trigger `event`. The `done` method will never be
  triggered. The Observable will be the Channel itself." 
  [observer]
  (channel-upstream-handler
   (fn [context channel-event]
     (if (instance? ExceptionEvent channel-event)
       (on-error observer (.getChannel channel-event) (.getCause channel-event))
       (on-event observer (.getChannel channel-event) channel-event))
     (.sendUpstream context channel-event))))

(defn messages
  "Returns an Observable which filters MessageEvents from the
  Observable and extracts the message object as the event."
  [observable-channel]
  (map-events #(.getMessage %)
	      (filter-events #(instance? MessageEvent %)
			     observable-channel)))

(def channel-state-keyword
  (strict-map-fn {ChannelState/OPEN :open?
                  ChannelState/BOUND :bound?
                  ChannelState/CONNECTED :connected?
                  ChannelState/INTEREST_OPS :interest-ops}))

(defn channel-state-pairs
  "Returns an Observable which filters ChannelStateEvents from the
  Observable and transforms them into [keyword value] pairs where the
  keyword is one of `:open?`, `:bound?`, `:connected?`, or
  `:interest-ops`."
  [observable-channel]
  (map-events (fn [channel-event]
		(let [^ChannelStateEvent evnt channel-event]
		  [(channel-state-keyword (.getState evnt))
		   (.getValue evnt)]))
	      (filter-events #(instance? ChannelStateEvent %)
			     observable-channel)))

;;; Message targets

(extend-protocol MessageTarget
  Channel
  (send! [channel message] (Channels/write channel message))
  ChannelFuture
  (send! [channel-future message]
	 (add-channel-future-listener
	  channel-future
	  (fn [cf] (send! (.getChannel cf) message)))))

;;; Observable channels & futures

(extend-protocol Observable
  Channel
  ;; Subscribing to a Channel triggers `event` and `error` as by
  ;; observer-channel-handler and additionally triggers `done` when
  ;; the Channel is closed.
  (subscribe [channel observer]
	     (let [key (name (gensym "channel-observer"))
		   handler (observer-channel-handler observer)
		   pipeline (.getPipeline channel)
		   listener (channel-future-listener (fn [_] (on-done observer channel)))]
	       (.addLast pipeline key handler)
	       (.addListener (.getCloseFuture channel) listener)
	       (fn [] (.remove pipeline key)
		 (.removeListener listener))))
  ;; Subscribing to a ChannelPipeline triggers `event` and `error` as
  ;; by observer-channel-handler.
  ChannelPipeline
  (subscribe [pipeline observer]
	     (let [key (name (gensym "pipeline-observer"))
		   handler (observer-channel-handler observer)]
	       (.addLast pipeline key handler)
	       (fn [] (.remove pipeline key))))
  ;; Subscribing to a ChannelFuture triggers `done` when the
  ;; ChannelFuture completes.
  ChannelFuture
  (subscribe [channel-future observer]
             (let [listener (channel-future-listener
			     (fn [channel-future]
			       (if (.isSuccess channel-future)
				 (on-done observer channel-future)
				 (on-error observer channel-future (.getCause channel-future)))))]
               (.addListener channel-future listener)
               (fn [] (.removeListener channel-future listener)))))
