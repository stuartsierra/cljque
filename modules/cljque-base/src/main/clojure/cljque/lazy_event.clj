(ns cljque.lazy-event)

(defprotocol IEvent
  (event [this])
  (current [this]))

(deftype LazyEvent [^:unsynchronized-mutable f
                    ^:unsynchronized-mutable val]
  IEvent
  (event [this]
    (locking this
      (when f
        (set! val (f))
        (set! f nil))
      (when val this)))
  (current [this]
    (event this)
    val))

(extend-type nil
  IEvent
  (event [this] nil)
  (current [this] nil))

(defn lazy-event [f]
  (LazyEvent. f nil))

(defn cons-event [val]
  (LazyEvent. nil val))

(defprotocol Observable
  (observe [this f]))

(extend-protocol Observable
  clojure.lang.PersistentVector
  (observe [this f]
    (loop [f f, s (seq this)]
      (when f
        (if-let [s (seq s)]
          (recur (f (cons-event (first s)))
                 (rest s))
          (f nil))))))
