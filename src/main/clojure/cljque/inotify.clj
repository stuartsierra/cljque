(ns cljque.inotify)

(defprotocol Supply
  (supply [ipending value]))

(defprotocol Notify
  (notify [observer ipending]))

(defprotocol Register
  (register [ipending observer]))

(defn active-promise []
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        q (ref [])
        v (ref nil)]
    (reify
      clojure.lang.IPending
      (isRealized [this]
        (zero? (.getCount latch)))
      clojure.lang.IDeref
      (deref [this]
        (.await latch)
        @v)
      Register
      (register [this observer]
        (when-not (dosync (when @q
                            (alter q conj observer)))
          (notify observer this)
          this))
      Supply
      (supply [this x]
        (when-let [watchers (dosync (when @q
                                      (ref-set v x)
                                      (let [dq @q]
                                        (ref-set q nil)
                                        dq)))]
          (.countDown latch)
          (doseq [w watchers]
            (notify w this))
          this)))))

(comment
 (defn postpone [ipending f & args]
   (let [p (active-promise)]
     (register ipending
               (reify Notify
                 (notify [this that]
                   (supply p (apply f @that args)))))
     p)))

(defn postpone [source f & args]
  (let [a (atom nil)]
    (register source (reify Notify
                       (notify [_ that]
                         (reset! a (apply f @that args)))))
    (reify
      clojure.lang.IPending
      (isRealized [this]
        (realized? source))
      clojure.lang.IDeref
      (deref [this]
        (deref source) ;; blocks
        (deref a))
      Register
      (register [this observer]
        (register source (reify Notify
                           (notify [_ _]
                             (notify observer this))))
        this))))


(declare proseq)

(deftype PromisedSeq [latch q v]
  Register
  (register [this observer]
    (when-not (dosync (when @q
                        (alter q conj observer)))
      (notify observer this))
    this)
  Supply
  (supply [this x]
    (when-let [watchers (dosync (when @q
                                  (ref-set v x)
                                  (let [dq @q]
                                    (ref-set q nil)
                                    dq)))]
      (.countDown latch)
      (doseq [w watchers]
        (notify w this)))
    this)
  clojure.lang.IPending
  (isRealized [this]
    (zero? (.getCount latch)))
  clojure.lang.Seqable
  (seq [this]
    (.await latch)
    (seq @v))
  clojure.lang.ISeq
  (first [this]
    (first (seq this)))
  (more [this]
    (rest (seq this)))
  (next [this]
    (seq (rest this)))
  (count [this]
    (count (seq this)))
  (cons [this o]
    (clojure.lang.Cons. o this))
  (empty [this]
    (proseq))
  (equiv [this that]
    (and (realized? this)
         (instance? clojure.lang.ISeq that)
         (= (seq this) (seq that)))))

(defn proseq
  "Returns a proseq (promised seq). Call supply to provide it with
  an ISeq."
  []
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        q (ref [])
        v (ref nil)]
    (PromisedSeq. latch q v)))

(defmethod clojure.core/print-method PromisedSeq [x writer]
  (.write writer (str "#<PromisedSeq "
                      (if (realized? x)
                        (first x)
                        :pending)
                      ">")))

(deftype Pump [current]
  Register
  (register [this inotify]
    (register @current inotify))
  Supply
  (supply [this x]
    (if (nil? x)
      (supply @current nil)
      (do
        (supply @current (cons x (proseq)))
        (swap! current
               (fn [state]
                 ;; Skip realized elements until we reach nil or pending.
                 (if (and state (realized? state))
                   (recur (rest state))
                   state)))))
    this)
  clojure.lang.IDeref
  (deref [this]
    @current))

(defn pump
  ([]
     (pump (proseq)))
  ([s]
     {:pre [(instance? PromisedSeq s)]}
     (Pump. (atom s))))

(defmethod clojure.core/print-method Pump [x writer]
  (.write writer "#<Pump>"))
