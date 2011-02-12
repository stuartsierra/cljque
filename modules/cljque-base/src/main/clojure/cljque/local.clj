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
  (subscribe [this observer]
	     (let [key (Object.)])
	      (debug "Listening to" address)
	      (set-listener address key observer)
	      (fn [] (debug "Unlistening to" address "with key" key)
		(remove-listener address key)))
  MessageTarget
  (send! [this message]
	 (io!)
	 (debug "Sending to" address (pr-str message))
	 (doseq [[k observer] (get @listeners address)]
	   (future (on-event observer this message)))))

(defn local [address]
  (LocalAddress. address))

