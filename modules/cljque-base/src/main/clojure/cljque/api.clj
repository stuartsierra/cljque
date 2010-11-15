(ns cljque.api)

;;; Message receiver protocols

(defprotocol Observer
  (event [this observable key event]
    "Called when observable generates an event.")
  (done [this observable key]
    "Called when observable o is finished generating events.")
  (error [this observable key e]
    "Called when observable throws an exception e."))

(defprotocol Observable
  (subscribe [this key observer]
    "Starts listening for messages. When a message is received,
    invokes f, possibly in another thread, with three arguments: this
    Observable, the key, and the message.")
  (unsubscribe [this key]
    "Stops sending events to the observer identified by key."))

;;; Message sending protocols

(defprotocol MessageTarget
  (send! [this message]
    "Sends a message asynchronously."))

;;; IRefs are Observable

(extend clojure.lang.IRef
  Observable
  {:subscribe (fn [this-ref key observer]
		(add-watch this-ref key
			   (fn [key iref old new]
			     (event observer iref key new))))
   :unsubscribe remove-watch})

;;; Functions are Observers

(extend clojure.lang.IFn
  Observer
  {:event (fn [this observable key event]
	    (this observable key event))
   :done (fn [this observable key]
	   (this observable key ::done))
   :error (fn [this observable key e]
	    (this observable key {::error e}))})
