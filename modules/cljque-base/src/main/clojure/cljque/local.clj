(ns cljque.local
  (:use cljque.api))

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
	      (set-listener address key observer)
	      (fn [] (remove-listener address key)))
  MessageTarget
  (send! [this message]
	 (io!)
	 (doseq [[k observer] (get @listeners address)]
	   (future (on-event observer this message)))))

(defn local [address]
  (LocalAddress. address))

