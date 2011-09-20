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

(deftype DerivedNotifier [source f ^:unsynchronized-mutable v]
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
    (str "#<DerivedNotifier " (if (= f v) :pending (pr-str v)) ">")))

(defmethod print-method DerivedNotifier [x writer]
  (.write writer (.toString x)))

(defn derived-notifier
  "Returns a notifier which will receive the result of 
  calling f on the value of inotify. Any exception thrown
  by f will be caught and supplied to the notifier."
  [inotify f]
  (DerivedNotifier. inotify f f))

(defmacro when-ready
  "Takes a vector of bindings and a body. Each binding is a pair
  consisting of a symbol and a notifier. When the notifier notifies,
  it will be bound to the symbol and body will be executed. The return
  value of body will be supplied to a notifier which is returned from
  when-ready. Any exception thrown in body will be caught and
  supplied to the notifier."
  [bindings & body]
  {:pre [(even? (count bindings))]}
  (if (seq bindings)
    `(derived-notifier ~(second bindings)
                       (fn [~(first bindings)]
                         (when-ready ~(drop 2 bindings)
                           ~@body)))
    `(do ~@body)))

(deftype FutureCons [current later])

(defn current [fc] (when fc (. fc current)))

(defn later [fc] (when fc (. fc later)))

(defn follow [a b]
  (FutureCons. a b))

(defn map& [f fseq]
  (when-ready [c fseq]
    (when c
      (follow (f (current c))
              (map& f (later c))))))

(defn take& [n fseq]
  (when-ready [x fseq]
    (when (and (pos? n) x)
      (follow (current x) (take& (dec n) (later x))))))

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
;; eval: (progn (define-clojure-indent (when-ready (quote defun))) (setq inferior-lisp-program "/Users/stuart/src/stuartsierra/cljque/run.sh"))
;; End:
