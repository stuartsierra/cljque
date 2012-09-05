(ns cljque.inotify)

(defprotocol INotify
  (register [notifier f]
    "Register callback f on notifier. When notifier receives a value,
    it will execute (f value), possibly on another thread. Function f
    should neither block nor throw exceptions. If notifier already has
    a value, it may execute (f value) immediately. Returns
    notifier."))

(defprotocol ISupply
  (supply [this x]
    "Supply a value x to this notifier and invoke pending
    callbacks. Only works once; future invocations have no effect."))

(defn promise-wait
  "Block until notifier has a value, return that value. With 3
  arguments, will return timeout-val if timeout (in milliseconds) is
  reached before a value is available."
  ([inotify]
     (let [p (promise)]
       (register inotify p)
       (deref p)))
  ([inotify timeout-ms timeout-val]
     (let [p (promise)]
       (register inotify p)
       (deref p timeout-ms timeout-val))))

;; Normal `locking` breaks mutable fields; see CLJ-1023
(defmacro unsafe-locking [lock & body]
  `(do (monitor-enter ~lock)
       (let [result# (do ~@body)]
         (monitor-exit ~lock)
         result#)))

(deftype Notifier [^:unsynchronized-mutable v
                   ^:unsynchronized-mutable q]
  INotify
  (register [this f]
    (when-not (unsafe-locking this
                (when q
                  (set! q (conj q f))))
      (f v))
    this)
  ISupply
  (supply [this x]
    (doseq [w (unsafe-locking this
                (when q
                  (set! v x)
                  (let [qq q]
                    (set! q nil)
                    qq)))]
      (w x))
    x)
  clojure.lang.IPending
  (isRealized [this]
    (unsafe-locking this (not q)))
  clojure.lang.IDeref
  (deref [this]
    (if q
      (promise-wait this)
      v))
  clojure.lang.IBlockingDeref
  (deref [this timeout val]
    (if q
      (promise-wait this timeout val)
      v)))

(defmethod print-method Notifier [x writer]
  (.write writer (str "#<Notifier " (if (realized? x) @x :pending) ">")))

(defn notifier
  "Returns a notifier object that can be set, once only, with
  supply. When supplied, invokes callback functions added with
  register. Calling deref will block until a value is supplied, unless
  the 3-argument form of deref is used. See also - realized?"
  []
  (Notifier. nil []))

(deftype LazyNotifier [source f ^:unsynchronized-mutable v]
  INotify
  (register [this g]
    (register source (fn [x] (g (this x))))
    this)
  clojure.lang.IFn
  (invoke [this x]
    (if (= f v)
      (set! v (try (f x) (catch Throwable t t)))
      v))
  clojure.lang.IPending
  (isRealized [this]
    (= f v))
  clojure.lang.IDeref
  (deref [this]
    (if (= f v)
      (promise-wait this)
      v))
  clojure.lang.IBlockingDeref
  (deref [this timeout val]
    (if (= f v)
      (promise-wait this timeout val)
      v))
  java.lang.Object
  (toString [this]
    (str "#<LazyNotifier " (if (= f v) :pending (pr-str v)) ">")))

(defmethod print-method LazyNotifier [x writer]
  (.write writer (.toString x)))

(defn lazy-notifier
  "Returns a notifier which will receive the result of calling f on
  the value of inotify. Any exception thrown by f will be caught and
  supplied to the notifier. The function f will not be called unless a
  callback is registered, and f may be called multiple times."
  [inotify f]
  (LazyNotifier. inotify f f))

(defn derived-notifier
  "Returns a notifier which will receive the result of calling f on
  the value of inotify. Any exception thrown by f will be caught and
  supplied to the notifier. The function f will be called as soon as
  inotify has a value, and f will only be called once."
  [inotify f]
  (let [n (notifier)]
    (register inotify
              (fn [x] (supply n (try (f x) (catch Throwable t t)))))
    n))

(defmacro on
  "bindings is a vector of [symbol notifier]. When the notifier has a
  value, executes body with that value will be bound to the symbol (or
  any other destructuring form). Returns a notifier which receives the
  return value of body. Any exception thrown in body will be caught
  and supplied to the notifier."
  [bindings & body]
  {:pre [(= 2 (count bindings))]}
  `(derived-notifier ~(second bindings)
                     (fn [~(first bindings)] ~@body)))

(defmacro lazy-on
  "Like 'on', but body will not be executed until the value is needed,
  and may be executed multiple times. More efficient than 'on'."
  [bindings & body]
  {:pre [(= 2 (count bindings))]}
  `(lazy-notifier ~(second bindings)
                  (fn [~(first bindings)] ~@body)))

(defmacro do-on
  "Like 'on' but does not return a value."
  [bindings & body]
  {:pre [(= 2 (count bindings))]}
  `(register ~(second bindings)
             (fn [~(first bindings)] ~@body)))
