(ns cljque.promises
  (:refer-clojure :exclude (promise deliver))
  (:import (java.util.concurrent CountDownLatch Executor TimeUnit)))

(def ^:dynamic *executor*
  "java.util.concurrent.Executor to use for callbacks created with the
  2-argument form of 'attend'. Applies at the time the callback is
  created, not when the promise is delivered. Defaults to the agent
  send-off thread pool. If bound to nil, no executor will be used and
  the callback will be invoked on the same thread that delivered the
  promise."
  clojure.lang.Agent/soloExecutor)

(defprotocol IDeliver
  (deliver [promise val]
    "Delivers the supplied value to the promise, releasing any pending
  derefs. A subsequent call to deliver or fail on the promise will
  have no effect. Returns the promise.")
  (fail [promise exception]
    "Delivers the supplied exception to the promise, releasing any
  pending derefs by throwing the exception. A subsequent call to
  deliver or fail on the promise will have no effect. Returns the
  promise."))

(defprotocol INotify
  (attend [promise f] [promise f executor]
    "Registers callback f on promise and returns the promise. When a
  value is delivered to the promise, it will submit #(f promise) to
  the executor, or *executor* if none supplied."))

(comment
  ;; This doesn't work because "Mismatched return type: execute,
  ;; expected: void, had: java.lang.Object"
  (defn local-executor []
    (reify Executor
      (execute [_ ^Runnable command]
        (.run command)))))

;; Try-finally in 'locking' breaks mutable fields, this is a workaround.
;; See CLJ-1023.
(defprotocol MutableVQE
  (set-v [this value])
  (set-q [this value])
  (set-e [this value]))

;; Do not create directly, use 'promise' function
(deftype Promise [latch
                  ^:unsynchronized-mutable v  ; value
                  ^:unsynchronized-mutable q  ; queue
                  ^:unsynchronized-mutable e] ; error
  MutableVQE
  (set-v [this value] (set! v value))
  (set-q [this value] (set! q value))
  (set-e [this value] (set! e value))
  IDeliver
  (deliver [this val]
    (when-let [fs (locking this
                    (when-let [fs q]
                      (set-v this val)
                      (set-q this nil)
                      fs))]
      (.countDown latch)
      (doseq [[f executor] fs]
        (if executor
          (.execute executor #(f this))
          (f this)))))
  (fail [this ex]
    (when-let [fs (locking this
                    (when-let [fs q]
                      (set-e this ex)
                      (set-q this nil)
                      fs))]
      (.countDown latch)
      (doseq [[f executor] fs]
        (if executor
          (.execute executor #(f this))
          (f this)))))
  INotify
  (attend
    [this f]
    (.attend this f *executor*))
  (attend
    [this f executor]
    (when-not (locking this
                (when q (set-q this (conj q [f executor]))))
      (.execute executor #(f this))))
  clojure.lang.IDeref
  (deref [_]
    (.await latch)
    (if e (throw e) v))
  clojure.lang.IBlockingDeref
  (deref
    [_ timeout-ms timeout-val]
    (if (.await latch timeout-ms TimeUnit/MILLISECONDS)
      (if e (throw e) v)
      timeout-val))
  clojure.lang.IPending
  (isRealized [this]
    (zero? (.getCount latch))))

(defn promise
  "Returns a promise object that can be set, once only, with 'deliver'
  or 'fail'. Calls to deref/@ prior to delivery will block, unless the
  variant of deref with timeout is used. All subsequent derefs will
  return the same delivered value without blocking. If the promise is
  failed, deref will throw the exception with which it failed.

  Callback functions can be attached to the promise with 'attend'.
  When the promise is delivered or failed, all callback functions will
  be invoked, in undetermined order, with the promise as an argument."
  []
  (Promise. (CountDownLatch. 1) nil [] nil))

(defmacro on
  "Bindings is a vector of [binding-form promise]. Creates a callback
  on promise which will execute body with binding-form bound to the
  value of the promise.

  Returns a new promise to which the return value of body will be
  delivered. If body throws an exception, or if the original promise
  failed, the returned promise fails with the same exception.

  If a value is delivered to the returned promise before the original
  promise is delivered, body MAY not be executed at all.

  The returned promise has the same default executor as the original
  promise."
  [bindings & body]
  (let [[binding-form base-promise] bindings]
    `(let [return# (promise)]
       (attend ~base-promise
               (fn [promise#]
                 (try (let [~binding-form (deref promise#)]
                        (when-not (realized? return#)
                          (deliver return# (do ~@body))))
                      (catch Throwable t#
                        (fail return# t#)))))
       return#)))
