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

