(ns cljque.api)

;;; Message receiver protocols

(defprotocol Listener
  (listen! [this f]
    "Starts listening for messages. When a message is received,
    invokes f in a future with the message as its argument."))

(defprotocol Stoppable
  (stop! [this]
    "Stops listening to this message source."))

;;; Message sending protocols

(defprotocol MessageTarget
  (send! [this message]
    "Sends a message asynchronously."))
