(ns cljque.local
  (:use cljque.api
        [clojure.contrib.logging :only (debug warn)]))

;;; Private, local-only implementation

(def ^{:private true} listeners (ref {}))

(defn- set-listener [address key f]
  (dosync
   (alter listeners assoc-in [address key] f)))

(defn- remove-listener [address key]
  (dosync
   (alter listeners update-in [address] dissoc key)
   (when (empty? (get @listeners address))
     (alter listeners dissoc address))))

;;; Generic send/listen message API

(defrecord LocalAddress [address]
  Observable
  (subscribe! [this key f]
	      (debug "Listening to" address "with key" key)
	      (set-listener address key f))
  (unsubscribe! [this key]
		(debug "Unlistening to" address "with key" key)
		(remove-listener address key))
  MessageTarget
  (send! [this message]
	 (debug "Sending to" address (pr-str message))
	 (doseq [[k f] (get @listeners address)]
	   (future (f this k message)))
	 nil))

(defn local [address]
  (LocalAddress. address))

