;; -*- mode: clojure; eval: (define-clojure-indent (continue 'defun)) -*-
(ns cljque.active-observers
  (:refer-clojure :exclude (map filter take drop reduce merge)))

;;; Core protocols

(defprotocol Observable
  (observe [this observer]
    "On a separate thread, calls (on-next observer this) for
    successive values from this object. Calls (on-next observer nil)
    when there are no more values. Returns an ObservableFuture for the
    result of the observation."))

(defprotocol ObservableCurrent
  (current [this]
    "Returns the current value of this Observable."))

(defprotocol Observer
  (on-next [this observable]
    "Processes a single event from observable.  Returns a new Observer
    to handle the next event."))

(defprotocol ObserverMore
  (more? [this]
    "True if this Observer can accept more events from the Observable."))

(defprotocol ObserverResult
  (result [this]
    "The return value of an Observer."))

(extend-type nil
  Observer
  (on-next [this observable] nil)
  ObserverMore
  (more? [this] false)
  ObserverResult
  (result [this] nil)
  Observable
  (observe [this observer]
    (when (more? observer)
      (on-next observer nil))
    nil)
  ObservableCurrent
  (current [this] nil))

;;; Observable events

(deftype ObservableError [err]
  Observable
  (observe [this observer]
    (when (more? observer)
      (on-next observer this))
    this)
  ObservableCurrent
  (current [this] (throw err)))

(deftype ObservableEvent [event observable]
  Observable
  (observe [this observer]
    (observe observable observer))
  ObservableCurrent
  (current [this] event))

(defn event 
  "Returns an Observable representing a single event from the source
  observable."
  [current source]
  (ObservableEvent. current source))

;;; Observable Futures

(defn notification-queue []
  (ref []))

(defn notify [q value]
  (doseq [recipient (dosync (let [qq @q] (ref-set q nil)))]
    (recipient value)))

(defn add-recipient [q x]
  (dosync (if (nil? @q)
            false
            (do (alter q conj x)
                true))))

(deftype ObservedPromise [p observer]
  clojure.lang.IFn
  (invoke [this value]
    (deliver p (result (on-next observer this))))
  (observe [this]))

(deftype ObservablePromise [p q]
  clojure.lang.IFn
  (invoke [this value]
    (deliver p value)
    (doseq [xs (dosync (let [x @q] (ref-set q nil) x))]
      (deliver xs value)))
  clojure.lang.IDeref
  (deref [this] @p)
  Observable
  (observe [this observer]
    (let [op (ObservablePromise. (promise) (ref []))])
    (if (nil? (dosync (alter q #(if (nil? %) nil (conj % op)))))
      ()))
  ObservableCurrent
  (current [this] @p))

(defn observable-promise []
  (ObservablePromise. (promise) (ref [])))

(defn observable-future-call [f]
  (let [op (observable-promise)]
    (future
      (let [result (try (f)
                        (catch Throwable t t))]
        (deliver op result)))
    op))

(defmacro observable-future [& body]
  `(observable-future-call (fn [] ~@body)))

;;; Observers and their return values

(deftype ObserverError [err]
  ObserverMore
  (more? [this] false)
  ObserverResult
  (result [this] (throw err)))

(deftype ObserverReturn [r]
  ObserverMore
  (more? [this] false)
  ObserverResult
  (result [this] r))

(deftype ObserverFunction [f]
  ObserverMore
  (more? [this] true)
  Observer
  (on-next [this observable]
    (try (f observable)
         (catch Throwable t
           (ObserverError. t)))))

(defn continue-with
  "Returns a Observer which accepts new events and invokes f on the
  next event. Catches exceptions thrown by f and replaces them with
  ObserverError."
  [f]
  (ObserverFunction. f))

(defmacro continue
  "Returns an Observer which accepts new events. argv is the argument
  vector for a single-argument function to handle the next event; body
  is the body of that function. Catches exceptions thrown in body and
  replaces them with ObserverError."
  [argv & body]
  {:pre [(vector? argv) (= 1 (count argv))]}
  `(continue-with
    (fn ~argv ~@body)))

(defn return 
  "Returns an Observer which does not accept new events and has the
  return value of result."
  [result]
  (ObserverReturn. result))

(defn finish
  "Sends a nil event to observer and returns its result value."
  [observer]
  (return (result (on-next observer nil))))

;;; Observable sequences

(defn observe-seq [s observer]
  (future  ;; later an ObservableFuture
    (loop [s s
           observer observer]
      (if (more? observer)
        (let [s (try (seq s)
                     (catch Throwable t
                       (ObservableError. t)))]
          (if (or (nil? s) (instance? ObservableError s))
            (result (on-next observer s))
            (recur (rest s) (on-next observer s))))
        (result observer)))))

(doseq [c [clojure.lang.Cons
           clojure.lang.PersistentList
           clojure.lang.PersistentList$EmptyList
           clojure.lang.LazySeq
           clojure.lang.Range
           clojure.lang.ChunkedCons]]
  (extend c Observable {:observe observe-seq})
  (extend c ObservableCurrent {:current first}))

;;; Observer combinator library

(defn map [f observer]
  (continue [observable]
    (if observable
      (map f (on-next observer (event (f (current observable)) observable)))
      (finish observer))))

(defn take [n observer]
  (if (zero? n)
    (when (more? observer) (finish observer))
    (continue [observable]
      (if observable
        (take (dec n) (on-next observer observable))
        (finish observer)))))

(defn drop [n observer]
  (if (zero? n)
    observer
    (continue [observable]
      (if observable
        (drop (dec n) observer)
        (finish observer)))))

(defn filter [f observer]
  (continue [observable]
    (if observable
      (if (f (current observable))
        (filter f (on-next observer observable))
        (filter f observer))
      (finish observer))))

(defn reduce [f seed]
  (continue [observable]
    (if observable
      (reduce f (f seed (current observable)))
      (return seed))))

;;; Merged Observables; still needs work

(deftype MergeObserver [n merged-observable observer]
  ObserverMore
  (more? [this] (more? observer))
  ObserverResult
  (result [this] (result observer))
  Observer
  (on-next [this observable]
    (if observable
      (MergeObserver. n
                      merged-observable
                      (on-next observer
                               (try (event (current observable)
                                           merged-observable)
                                    (catch Throwable t
                                      (ObservableError. t)))))
      (if (zero? (swap! n dec))
        (finish observer)
        this))))

(deftype MergeObservable [sources]
  Observable
  (observe [this observer]
    (let [n (atom (count sources))]
      (doseq [source sources]
        (observe source (MergeObserver. n this observer))))
    this))

(defn merge [& sources]
  (MergeObservable. sources))

;;; Debugging & testing

(let [a (agent nil)]
  (defn safe-prn [& args]
    (send-off a (fn [_] (apply prn args)))
    nil))

(deftype DebugObserver []
  ObserverMore
  (more? [this] true)
  ObserverResult
  (result [this] nil)
  Observer
  (on-next [this observable]
    (if observable
      (safe-prn :ON-NEXT (try (current observable)
                         (catch Throwable t t)))
      (safe-prn :DONE))
    this))