(ns cljque.promises
  (:refer-clojure :exclude (promise deliver future future-call))
  (:import (java.util.concurrent CountDownLatch Executor TimeUnit
                                 TimeoutException)))

(declare promise)

(def ^:dynamic *callback-executor*
  "java.util.concurrent.ExecutorService to use for callbacks created
  with the 2-argument form of 'attend'. Applies at the time the
  callback is created, not when the promise is delivered. Defaults to
  the agent send-off thread pool. If bound to nil, no executor will be
  used and the callback will be invoked on the same thread that
  delivered the promise."
  clojure.lang.Agent/soloExecutor)

(def ^:dynamic *future-executor*
  "java.util.concurrent.ExecutorService to use when creating futures.
  Defaults to the agent send-off thread pool."
  clojure.lang.Agent/soloExecutor)

(defprotocol IDeliver
  (-deliver [promise val])
  (-fail [promise exception]))

(defprotocol INotify
  (-attend [promise f executor]
    "Adds a callback to this promise which will execute (f promise) on
  executor when it has a value. Returns the promise."))

(defn attend
  "Registers callback f on promise. When a value is delivered to the
  promise, it will submit #(f promise) to the executor, or
  *callback-executor* if none supplied. Returns promise."
  ([promise f]
     (-attend promise f *callback-executor*))
  ([promise f executor]
     (-attend promise f executor)))

(defn fail
  "Delivers the supplied exception to the promise, releasing any
  pending derefs by throwing the exception. A subsequent call to
  deliver or fail on the promise will have no effect. Returns nil if
  the promise has already been delivered; otherwise returns the
  promise."
  [promise exception]
  (-fail promise exception))

(defn deliver
  "Delivers the supplied value to the promise, releasing any pending
  derefs. A subsequent call to deliver or fail on the promise will
  have no effect. Returns nil if the promise has already been
  delivered; otherwise returns the promise.

  If val is a promise then this promise will wait until val is
  delivered and become realized with the same value."
  [promise val]
  (if (and (not (realized? promise))
           (extends? INotify (type val)))
    (do (attend val (fn [p] (try (deliver promise @p)
                                 (catch Throwable t
                                   (fail promise t))))
                nil)
        promise)
    (-deliver promise val)))

(defn follow
  "Registers callback f on source-promise. When a value is delivered
  to the promise, it will submit #(f value) to the executor, or
  *callback-executor* if none supplied. Returns a new promise which
  will be delivered with the return value of f, or failed if the
  original promise fails or f throws an exception.

  If a value is delivered to the returned promise before the original
  promise is delivered, f may not be executed at all."
  ([source-promise f]
     (follow source-promise f *callback-executor*))
  ([source-promise f executor]
     (let [return (promise)]
       (attend source-promise
               (fn [p]
                 (when-not (realized? return)
                   (try (deliver return (f @p))
                        (catch Throwable t
                          (fail return t)))))
               executor)
       return)))

(defn- add-suppressed
  "If this JDK supports it, add the suppressed exception onto the
  Throwable. Always returns the Throwable."
  [throwable suppressed]
  (when-not (identical? throwable suppressed)
    (when-let [addSuppressed (try (.getMethod (.getClass throwable)
                                              "addSuppressed"
                                              (object-array [Throwable]))
                                  (catch NoSuchMethodException _ nil))]
      (.invoke addSuppressed throwable (object-array [suppressed]))))
  throwable)

(defn follow-fail
  "Registers a failure callback f on source-promise. When the promise
  fails, it will submit #(f exception) to the executor, or
  *callback-executor* if none supplied. Returns a new promise which
  will be delivered with the return value of f, or failed if f throws
  an exception.

  If the source-promise does not fail, the returned promise will be
  delivered with its value.

  If a value is delivered to the returned promise before the original
  promise is delivered, f may not be executed at all."
  ([source-promise f]
     (follow-fail source-promise f *callback-executor*))
  ([source-promise f executor]
     (let [return (promise)]
       (attend source-promise
               (fn [p]
                 (when-not (realized? return)
                   (if-let [ex (try @p nil (catch Throwable t t))]
                     (try (deliver return (f ex))
                       (catch Throwable t
                         (fail return (add-suppressed t ex))))
                     (deliver return @p))))
               executor)
       return)))

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
  (-deliver [this val]
    (when-let [fs (locking this
                    (when-let [fs q]
                      (set-v this val)
                      (set-q this nil)
                      fs))]
      (.countDown latch)
      (doseq [f fs] (f))
      this))
  (-fail [this ex]
    (when-let [fs (locking this
                    (when-let [fs q]
                      (set-e this ex)
                      (set-q this nil)
                      fs))]
      (.countDown latch)
      (doseq [f fs] (f))
      this))
  INotify
  (-attend [this f executor]
    (let [exe (if executor
                (fn [] (.execute executor #(f this)))
                #(f this))]
      (when-not (locking this
                  (when q (set-q this (conj q exe))))
        (exe)))
    this)
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

(defmacro then
  "Creates a callback on promise which will execute body with
  binding-form bound to the value of the promise.

  Returns a new promise to which the return value of body will be
  delivered. If body throws an exception, or if the original promise
  failed, the returned promise fails with the same exception.

  If a value is delivered to the returned promise before the original
  promise is delivered, body MAY not be executed at all.

  Promises can be chained using 'then' and the threading macro:

      (-> a-promise
          (then x ...)
          (then y ...)
          (then z ...)
          (recover err ...))"
  [source-promise binding-form & body]
  `(follow ~source-promise (fn [~binding-form] ~@body)))

(defmacro recover
  "Creates a failure callback on promise. If the promise fails, it
  will execute body with binding-form bound to the exception.

  Returns a new promise which will be delivered with the value of the
  original promise (if it does not fail) or the return value of
  body (if it does). If body throws an exception, the returned promise
  fails with the same exception.

  If a value is delivered to the returned promise before the original
  promise is delivered, body MAY not be executed at all."
  [source-promise binding-form & body]
  `(follow-fail ~source-promise (fn [~binding-form] ~@body)))

(defn future-call
  "Takes a function of no args and returns a future/promise object.
  Invokes the function on *future-executor* and delivers it to the
  returned promise. If the function throws an exception, fails the
  promise with that exception. The future can be cancelled with
  future-cancel."
  ([f] (future-call f *future-executor*))
  ([f executor]
     (let [return (promise)
           task (fn [] (try (let [value (f)]
                              (deliver return value)
                              value)
                            (catch Throwable t
                              (fail return t))))
           fut (.submit executor task)]
       (reify 
         clojure.lang.IDeref 
         (deref [_] (deref return))
         clojure.lang.IBlockingDeref
         (deref [_ timeout-ms timeout-val]
           (deref return timeout-ms timeout-val))
         clojure.lang.IPending
         (isRealized [_] (realized? return))
         java.util.concurrent.Future
         (get [_] (.get fut))
         (get [_ timeout unit] (.get fut timeout unit))
         (isCancelled [_] (.isCancelled fut))
         (isDone [_] (.isDone fut))
         (cancel [_ interrupt?] (.cancel fut interrupt?))
         INotify
         (-attend [_ f executor]
           (-attend return f executor))))))

(defmacro future
  "Returns a future/promise object. Executes body on *future-executor*
  and delivers its result to the returned promise. If body throws an
  exception, fails the promise with that exception. The future can be
  cancelled with future-cancel."
  [& body]
  `(future-call (fn [] ~@body)))
