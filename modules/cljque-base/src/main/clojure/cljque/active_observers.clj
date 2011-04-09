(ns cljque.active-observers)

(defprotocol Observable
  (observe [this observer]))

(defprotocol ObserverReturn
  (more? [this])
  (result [this]))

(defprotocol Observer
  (on-next [this event])
  (on-error [this err])
  (on-done [this]))

(defn observe-seq [s observer]
  (future  ;; later an ObservableFuture
    (loop [observer observer
           s s]
      (if (and s (more? observer))
        (if-let [s (try (seq s)
                        (catch Throwable t
                          (on-error observer t)
                          nil))]
          (recur (on-next observer (first s))
                 (rest s))
          (recur (on-done observer)
                 nil))
        (result observer)))))

(extend-protocol Observable
  clojure.lang.PersistentVector
  (observe [this observer]
    (observe-seq this observer)))

(deftype ObserverError [err]
  ObserverReturn
  (more? [this] false)
  (result [this] (throw err)))

(deftype ObserverResult [r]
  ObserverReturn
  (more? [this] false)
  (result [this] r))

(deftype DebugObserver []
  ObserverReturn
  (more? [this] true)
  (result [this] nil)
  Observer
  (on-next [this event] (prn :ON-NEXT event) this)
  (on-error [this err] (prn :ON-ERROR err) this)
  (on-done [this] (prn :ON-DONE) this))

(defn map [f observer]
  (reify
    ObserverReturn
    (more? [this] (more? observer))
    (result [this] (result observer))
    Observer
    (on-next [this event] (map f (on-next observer (f event))))
    (on-error [this err] (on-error observer err))
    (on-done [this] (on-done observer))))

(defn reduce [f seed]
  (reify
    ObserverReturn
    (more? [this] true)
    (result [this] seed)
    Observer
    (on-next [this event] (reduce f (f seed event)))
    (on-error [this err] (ObserverError. err))
    (on-done [this] (ObserverResult. seed))))

(defn take [n observer]
  (if (and (zero? n) (more? observer))
    (take -1 (on-done observer))
    (reify
      ObserverReturn
      (more? [this] (and (pos? n) (more? observer)))
      (result [this] (result observer))
      Observer
      (on-next [this event]
        (take (dec n) (on-next observer event)))
      (on-error [this err] (on-error observer err))
      (on-done [this] (on-done observer)))))
