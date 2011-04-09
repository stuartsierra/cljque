(ns cljque.subscription)

(defprotocol Subscription
  (current [this])
  (active? [this]))

(defprotocol Subscribable
  (subscribe [this subscriber]))

(deftype Issue [cur subscriber]
  (current [this cur])
  (active? [this] true))

(extend-protocol Subscribable
  clojure.lang.PersistentVector
  (subscribe [this subscriber]
    (future
      (loop [issue (Issue. nil subscriber)
             s (seq this)]
        (when-let (.subscriber f issue)
          (recur ))))))