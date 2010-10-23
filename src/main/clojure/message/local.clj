(ns message.local
  (:use message.api))

;;; Private, local-only implementation

(def ^{:private true} listeners (ref {}))

(defn- set-listener [address f]
  (dosync
   (when (get @listeners address)
     (throw (IllegalArgumentException.
	     (str "Tried to add second listener to ") (pr-str address))))
   (alter listeners assoc address f)))

(defn- remove-listener [address]
  (dosync
   (alter listeners dissoc address)))

(defn- unique-id []
  (str (java.util.UUID/randomUUID)))

(defn- reply-to
  "Wraps message in an envelope with a reply-to header of address."
  [address message]
  {:reply-to address, :message message})

(declare local)

(defn- responder
  "Returns a function wrapping f which expects a message with a
  reply-to wrapper. The function will invoke f on the original message
  and send back its return value."
  [f]
  (fn [{:keys [reply-to message]}]
    (send-message (local reply-to) (f message))))

;;; Generic send/listen message API

(defrecord LocalAddress [address]
  Listener
    (listen [this f]
      (future (set-listener address f)))
  Stoppable
    (stop [this]
      (future (remove-listener address)))
  Responder
    (respond [this f]
      (future (set-listener address (responder f))))
  MessageTarget
    (send-message [this message]
      (when-let [f (get @listeners address)]
	(future (f message))))
  RequestTarget
    (request [this message f]
      (let [token (unique-id)
	    target (LocalAddress. token)]
	@(listen target (fn [response] (f response) (stop target)))
	(send-message this (reply-to token message)))))

(defn local [address]
  (LocalAddress. address))
