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
