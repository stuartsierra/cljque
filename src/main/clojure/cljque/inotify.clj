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

(deftype Notifier [v]
  ;; v is atom containing [supplied? value & callbacks]
  INotify
  (register [this f]
    (when (first
           (swap! v (fn [[supplied? & _ :as state]]
                      (if supplied?
                        state
                        (conj state f)))))
      (f (second @v)))
    this)
  ISupply
  (supply [this x]
    (when (first (swap! v (fn [[supplied? value & callbacks :as state]]
                            (if supplied? state  ; repeated supply, no-op
                                (assoc state 0 true 1 x)))))
      (doseq [w (drop 2 @v)]
        (w x))
      ;; clear callbacks
      (swap! v (fn [[supplied? value & _]] [supplied? value])))
    x)
  clojure.lang.IPending
  (isRealized [_]
    (first @v))
  clojure.lang.IDeref
  (deref [this]
    (let [vv @v]
      (if (first vv)
        (second vv)
        (promise-wait this))))
  clojure.lang.IBlockingDeref
  (deref [this timeout val]
    (let [vv @v]
      (if (first vv)
        (second vv)
        (promise-wait this timeout val)))))

(defmethod print-method Notifier [x writer]
  (.write writer (str "#<Notifier " @(. x v) ">")))

(defn notifier
  "Returns a notifier object that can be set, once only, with
  supply. When supplied, invokes callback functions added with
  register. Calling deref will block until a value is supplied, unless
  the 3-argument form of deref is used. See also - realized?"
  []
  (let [v (atom [false nil])]
    (Notifier. v)))

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

(defmacro attend
  "Takes a vector of bindings and a body. Each binding is a pair
  consisting of a symbol and a notifier. When the notifier notifies,
  it will be bound to the symbol and body will be executed. The return
  value of body will be supplied to a notifier which is returned from
  attend. Any exception thrown in body will be caught and supplied
  to the notifier. The body may be executed multiple times."
  [bindings & body]
  {:pre [(even? (count bindings))]}
  (if (seq bindings)
    `(lazy-notifier ~(second bindings)
                    (fn [~(first bindings)]
                      (attend ~(drop 2 bindings)
                        ~@body)))
    `(do ~@body)))

(defmacro when-ready
  [bindings & body]
  {:pre [(even? (count bindings))]}
  (if (seq bindings)
    `(derived-notifier ~(second bindings)
                       (fn [~(first bindings)]
                         (when-ready ~(drop 2 bindings)
                           ~@body)))
    `(do ~@body)))

(defmacro do-when-ready
  "Like when-ready, but does not return a value and will not execute
  body more than once."
  [bindings & body]
  {:pre [(even? (count bindings))]}
  (list 'do (if (seq bindings)
              `(register ~(second bindings)
                         (fn [~(first bindings)]
                           (do-when-ready ~(drop 2 bindings)
                                          ~@body)))
              `(do ~@body))
        nil))

(defprotocol IFutureSeq
  (current [this])
  (later [this]))

(deftype FutureCons [current later]
  IFutureSeq
  (current [this] current)
  (later [this] later))

(defn follow [a b]
  (FutureCons. a b))

(defn map& [f fseq]
  (attend [c fseq]
    (when c
      (follow (f (current c))
              (map& f (later c))))))

(defn take& [n fseq]
  (attend [x fseq]
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

(defn push
  "Extends a future-seq by supplying one Cons cell containing x and
  another future-seq. Returns the next future-seq."
  [fseq x]
  (later (supply fseq (follow x (notifier)))))

(defn stop
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
  ;; Performance comparison of derived-notifier vs lazy-notifier
  (dotimes [i 10]
    (let [origin (notifier)
          terminus (loop [i 0, n origin]
                     (if (= i 8000) n  ; StackOverflow at 10000
                         (recur (inc i) (derived-notifier n inc))))]
      (time (do (supply origin 1) (deref terminus)))))
  "Elapsed time: 11.295 msecs"
  "Elapsed time: 7.868 msecs"
  "Elapsed time: 16.707 msecs"
  "Elapsed time: 8.477 msecs"
  "Elapsed time: 14.757 msecs"
  "Elapsed time: 7.094 msecs"
  "Elapsed time: 16.908 msecs"
  "Elapsed time: 8.074 msecs"
  "Elapsed time: 7.767 msecs"
  "Elapsed time: 20.334 msecs"

  (dotimes [i 10]
    (let [origin (notifier)
          terminus (loop [i 0, n origin]
                     (if (= i 8000) n
                         (recur (inc i) (lazy-notifier n inc))))]
      (time (do (supply origin 1) (deref terminus)))))
  "Elapsed time: 0.409 msecs"
  "Elapsed time: 0.254 msecs"
  "Elapsed time: 0.229 msecs"
  "Elapsed time: 0.213 msecs"
  "Elapsed time: 0.222 msecs"
  "Elapsed time: 0.225 msecs"
  "Elapsed time: 0.244 msecs"
  "Elapsed time: 0.222 msecs"
  "Elapsed time: 0.217 msecs"
  "Elapsed time: 0.215 msecs"
)

;; "attend": occur with or as a result of; wait on (an important person)

;; Still TODO:
;; - supply chunked seqs to notifiers?
;; - extend INotify to futures?

;; Other possibilities:
;; - better names?
;; - support register on things which do not implement INotify?
;;   - future-seq fns would work on regular seqs
;;   - They would invoke callback immediately
;; - cancellable registrations?
;; - Use WeakReferences for callback queue?
;;   - if result is not used, notification can be skipped


;; This is a fully "unmaterialized" abstraction. It separates 
;; consumption from realization.

;; Special version of "sink" that takes 2 args: an agent and a
;; function to apply?


;; Local Variables:
;; mode: clojure
;; eval: (progn (define-clojure-indent (attend (quote defun))) (setq inferior-lisp-program "/Users/stuart/src/stuartsierra/cljque/run.sh"))
;; End:
