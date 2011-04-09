;; -*- mode: clojure; eval: (define-clojure-indent (continue 'defun)) -*-
(ns cljque.active-observers
  (:refer-clojure :exclude (map filter take drop reduce)))

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

(defprotocol Observation
  (current [this])
  (done? [this]))

(deftype ObservationEvent [event]
  Observation
  (current [this] event)
  (done? [this] false))

(deftype ObservationError [err]
  Observation
  (current [this] (throw err))
  (done? [this] false))

(deftype ObservationDone []
  Observation
  (current [this] nil)
  (done? [this] true))

(deftype ObserverFunction [f]
  ObserverReturn
  (more? [this] true)
  (result [this] nil)
  Observer
  (on-next [this event]
    (try (f (ObservationEvent. event))
         (catch Throwable t
           (ObserverError. t))))
  (on-error [this err]
    (try (f (ObservationError. err))
         (catch Throwable t
           (ObserverError. t))))
  (on-done [this]
    (try (f (ObservationDone.))
         (catch Throwable t
           (ObserverError. t)))))

(defn continue-with [f]
  (ObserverFunction. f))

(defmacro continue [argv & body]
  `(continue-with
    (fn ~argv ~@body)))

(defn finish [result]
  (ObserverResult. result))

(defn stop [observer]
  (stop (result (on-done observer))))

(defn map [f observer]
  (continue [observation]
    (if (done? observation)
      (stop observer)
      (map f (on-next observer (f (current observation)))))))

(defn take [n observer]
  (if (zero? n)
    (stop observer)
    (continue [observation]
      (if (done? observation)
        (stop observer)
        (take (dec n) (on-next observer (current observation)))))))

(defn drop [n observer]
  (if (zero? n)
    observer
    (continue [observation]
      (if (done? observation)
        (stop observer)
        (drop (dec n) observer)))))

(defn filter [f observer]
  (continue [observation]
    (if (done? observation)
      (stop observer)
      (if (f (current observation))
        (filter f (on-next observer (current observation)))
        (filter f observer)))))

(defn reduce [f seed]
  (continue [observation]
    (if (done? observation)
      (finish seed)
      (reduce f (f seed (current observation))))))