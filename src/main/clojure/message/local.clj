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

(declare local)

;;; Generic send/listen message API

(defrecord LocalAddress [address]
  Listener
    (listen [this f]
      (future (set-listener address f)))
  Stoppable
    (stop [this]
      (future (remove-listener address)))
  MessageTarget
    (send-message [this message]
      (when-let [f (get @listeners address)]
	(future (f message)))))

(defn local [address]
  (LocalAddress. address))
