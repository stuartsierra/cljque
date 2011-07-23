(ns cljque.inotify
  (:refer-clojure :exclude (promise)))

(defprotocol INotify
  (register [notifier f]
    "Register callback f on notifier. When notifier has a value, it
    will execute (f notifier), which should not throw exceptions. If
    notifier already has a value, executes (f notifier)
    immediately. Returns notifier."))

(defn ready? [x]
  (if (instance? clojure.lang.IPending x)
    (realized? x)
    true))

(defn promise
  "Returns a promise object that can be read with deref/@, and set,
  once only, with deliver. Calls to deref/@ prior to delivery will
  block, unless the variant of deref with timeout is used. All
  subsequent derefs will return the same delivered value without
  blocking. See also - realized? and register."
  []
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        q (java.util.concurrent.ConcurrentLinkedQueue.)
        v (atom q)]
    (.add q (fn [_] (.countDown latch)))
    (reify
      INotify
      (register [this f]
        (.add q f)
        (when (not= q @v)
          (.remove q)
          (f this))
        this)
      ;; Implementation of IFn for deliver:
      clojure.lang.IFn
      (invoke [this x]
        (if (ready? x)
          (when (compare-and-set! v q x)
            (doseq [w q] (w this))
            x)
          (register x (fn [y] (deliver this y)))))
      clojure.lang.IPending
      (isRealized [_]
        (zero? (.getCount latch)))
      clojure.lang.IDeref
      (deref [_]
        (.await latch)
        @v)
      clojure.lang.IBlockingDeref
      (deref
        [_ timeout-ms timeout-val]
        (if (.await latch timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          @v
          timeout-val)))))

(defn apply-when-notified
  "Returns a promise which will receive the result of 
  (apply f inotify args) when inotify notifies. Any exception thrown
  by f will be caught and delivered to the promise."
  [inotify f & args]
  (let [p (promise)]
    (register inotify
              (fn [v] (deliver p (try (apply f v args)
                                      (catch Throwable t t)))))
    p))

(defmacro when-ready
  "Takes a vector of bindings and a body. Each binding is a pair
  consisting of a symbol and a notifier. When the notifier notifies,
  it will be bound to the symbol and body will be executed. The return
  value of body will be delivered to a promise which is returned from
  when-ready. Any exception thrown in body will be caught and
  delivered to the promise."
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
  (more [this] (rest @n))
  (next [this] (seq (rest @n)))
  (count [this] (inc (count @n)))
  (cons [this x] (clojure.lang.Cons. x this))
  (empty [this] (FutureSeq. (promise)))
  (equiv [this that] (and (realized? n)
                          (= @n that))))

(defn future-seq
  "Returns a lazy seq which implements INotify. With an argument,
  returns a lazy seq backed by the given notifier. Calls to any
  sequence functions will block until the seq is realized.
  See also deliver-next, deliver-stop, and pump."
  ([] (future-seq (promise)))
  ([n] (FutureSeq. n)))

(defmethod clojure.core/print-method FutureSeq [x writer]
  (.write writer (str "#<FutureSeq "
                      (if (realized? x) (first x) :pending) ">")))

(defn deliver-next
  "Extends a future-seq by delivering one Cons cell containing x and
  another future-seq. Returns the next future-seq."
  [fseq x]
  (rest (deliver (.n fseq) (cons x (future-seq)))))

(defn deliver-stop
  "Ends a future-seq by delivering nil. Returns nil."
  [fseq]
  (rest (deliver (.n fseq) nil)))

(defn pump
  "Given a future-seq, returns a stateful function f which can insert
  new values into the seq.

  (f x) will extend the future-seq by one cons cell containing x and
  advance f to the next future-seq.

  (f) will terminate the future-seq."
  [fseq]
  (let [a (atom fseq)]
    (fn
      ([] (swap! a deliver-stop))
      ([x] (swap! a deliver-next x)))))

(defn future-map [f fseq]
  (future-seq
   (when-ready [s fseq]
     (when-let [c (seq s)]
       (cons (f (first c))
             (future-map f (rest c)))))))

(defn future-filter [f fseq]
  (future-seq
   (when-ready [s fseq]
     (when-let [c (seq s)]
       (if (f (first c))
         (cons (first c)
               (future-filter f (rest c)))
         (future-filter f (rest c)))))))

(defn future-take [n fseq]
  (future-seq
   (when-ready [s fseq]
     (when-let [c (seq s)]
       (when (pos? n)
         (cons (first c) (future-take (dec n) (rest c))))))))

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

(comment
;; Sample usage
  (def a (future-seq))
  (def b (future-map #(* 5 %) a))
  (def c (future-filter even? b))
  (def d (future-take 10 c))
  (def e (future-reduce + d))

  (def p (pump a))
  (dotimes [i 100] (p i))
  (p)

  (assert (= (seq a) (range 100)))
  (assert (= (seq b) (map #(* 5 %) (range 100))))
  (assert (= (seq c) (filter even? b)))
  (assert (= (seq d) (list 0 10 20 30 40 50 60 70 80 90)))
  (assert (= 450 @e))
;; end comment
  )

;; Still TODO:
;; - future-reduce
;; - deliver chunked seqs to future-seqs
;; - extend INotify to futures

;; Other possibilities:
;; - better names?
;; - support register on things which do not implement INotify?
;;   - future-seq fns would work on regular seqs
;;   - They would invoke callback immediately
;; - promise re-throws exception on deref?
;; - cancellable registrations?
;; - Use WeakReferences for callback queue?
;;   - if result is not used, notification can be skipped
;; - make promise use an atom instead of refs?


;; Local Variables:
;; mode: clojure
;; eval: (progn (define-clojure-indent (when-ready (quote defun))) (setq inferior-lisp-program "/Users/stuart/src/stuartsierra/cljque/run.sh"))
;; End:
