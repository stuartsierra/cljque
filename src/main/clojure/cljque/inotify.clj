(ns cljque.inotify)

(defprotocol INotify
  (register [notifier f]
    "Register callback f on notifier. When notifier has a value, it
    will execute (f value), which should not throw exceptions. If
    notifier already has a value, executes (f value)
    immediately. Returns notifier."))

(defprotocol ISupply
  (supply [recipient x]
    "Submit a value x to recipient."))

(defn ready? [x]
  (if (satisfies? INotify x)
    (realized? x)
    true))

(defn notifier
  "Returns a notifier object that can be read with register and set,
  once only, with supply.

  Calling supply on a notifier A with another notifier B as the value
  will register A to receive the value supplied to B.

  See also - realized? and register."
  []
  (let [q (ref [])
        v (ref q)]
    (reify
      INotify
      (register [this f]
        (when-not (dosync
                   (when (= @v q)
                     (alter q conj f)))
          (f @v))
        this)
      ISupply
      (supply [this x]
        (if (ready? x)
          (do (doseq [w (dosync
                         (when (= @v q)
                           (let [qq @q]
                             (ref-set v x)
                             (ref-set q nil)
                             qq)))]
                (w x))
              x)
          (register x (fn [y] (supply this y)))))
      clojure.lang.IPending
      (isRealized [_]
        (not= @v q)))))

(defn apply-when-notified
  "Returns a notifier which will receive the result of 
  (apply f inotify args) when inotify notifies. Any exception thrown
  by f will be caught and supplyed to the notifier."
  [inotify f & args]
  (let [p (notifier)]
    (register inotify
              (fn [v] (supply p (try (apply f v args)
                                     (catch Throwable t t)))))
    p))

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
    `(apply-when-notified ~(second bindings)
                          (fn [~(first bindings)]
                            (when-ready ~(drop 2 bindings)
                              ~@body)))
    `(do ~@body)))

(deftype FutureSeq [n]
  INotify
  (register [this f] (register n (fn [_] (f (seq this)))))
  clojure.lang.IPending
  (isRealized [this] (realized? n))
  clojure.lang.Seqable
  (seq [this] (seq @n))
  clojure.lang.ISeq
  (first [this] (first @n))
  ;; Ensure rest is always wrapped in a FutureCons?
  (more [this] (rest @n))
  (next [this] (seq (rest @n)))
  (count [this] (count @n))
  (cons [this x] (clojure.lang.Cons. x this))
  (empty [this] (FutureSeq. (notifier)))
  (equiv [this that] (if (realized? n)
                       (= that @n)
                       (identical? this that))))

(defn future-seq
  "Returns a lazy seq which implements INotify. With an argument,
  returns a lazy seq backed by the given notifier. Calls to any
  sequence functions will block until the seq is realized.
  See also supply-next, supply-stop, and pump."
  ([] (future-seq (notifier)))
  ;; Check for Notifier or realized Seq?
  ([n] (FutureSeq. n)))

(defmethod clojure.core/print-method FutureSeq [x writer]
  (.write writer (str "#<FutureSeq "
                      (if (realized? x) (first x) :pending) ">")))

(defn future-map [f fseq]
  (future-seq
   (when-ready [s fseq]
     (when-let [c (seq s)]
       (lazy-seq
        (cons (f (first c))
              (future-map f (rest c))))))))

(defn future-filter [f fseq]
  (future-seq
   (when-ready [s fseq]
     (when-let [c (seq s)]
       (if (f (first c))
         (lazy-seq
          (cons (first c)
                (future-filter f (rest c))))
         (future-filter f (rest c)))))))

(defn future-take [n fseq]
  (future-seq
   (when-ready [s fseq]
     (when-let [c (seq s)]
       (when (pos? n)
         (lazy-seq
          (cons (first c) (future-take (dec n) (rest c)))))))))

(defn future-reduce
  ([f fseq]
     (when-ready [s fseq]
       (when-let [c (seq s)]
         (future-reduce f (first c) (rest c)))))
  ([f val fseq]
     (when-ready [s fseq]
       (if-let [c (seq s)]
         (future-reduce f (f val (first c)) (rest c))
         val))))

(defn supply-next
  "Extends a future-seq by supplying one Cons cell containing x and
  another future-seq. Returns the next future-seq."
  [fseq x]
  (rest (supply (.n fseq) (cons x (future-seq)))))

(defn supply-stop
  "Ends a future-seq by supplying nil. Returns nil."
  [fseq]
  (rest (supply (.n fseq) nil)))

(defn pump
  "Given an unrealized future-seq, returns a mutable reference p which
  can inject new values into the future-seq.

  (supply p x) will extend the future-seq by one cons cell containing
  x and another future-seq.

  (.close p) will terminate the future-seq.

  The currently pending future-seq is always available as @p."
  ([] (pump (future-seq)))
  ([fseq]
     (let [a (atom fseq)]
       (reify
         clojure.lang.IDeref
         (deref [this] @a)
         ISupply
         (supply [this x]
           (swap! a supply-next x))
         java.io.Closeable
         (close [this]
           (swap! a supply-stop))))))

(defn default-sink-error-handler [s e]
  (let [err (java.io.PrintWriter. *err*)]
    (.printStackTrace e err)
    (.flush err)))

(defn sink
  "Calls f for side effects on each successive value of fseq. Returns
  a Closeable, calling .close stops reacting to new values.

  Optional :error-handler is a function which will be called with the
  current future-seq and the exception. Default error handler prints a
  stacktrace to *err*."
  [f fseq & options]
  (let [{:keys [error-handler]
         :or {error-handler default-sink-error-handler}}
        options
        open? (atom true)]
    (register fseq
              (fn thisfn [s]
                (when @open?
                 (try (f (first s))
                      (register (rest s) thisfn)
                      (catch Throwable t
                        (error-handler s t))))))
    (reify java.io.Closeable
      (close [this] (reset! open? false)))))

(defn testme []
;; Sample usage
  (def a (future-seq))
  (def b (future-map #(* 5 %) a))
  (def c (future-filter even? b))
  (def d (future-take 10 c))
  (def e (future-reduce + d))

  (def p (pump a))
  (dotimes [i 100] (supply p i))
  (.close p)

  (assert (= (seq a) (range 100)))
  (assert (= (seq b) (map #(* 5 %) (range 100))))
  (assert (= (seq c) (filter even? b)))
  (assert (= (seq d) (list 0 10 20 30 40 50 60 70 80 90)))
  (assert (= 450 @e)))

(defn testerr []
  (def a (future-seq))
  (sink (comp prn inc) a)
  (def p (pump a))
  (dotimes [i 5] (supply p i))
  (supply p "hello")
  (.close p))

(defn testerr2 []
  (def a (future-seq))
  (def b (future-map inc a))
  (def c (future-filter odd? b))
  (def p (pump a))
  (dotimes [i 5] (supply p i))
  (supply p "hello")
  (.close p)
  (prn (seq a))
  (prn (take 2 c)))

;; Still TODO:
;; - supply chunked seqs to future-seqs
;; - extend INotify to futures

;; Other possibilities:
;; - better names?
;;   - latched sequences?
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
