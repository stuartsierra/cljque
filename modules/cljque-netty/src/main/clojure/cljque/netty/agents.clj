(ns cljque.netty.agents
  (:use cljque.api
        cljque.netty.util))

(import-netty)

(defprotocol ChannelUpstreamHandlerProtocol
  (bound [this])
  (unbound [this])
  (open [this])
  (closed [this])
  (connected [this])
  (disconnected [this])
  (interest-changed [this])
  (child-channel-closed [this child-channel])
  (child-channel-open [this child-channel])
  (exception-caught [this exception])
  (message [this message])
  (write-complete [this written-amount]))

(defrecord MessageHandlers [bound closed connected disconnected
                            interest-changed open unbound child-channel-closed
                            child-channel-open exception-caught message
                            write-complete]
  ChannelUpstreamHandlerProtocol
  (event [this observable netty-event]
         (condp instance? netty-event
           MessageEvent
           (message (.getMessage ^MessageEvent netty-event))

           WriteCompletionEvent
           (write-complete (.getWrittenAmount ^WriteCompletionEvent netty-event))

           ChildChannelStateEvent
           (let [child (.getChildChannel ^ChildChannelStateEvent netty-event)]
             (if (.isOpen child)
               (child-channel-open observable child)
               (child-channel-closed observable child)))

           ChannelStateEvent
           (let [evnt ^ChannelStateEvent netty-event]
             (condp = (.getState evnt)
		 ChannelState/OPEN (if (.getValue evnt)
				     (open observable)
				     (closed observable))
		 ChannelState/BOUND (if (.getValue evnt)
				      (bound observable)
				      (unbound observable))
		 ChannelState/CONNECTED (if (.getValue evnt)
					  (connected observable)
					  (disconnected observable))
		 ChannelState/INTEREST_OPS (interest-changed observable)))

	   ExceptionEvent
	   (exception-caught observable (.getCause ^ExceptionEvent netty-event)))))

(defrecord ChannelAgentState [channel handlers])