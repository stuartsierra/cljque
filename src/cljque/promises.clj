(ns cljque.promises
  (:refer-clojure :exclude (promise deliver))
  (:import (java.util.concurrent CountDownLatch Executor TimeUnit)))

(defprotocol IDeliver
  (deliver [promise val]
    "Delivers the supplied value to the promise, releasing any pending
  derefs. A subsequent call to deliver or abort on the promise will
  have no effect. Returns the promise.")
  (abort [promise ex]
    "Delivers the supplied exception to the promise, releasing any
  pending derefs by throwing the exception. A subsequent call to
  deliver or abort on the promise will have no effect. Returns the
  promise."))

(defprotocol INotify
  (attend [promise f] [promise f executor]
    "Registers callback f on promise and returns the promise. When a
    value is delivered to the promise, it will submit #(f promise) to
    the executor."))

(comment
  ;; This doesn't work because "Mismatched return type: execute,
  ;; expected: void, had: java.lang.Object"
  (defn local-executor []
    (reify Executor
      (execute [_ ^Runnable command]
        (.run command)))))

(defn promise []
  (let [d (CountDownLatch. 1)
        v (atom [d false])]
    (reify
      IDeliver
      (deliver [this val]
        (let [[val _ & q] (swap! v #(if (= d (first %))
                                      (assoc % 0 val)
                                      %))]
          (when-not (= d val)
            (.countDown d)
            (doseq [[f ^Executor executor] q]
              (if executor
                (.execute executor #(f this))
                (f this))))))
      (abort [this ex]
        (let [[val _ & q] (swap! v #(if (= d (first %))
                                      (assoc % 0 val 1 true)
                                      %))]
          (when-not (= d val)
            (.countDown d)
            (doseq [[f ^Executor executor] q]
              (if executor
                (.execute executor #(f this))
                (f this))))))
      INotify
      (attend
        [this f]
        (let [[val] (swap! v #(if (= d (first %)) (conj % [f]) %))]
          (when-not (= d val)
            (f this))
          this))
      (attend
        [this f executor]
        (let [[val] (swap! v #(if (= d (first %)) (conj % [f executor]) %))]
          (when-not (= d val)
            (.execute executor #(f this)))
          this))
      clojure.lang.IDeref
      (deref [_]
        (.await d)
        (let [[val aborted?] @v]
          (if aborted?
            (throw val)
            val)))
      clojure.lang.IBlockingDeref
      (deref
        [_ timeout-ms timeout-val]
        (if (.await d timeout-ms TimeUnit/MILLISECONDS)
          (let [[val aborted?] @v]
            (if aborted?
              (throw val)
              val))
          timeout-val))
      clojure.lang.IPending
      (isRealized [this]
        (zero? (.getCount d))))))
