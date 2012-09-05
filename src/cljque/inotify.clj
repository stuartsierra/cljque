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

(defprotocol IFutureSeq
  (current [this])
  (later [this]))

(deftype FutureCons [current later]
  IFutureSeq
  (current [this] current)
  (later [this] later))

(extend nil IFutureSeq
        {:current (constantly nil)
         :later (constantly nil)})

(defn follow [a b]
  (FutureCons. a b))

(defn map& [f fseq]
  (lazy-on [c fseq]
    (when c
      (follow (f (current c))
              (map& f (later c))))))

(defn take& [n fseq]
  (lazy-on [x fseq]
    (when (and (pos? n) x)
      (follow (current x) (take& (dec n) (later x))))))

(defn drop& [n fseq]
  (let [out (notifier)
        step (fn thisfn [n x]
               (if x
                 (if (zero? n)
                   (supply out x)
                   (register (later x) (partial thisfn (dec n))))
                 (supply out nil)))]
    (register fseq (partial step (dec n)))
    out))

(defn filter& [f fseq]
  (let [out (notifier)
        step (fn thisfn [x]
               (if x
                 (let [c (current x)]
                   (if (f c)
                     (supply out (follow c (filter& f (later x))))
                     (register (later x) thisfn)))
                 (supply out nil)))]
    (register fseq step)
    out))

(defn reduce& [f init fseq]
  (let [out (notifier)
        step (fn thisfn [init x]
               (if x
                 (register (later x)
                           (partial thisfn (f init (current x))))
                 (supply out init)))]
    (register fseq (partial step init))
    out))

(defn push!
  "Extends a future-seq by supplying one Cons cell containing x and
  another future-seq. Returns the next future-seq."
  [fseq x]
  (later (supply fseq (follow x (notifier)))))

(defn stop!
  "Ends a future-seq by supplying nil. Returns nil."
  [fseq]
  (later (supply fseq nil)))

(defn default-sink-error-handler [s e]
  (let [err (java.io.PrintWriter. *err*)]
    (.printStackTrace e err)
    (.flush err)))

(defn sink
  "Calls f for side effects on each successive value of fseq.

  Optional :error-handler is a function which will be called with the
  current future-seq and the exception. Default error handler prints a
  stacktrace to *err*. After an error is thrown, future values of fseq
  will be ignored."
  [f fseq & options]
  (let [{:keys [error-handler]
         :or {error-handler default-sink-error-handler}}
        options]
    (register fseq
              (fn thisfn [s]
                (when s
                  (try (f (current s))
                       (register (later s) thisfn)
                       (catch Throwable t
                         (error-handler s t))))))
    nil))

(defmacro doseq& [bindings & body]
  {:pre [(vector? bindings) (= 2 (count bindings))]}
  `(sink (fn [~(first bindings)] ~@body) ~(second bindings)))

(defn first-in
  "Returns a notifier which receives the value of the first given
  notifier to have a value."
  [& notifiers]
  (let [out (notifier)]
    (doseq [n notifiers]
      (register n #(supply out %)))
    out))

(defn future-call& [f]
  (let [out (notifier)]
    (future-call #(supply out (try (f) (catch Throwable t t))))
    out))

(defmacro future& [& body]
  `(future-call& (fn [] ~@body)))

(comment
  ;; Sample usage
  (def a (notifier))
  (def b (filter& even? a))
  (def c (map& #(* 5 %) b))
  (def d (take& 10 c))
  (def e (reduce& + 0 d))
  (doseq& [x d] (println "d is" x))
  (do-when-ready [x e] (println "e is" x))
  
  ;; This must be an Agent, not an Atom or Ref:
  (def tick (agent a))
  (dotimes [i 100]
    (send tick push! i))
  (send tick stop!)
  )

(comment
  ;; Performance comparison of derived-notifier vs lazy-notifier
  (dotimes [i 10]
    (let [origin (notifier)
          terminus (loop [i 0, n origin]
                     (if (= i 1000) n
                         (recur (inc i) (derived-notifier n inc))))]
      (time (do (supply origin 1) (deref terminus)))))
"Elapsed time: 8.002 msecs"
"Elapsed time: 1.529 msecs"
"Elapsed time: 1.558 msecs"
"Elapsed time: 1.494 msecs"
"Elapsed time: 1.496 msecs"
"Elapsed time: 1.522 msecs"
"Elapsed time: 1.534 msecs"
"Elapsed time: 1.432 msecs"
"Elapsed time: 1.652 msecs"
"Elapsed time: 1.53 msecs"

  (dotimes [i 10]
    (let [origin (notifier)
          terminus (loop [i 0, n origin]
                     (if (= i 1000) n
                         (recur (inc i) (lazy-notifier n inc))))]
      (time (do (supply origin 1) (deref terminus)))))
"Elapsed time: 2.095 msecs"
"Elapsed time: 0.857 msecs"
"Elapsed time: 0.725 msecs"
"Elapsed time: 0.851 msecs"
"Elapsed time: 0.866 msecs"
"Elapsed time: 0.86 msecs"
"Elapsed time: 0.887 msecs"
"Elapsed time: 0.866 msecs"
"Elapsed time: 0.841 msecs"
"Elapsed time: 0.513 msecs"
)

;; "lazy-on": occur with or as a result of; wait on (an important person)

;; Still TODO:
;; - Supply chunked seqs to notifiers

;; Open questions:
;; - Should FutureCons implement ISeq?
;;   - implies all of IPersistentCollection and Seqable (and maybe Sequential)
;; - Should INotify be extended to other types?
;;   - future-seq fns would work on regular seqs
;;   - They would invoke callback immediately
;; - Should registration be cancellable?
;;   - Complicates bookkeeping
;; - Use WeakReferences for callback queue?
;;   - if result is not used, notification can be skipped
;; - Should there be any integration with Agents?
;;   - e.g. 'sink' that takes agent & function?


;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (lazy-on (quote defun)) (when-ready (quote defun)) (do-when-ready (quote defun)))
;; End:
