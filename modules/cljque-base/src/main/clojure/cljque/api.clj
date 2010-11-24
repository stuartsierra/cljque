(ns cljque.api)

;;; Message receiver protocols

(defprotocol Observer
  (event [this observable event]
    "Called when observable generates an event.")
  (done [this observable]
    "Called when observable o is finished generating events.")
  (error [this observable e]
    "Called when observable throws an exception e."))

(defprotocol Observable
  (subscribe [this observer]
    "Starts listening for messages. When a message is received,
    invokes f, possibly in another thread, with three arguments: this
    Observable, the key, and the message.

    Returns a no-arg function which unsubscribes the observer."))

;;; Message sending protocols

(defprotocol MessageTarget
  (send! [this message]
    "Sends a message asynchronously."))

;;; IRefs are Observable

(extend clojure.lang.IRef
  Observable
  {:subscribe (fn [this-ref observer]
		(let [key (Object.)]
		  (add-watch this-ref key
			     (fn [key iref old new]
			       (event observer iref new)))
		  (fn [] (remove-watch this-ref key))))})

;;; An Agent can wrap a MessageTarget and forward to it

(extend clojure.lang.Agent
  MessageTarget
  {:send! (fn [this-agent message]
	    (send this-agent send! message))})

;;; Functions are Observers

(extend clojure.lang.IFn
  Observer
  {:event (fn [this-fn observable event]
	    (this-fn observable event))
   :done (fn [this-fn observable]
	   (this-fn observable ::done))
   :error (fn [this-fn observable e]
	    (this-fn observable {::error e}))})
