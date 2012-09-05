(ns cljque.inotify)

(defprotocol INotify
  (attend [notifier f]
    "Registers callback f on notifier and returns notifier. When notifier
    receives a value, it will execute (f value), possibly on another
    thread. If notifier already has a value, it may execute (f value)
    immediately on the current thread. The function f should neither
    block nor throw exceptions without catching them."))

(defprotocol ISupply
  (supply [this x]
    "Sets the value of this notifier to x and invokes pending callbacks.
    Only works once; future invocations have no effect."))

(defn promise-wait
  "Blocks until notifier has a value and returns that value. With 3
  arguments, will return timeout-val if timeout (in milliseconds) is
  reached before a value is available."
  ([inotify]
     (let [p (promise)]
       (attend inotify p)
       (deref p)))
  ([inotify timeout-ms timeout-val]
     (let [p (promise)]
       (attend inotify p)
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
  (attend [this f]
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
  attend. Calling deref will block until a value is supplied, unless
  the 3-argument form of deref is used. See also - realized?"
  []
  (Notifier. nil []))

(deftype LazyNotifier [source f ^:unsynchronized-mutable v]
  INotify
  (attend [this g]
    (attend source (fn [x] (g (this x))))
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
  callback is attended, and f may be called multiple times."
  [inotify f]
  (LazyNotifier. inotify f f))

(defn derived-notifier
  "Returns a notifier which will receive the result of calling f on
  the value of inotify. Any exception thrown by f will be caught and
  supplied to the notifier. The function f will be called as soon as
  inotify has a value, and f will only be called once."
  [inotify f]
  (let [n (notifier)]
    (attend inotify
              (fn [x] (supply n (try (f x) (catch Throwable t t)))))
    n))

(defmacro on
  "bindings is a vector of [binding-form notifier]. When the notifier
  has a value, executes body with that value bound to the symbol (or
  any other destructuring form). Returns a notifier which receives the
  return value of body, or any exception thrown in body."
  [bindings & body]
  {:pre [(= 2 (count bindings))]}
  `(derived-notifier ~(second bindings)
                     (fn [~(first bindings)] ~@body)))

(defmacro lazy-on
  "Like 'on' but more efficient. The body will not be executed until
  its return value is needed, and may be executed multiple times."
  [bindings & body]
  {:pre [(= 2 (count bindings))]}
  `(lazy-notifier ~(second bindings)
                  (fn [~(first bindings)] ~@body)))

(defmacro do-on
  "Like 'on' but does not return a value."
  [bindings & body]
  {:pre [(= 2 (count bindings))]}
  `(attend ~(second bindings)
           (fn [~(first bindings)] ~@body)))
