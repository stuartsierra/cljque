(ns message.api)

;;; Message receiver protocols

(defprotocol Listener
  (listen [this f]
    "Asynchronously starts listening. When a message is received,
    invokes f with the message as its argument."))

(defprotocol Responder
  (respond [this f]
    "Asynchronously starts listening. When a message is received,
    invokes f with the message as its argument. The return value of f
    is returned to the sender of the message."))

(defprotocol Stoppable
  (stop [this]))

;;; Message sending protocols

(defprotocol MessageTarget
  (send-message [this message]))

(defprotocol RequestTarget
  (request [this message f]))
