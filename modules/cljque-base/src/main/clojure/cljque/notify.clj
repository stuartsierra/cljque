(ns cljque.notify)

(defprotocol IRegister
  (register [this f]
    "When this is realized, invokes f on this. Returns an IPending
    which receives the return value of f. f must must return an object
    of the same interface types as this and must not throw. If this
    already has a value, may invoke f synchronously."))

(deftype RealizedValue [v]
  clojure.lang.IPending
  (isRealized [this] true)
  clojure.lang.IDeref
  (deref [this] v)
  IRegister
  (register [this f]
    (f this)))

(deftype RegisteredFnError [t]
  clojure.lang.IPending
  (isRealized [this] true)
  clojure.lang.IDeref
  (deref [this] (throw t))
  IRegister
  (register [this f]
    (f this)))

(declare pending-promise)

(deftype PendingPromise [d v q]
  clojure.lang.IPending
  (isRealized [this] (zero? (.getCount d)))
  clojure.lang.IDeref
  (deref [this] (.await d) @v)
  clojure.lang.IFn
  (invoke [this x]
    (when-let [fs (dosync
                   (when @q
                     (let [qq @q]
                       (ref-set q nil)
                       (ref-set v x)
                       qq)))]
      (.countDown d)
      (doseq [f fs] (f this))
      this))
  IRegister
  (register [this f]
    (or (dosync (when @q
                  (let [p (pending-promise)]
                    (alter q conj #(deliver p @(f %)))
                    p)))
        (f this))))

(defn pending-promise []
  (let [d (java.util.concurrent.CountDownLatch. 1)
        v (ref nil)
        q (ref [])]
    (PendingPromise. d v q)))

(defn invoke-registered-fn [f vref]
  (try (RealizedValue. (f @vref))
       (catch Throwable t
         (RegisteredFnError. t))))

;;; Public API

(defn observe
  "Observe a (possibly pending) reference r. Returns a new pending
  reference.  When r is realized, invokes f on that value and sets the
  returned reference to f's return value.  If r already has a value,
  invokes f synchronously and returns its return value wrapped in a
  reference."
  [r f]
  (register r #(invoke-registered-fn f %)))
