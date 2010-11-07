(ns cljque.api)

;;; Message receiver protocols

(defprotocol Observable
  (subscribe [this key f]
    "Starts listening for messages. When a message is received,
    invokes f, possibly in another thread, with three arguments: this
    Observable, the key, and the message.")
  (unsubscribe [this key]
    "Stops sending events to the function identified by key."))

;;; Message sending protocols

(defprotocol MessageTarget
  (send! [this message]
    "Sends a message asynchronously."))

;;; IRef implementation

(extend clojure.lang.IRef
  Observable
  {:subscribe (fn [this key f]
		(add-watch this key
			   (fn [key this old new]
			     (f this key new))))
   :unsubscribe remove-watch})
