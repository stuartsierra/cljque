(ns cljque.inotify)

(defprotocol Register
  (register [this f]
    "Register callback f to be invoked on the value of this when it
    becomes available."))

(defn notifier
  "Returns a promise on which callbacks can be registered."
  []
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        q (ref []) ;; make this a Java Queue?
        v (ref nil)] ;; make this an atom, use the Queue as a marker?
    (reify
      Register
      (register [this f]
        (when-not (dosync (when @q
                            (alter q conj f)))
          (f this)
          this))
      ;; Implementation of IFn for deliver:
      clojure.lang.IFn
      (invoke [this x]
        (when-let [watchers (dosync (when @q
                                      (ref-set v x)
                                      (let [dq @q]
                                        (ref-set q nil)
                                        dq)))]
          (.countDown latch)
          (doseq [w watchers]
            (w this))
          x))
      clojure.lang.IPending
      (isRealized [this]
        (zero? (.getCount latch)))
      clojure.lang.IDeref
      (deref [this]
        (.await latch)
        @v)
      clojure.lang.IBlockingDeref
      (deref
        [_ timeout-ms timeout-val]
        (if (.await latch timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
          @v
          timeout-val)))))

(deftype FutureSeq [n]
  Register
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
  (empty [this] (FutureSeq. (notifier)))
  (equiv [this that] (and (realized? n)
                          (= @n that))))

(defn future-seq
  "Returns a lazy seq on which callbacks can be registered.
  With an argument, returns a lazy seq backed by the given 
  notifier."
  ([] (future-seq (notifier)))
  ([n] (FutureSeq. n)))

(defn deliver-next [fc x]
  (deliver (.n fc) (cons x (future-seq))))

(defn deliver-stop [fc]
  (deliver (.n fc) nil))

(defn pump
  "Given a future-seq fc, returns a function f.

  (f x) will extend the future-seq by one cons cell containing x.

  (f) will terminate the future-seq."
  [fc]
  (let [a (atom fc)]
    (fn
      ([] (swap! a (fn [fc] (rest (deliver-stop fc)))))
      ([x] (swap! a (fn [fc] (rest (deliver-next fc x))))))))

(defn apply-when-realized
  "Returns a notifier which will receive the result of 
  (apply f r args) when r becomes realized."
  [r f & args]
  (let [n (notifier)]
    (register r (fn [v] (deliver n (apply f v args))))
    n))

(defmacro when-ready
  "Takes a vector of bindings and a body. Each binding is a pair
  consisting of a symbol (or destructuring form) and a notifier. When
  the notifier is realized, it will be bound to the symbol and body
  will be executed. The return value of body will be delivered to a
  notifier which is returned from when-ready."
  [bindings & body]
  {:pre [(even? (count bindings))]}
  (if (seq bindings)
    `(apply-when-realized ~(second bindings)
                          (fn [~(first bindings)]
                            (when-ready ~(drop 2 bindings)
                              ~@body)))
    `(do ~@body)))

(defn a-map [f ps]
  (future-seq
   (when-ready [s ps]
     (when-let [c (seq s)]
       (cons (f (first c))
             (a-map f (rest c)))))))

(defn a-filter [f ps]
  (future-seq
   (when-ready [s ps]
     (when-let [c (seq s)]
       (if (f (first c))
         (cons (first c)
               (a-filter f (rest c)))
         (a-filter f (rest c)))))))

(defn a-take [n ps]
  (future-seq
   (when-ready [s ps]
     (when-let [c (seq s)]
       (when (pos? n)
         (cons (first c) (a-take (dec n) (rest ps))))))))

;; Still TODO:
;; - better names
;; - chunked seqs
;; - exception handling
;; - registerable futures
;; - reduce
;; - cancellable registrations?
;; - implement java.lang.Future in notifier?

;; Maybe the queue should contain WeakReferences. Then if the result
;; sequence is not used, the notification never happens.

;; Local Variables:
;; mode: clojure
;; eval: (progn (define-clojure-indent (when-ready (quote defun))) (setq inferior-lisp-program "/Users/stuart/src/stuartsierra/cljque/run.sh"))
;; End:
