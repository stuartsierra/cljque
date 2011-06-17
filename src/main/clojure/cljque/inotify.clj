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

(defn apply-when-realized
  "Returns a notifier which will receive the result of 
  (apply f @r args) when r becomes realized."
  [r f & args]
  (let [n (notifier)]
    (register r (fn [v] (deliver n (apply f @v args))))
    n))

;; (declare proseq)

;; (deftype PromisedSeq [latch q v]
;;   clojure.lang.IFn
;;   (invoke [this x]
;;     (when-let [watchers (dosync (when @q
;;                                   (ref-set v x)
;;                                   (let [dq @q]
;;                                     (ref-set q nil)
;;                                     dq)))]
;;       (.countDown latch)
;;       (doseq [w watchers]
;;         (w this)))
;;     x)
;;   Register
;;   (register [this f]
;;     (when-not (dosync (when @q
;;                         (alter q conj f)))
;;       (f this))
;;     this)
;;   clojure.lang.IPending
;;   (isRealized [this]
;;     (zero? (.getCount latch)))
;;   clojure.lang.Seqable
;;   (seq [this]
;;     (.await latch)
;;     (seq @v))
;;   clojure.lang.ISeq
;;   (first [this]
;;     (first (seq this)))
;;   (more [this]
;;     (rest (seq this)))
;;   (next [this]
;;     (seq (rest this)))
;;   (count [this]
;;     (count (seq this)))
;;   (cons [this o]
;;     (clojure.lang.Cons. o this))
;;   (empty [this]
;;     (proseq))
;;   (equiv [this that]
;;     (and (realized? this)
;;          (instance? clojure.lang.ISeq that)
;;          (= (seq this) (seq that)))))

;; (defn proseq
;;   "Returns a proseq (promised seq). Call supply to provide it with
;;   an ISeq."
;;   []
;;   (let [latch (java.util.concurrent.CountDownLatch. 1)
;;         q (ref [])
;;         v (ref nil)]
;;     (PromisedSeq. latch q v)))

;; (defmethod clojure.core/print-method PromisedSeq [x writer]
;;   (.write writer (str "#<PromisedSeq "
;;                       (if (realized? x)
;;                         (first x)
;;                         :pending)
;;                       ">")))
;; (comment
;;  (deftype Pump [current]
;;    Register
;;    (register [this f]
;;      (register @current f))
;;    Supply
;;    (supply [this x]
;;      (if (nil? x)
;;        (supply @current nil)
;;        (do
;;          (supply @current (cons x (proseq)))
;;          (swap! current
;;                 (fn [state]
;;                   ;; Skip realized elements until we reach nil or pending.
;;                   (if (and state (realized? state))
;;                     (recur (rest state))
;;                     state)))))
;;      this)
;;    clojure.lang.IDeref
;;    (deref [this]
;;      @current)))

;; (defn pump
;;   ([]
;;      (pump (proseq)))
;;   ([s]
;;      {:pre [(instance? PromisedSeq s)]}
;;      (Pump. (atom s))))

;; (defmethod clojure.core/print-method Pump [x writer]
;;   (.write writer "#<Pump>"))

;; ;; SiphonedSeq does not implement Supply, so you can't inject values
;; ;; into it.
;; (deftype SiphonedSeq [p]
;;   Register
;;   (register [this that] (register p that))
;;   clojure.lang.IPending
;;   (isRealized [this] (realized? p))
;;   clojure.lang.Seqable
;;   (seq [this] (seq p))
;;   clojure.lang.ISeq
;;   (first [this] (first p))
;;   (more [this] (rest p))
;;   (next [this] (next p))
;;   (count [this] (count p))
;;   (cons [this that] (cons p that))
;;   (empty [this] (empty p))
;;   (equiv [this that] (.equiv p that)))

;; (defn siphon* [f s]
;;   (if (realized? s)
;;     (f s))
;;   (let [p (proseq)]
;;     (register s (fn [that]
;;                   (supply p (f that))))
;;     (SiphonedSeq. p)))

;; (defmacro siphon [bindings & body]
;;   {:pre [(vector? bindings) (= 2 (count bindings))]}
;;   `(siphon* (fn [~(first bindings)] ~@body) ~(second bindings)))

;; (defn a-map [f ps]
;;   (siphon [s ps]
;;     (when-let [c (seq s)]
;;       (cons (f (first c))
;;             (a-map f (rest c))))))

;; (defn a-filter [f ps]
;;   (siphon [s ps]
;;     (when-let [c (seq s)]
;;       (if (f (first c))
;;         (cons (first c)
;;               (a-filter f (rest c)))
;;         (a-filter f (rest c))))))

;; (defn a-take [n ps]
;;   (siphon [s ps]
;;     (when-let [c (seq s)]
;;       (when (pos? n)
;;         (cons (first c) (a-take (dec n) (rest ps)))))))

;; ;;; Maybe the queue should contain WeakReferences. Then if the result
;; ;;; sequence is not used, the notification never happens.

;; ;; Local Variables:
;; ;; mode: clojure
;; ;; eval: (define-clojure-indent (siphon (quote defun)))
;; ;; End:
