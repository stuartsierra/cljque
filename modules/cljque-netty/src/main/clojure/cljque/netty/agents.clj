(ns cljque.netty.agents
  (:use cljque.api
        cljque.netty.util))

(import-netty)

(defn channel-event-map [^ChannelEvent netty-event]
  (assoc (condp instance? netty-event
	   MessageEvent
	   {:event-type :message
	    :message (.getMessage ^MessageEvent netty-event)}

	   WriteCompletionEvent
	   {:event-type :write-complete
	    :written-amount (.getWrittenAmount
			     ^WriteCompletionEvent netty-event)}

	   ChildChannelStateEvent
	   (let [child (.getChildChannel ^ChildChannelStateEvent netty-event)]
	     (if (.isOpen child)
	       {:event-type :child-channel-open
		:child-channel child}
	       {:event-type :child-channel-closed
		:child-channel child}))

	   ChannelStateEvent
	   (let [evnt ^ChannelStateEvent netty-event]
	     (condp = (.getState evnt)
		 ChannelState/OPEN (if (.getValue evnt)
				     {:event-type :open}
				     {:event-type :closed})
		 ChannelState/BOUND (if (.getValue evnt)
				      {:event-type :bound}
				      {:event-type :unbound})
		 ChannelState/CONNECTED (if (.getValue evnt)
					  {:event-type :connected}
					  {:event-type :disconnected})
		 ChannelState/INTEREST_OPS {:event-type :interest-changed}))

	   ExceptionEvent
	   {:event-type :exception-caught
	    :cause (.getCause ^ExceptionEvent netty-event)}

	   IdleStateEvent
	   (let [^IdleStateEvent evnt netty-event]
	     {:event-type :idle
	      :last-activity-time (.getLastActivityTime evnt)
	      :state (condp = (.getState evnt)
			 IdleState/ALL_IDLE :all-idle
			 IdleState/READER_IDLE :reader-idle
			 IdleState/WRITER_IDLE :writer-idle)}))
    ;; assoc generic ChannelEvent fields:
    :channel (.getChannel netty-event)
    :future (.getFuture netty-event)))
