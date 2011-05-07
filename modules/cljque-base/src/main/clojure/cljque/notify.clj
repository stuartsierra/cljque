(ns cljque.notify)

(defprotocol Observable
  (observe [this observer]))

(defprotocol Feeder
  (feed [this observable]))

(declare invoke-observer)

(deftype ObservableValue [v]
  clojure.lang.IPending
  (isRealized [this] true)
  clojure.lang.IDeref
  (deref [this] v)
  Observable
  (observe [this observer]
    (invoke-observer observer this)))

(deftype ObservableError [t]
  clojure.lang.IPending
  (isRealized [this] true)
  clojure.lang.IDeref
  (deref [this] (throw t))
  Observable
  (observe [this observer]
    (invoke-observer observer this)))

(defn invoke-observer [f observable]
  (try (ObservableValue. (f observable))
       (catch Throwable t
         (ObservableError. t))))

(declare pending-value)

(defn register-observer [qref vref observer]
  (or (dosync (when @qref
                (let [pv (pending-value)
                      f (fn [v] (feed pv (invoke-observer observer v)))]
                  (alter qref conj f)
                  pv)))
      (invoke-observer observer @vref)))

(defn notify-observers [qref vref]
  (doseq [f (dosync (let [q @qref] (ref-set qref nil) q))]
    (f @vref)))

(deftype PendingValue [vpromise qref]
  clojure.lang.IPending
  (isRealized [this] (.isRealized vpromise))
  clojure.lang.IDeref
  (deref [this] @@vpromise)
  Observable
  (observe [this observer]
    (register-observer qref vpromise observer))
  Feeder
  (feed [this observable]
    (deliver vpromise observable)
    (notify-observers qref vpromise)))

;;; Public API

(defn pending-value
  "Returns a new pending value, which is similar to a promise but can
  be subscribed to."
  []
  (PendingValue. (promise) (ref [])))

(defn deliver-value
  "Deliver a value to a pending-value. Only works once; future calls
  to the same pending-value are no-ops."
  [pend x]
  (feed pend (ObservableValue. x)))

(defn observe-value
  "Observe a (possibly pending) value pv. Returns a new PendingValue
  or ObservableValue.  When pv gets a value, invokes f on that value
  and updates the returned PendingValue.  If pv already has a value,
  invokes f synchronously."
  [pv f]
  (observe pv (comp f deref)))
