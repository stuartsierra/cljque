(ns cljque.inotify)

(defprotocol Supply
  (supply [ipending value]))

(defprotocol Terminate
  (terminate [ipending]))

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


(declare active-seq)

(deftype ActiveSeq [latch q v]
  Register
  (register [this observer]
    (when-not (dosync (when @q
                        (alter q conj observer)))
      (when-let [n (notify observer this)]
        (register (rest this) n))
      this))
  Supply
  (supply [this x]
    (if-let [watchers (dosync (when @q
                                (ref-set v x)
                                (let [dq @q]
                                  (ref-set q nil)
                                  dq)))]
      (do (.countDown latch)
          (doseq [w watchers]
            (when-let [n (notify w this)]
              (register (rest this) n))))
      (supply (rest this) x))
    this)
  Terminate
  (terminate [this]
    (if-let [watchers (dosync (when @q
                                (ref-set v nil)
                                (let [dq @q]
                                  (ref-set q nil)
                                  dq)))]
      (do (.countDown latch)
          (doseq [w watchers]
            (notify w nil)))
      (terminate (rest this))))
  clojure.lang.IPending
  (isRealized [this]
    (zero? (.getCount latch)))
  clojure.lang.ISeq
  (first [this]
    (.await latch)
    (first @v))
  (more [this]
    (.await latch)
    (rest @v))
  (next [this]
    (seq (rest this)))
  (seq [this]
    (.await latch)
    (when @v
      (clojure.core/cons (clojure.core/first this)
                         (clojure.core/rest this))))
  (count [this]
    (clojure.core/count this))
  (cons [this o]
    (clojure.core/cons o this))
  (empty [this]
    (active-seq))
  (equiv [this that]
    (and (realized? this)
         (if (instance? clojure.lang.ISeq that)
           (= (seq this) (seq that))
           false))))

(defn active-seq []
  (let [latch (java.util.concurrent.CountDownLatch. 1)
        q (ref [])
        v (ref nil)]
    (ActiveSeq. latch q v)))

(defmethod clojure.core/print-method ActiveSeq [x writer]
  (.write writer (str "#<ActiveSeq "
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
    (supply @current (cons x (active-seq)))
    (swap! current
           (fn [state]
             (if (realized? state)
               (recur (rest state))
               state)))
    this))

(defn pump
  ([]
     (pump (active-seq)))
  ([s]
     {:pre [(instance? ActiveSeq s)]}
     (Pump. (atom s))))

(comment
 (deftype NextReceiver [f])

 (defn next-receiver [f]
   (NextReceiver. f))

 (defn sink-notifier [sink f]
   (reify Notify
     (notify [this that]
       (loop [xs (f that)]
         (when-first [x xs]
           (if (instance? NextReceiver x)
             (sink-notifier sink (.f x))
             (do (supply target x)
                 (recur (rest xs)))))))))

 (defn postpone-seq [source f]
   (let [target (active-seq)]
     (register source (target-seq-notifier target f))
     target)))
