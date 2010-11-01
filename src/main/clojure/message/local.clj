(ns message.local
  (:use message.api
	[clojure.contrib.logging :only (debug warn)]))

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
    (listen! [this f]
      (debug "Starting local listener" address)
      (set-listener address f))
  Stoppable
    (stop! [this]
      (debug "Stopping local listener" address)
      (remove-listener address))
  MessageTarget
    (send! [this message]
      (debug "Sending local message to" address ":" (pr-str message))
      (if-let [f (get @listeners address)]
	(future (f message))
	(warn "No local listener for" address))))

(defn local [address]
  (LocalAddress. address))
