(ns message.api)

;;; Message receiver protocols

(defprotocol Listener
  (listen [this f]
    "Asynchronously starts listening. When a message is received,
    invokes f with the message as its argument."))

(defprotocol Stoppable
  (stop [this]))

;;; Message sending protocols

(defprotocol MessageTarget
  (send-message [this message]))
