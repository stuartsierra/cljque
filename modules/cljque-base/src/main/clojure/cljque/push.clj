(ns cljque.push
  "'Push' sequence API, mirroring the API of Clojure's 'pull' sequences."
  (:use cljque.observe
        [cljque.schedule :only (delay-send periodic-send)] )
  (:refer-clojure :exclude (concat cycle delay distinct drop filter
                                   first map merge range rest take)))

;;; Debugging

(def debug-observer
  (reify Observer
    (message [this x] (prn x))
    (done [this] (prn 'DONE))
    (error [this e] (prn e))))

(def debug-observable
  (reify Observable
    (observe [this observer]
      (prn "Observer subscribed")
      (future
        (message observer 0)
        ;(Thread/sleep 100)
        (message observer 1)
        ;(Thread/sleep 100)
        (message observer 2)
        ;(Thread/sleep 100)
        (message observer 3) 
        (done observer))
      (fn [] (prn "Observer unsubscribed")))))

;;; Error handling

(defn make-agent [state observer]
  (agent state
         :error-handler (fn [agnt err]
                          (error observer err))
         :error-mode :fail))

(defn observer-agent [a on-message on-done]
  (reify Observer
    (message [this m] (send a on-message m))
    (done [this] (send a on-done))
    (error [this e] (send a (fn [_] (throw e))))))

;;; One-time event generators

(defn messages
  "Returns an observable which, when observed, invokes
  (message observer x) for each xs, in order, then invokes 
  (done observer)."
  [& xs]
  (observable-seq xs))

(def Never (reify Observable
             (observe [this observer]
               (send (agent nil) (fn [_] (done observer)))
               (constantly nil))))

;;; Internals

(defn auto-unsubscribe [source]
  "Wraps Observable source in an Observable which automatically
  invokes its unsubscribe function when it signals `done`."
  (reify Observable
    (observe [this observer]
      (let [unsub (promise)]
        (deliver unsub
                 (observe source
                          (reify Observer
                            (message [this m] (message observer m))
                            (error [this err] (error observer err))
                            (done [this] (done observer) (@unsub)))))
        (fn [] (@unsub))))))

;;; "Push" sequence API

(defn range
  "Returns an observable which, when observed, generates a series of
  messages like clojure.core/range."
  ([]
     (reify Observable
       (observe [this observer]
         (let [a (make-agent 0 observer)]
           (send a
                 (fn thisfn [state]
                   (when state
                     (message observer state)
                     (send *agent* thisfn)
                     (inc state))))
           (fn [] (send a (constantly false)))))))
  ([end]
     (range 0 end 1))
  ([start end]
     (range start end 1))
  ([start end step]
     (reify Observable
       (observe [this observer]
         (let [a (make-agent start observer)]
           (send a
                 (fn thisfn [state]
                   (if (< state end)
                     (do (message observer state)
                         (send *agent* thisfn)
                         (+ state step))
                     (done observer))))
           (fn [] (send a (constantly end))))))))

(defn- multimap-message [state observer f i m]
  (when state
    (let [state (update-in state [i] conj m)]
      (if (every? seq state)
        (do (message observer (apply f (clojure.core/map clojure.core/first state)))
            (vec (clojure.core/map pop state)))
        state))))

(defn- multimap-done [state observer i]
  (when state
    (if (empty? (nth state i))
      (do (done observer) nil)
      state)))

(defn map
  ([f source]
     (reify Observable
       (observe [this observer]
         (observe source
                  (reify Observer
                    (message [this m] (message observer (f m)))
                    (done [this] (done observer))
                    (error [this e] (error observer e)))))))
  ([f source & more]
     (let [sources (cons source more)]
       (auto-unsubscribe
        (reify Observable
          (observe [this observer]
            (let [a (make-agent (vec
                                 (repeat
                                  (count sources)
                                  clojure.lang.PersistentQueue/EMPTY))
                                observer)
                  unsubs (doall
                          (map-indexed
                           (fn [i source]
                             (observe
                              source
                              (reify Observer
                                (message [this m]
                                  (send a multimap-message observer f i m))
                                (done [this]
                                  (send a multimap-done observer i))
                                (error [this err]
                                  (error observer err)))))
                           sources))]
              (fn [] (doseq [u unsubs] (u))))))))))

(defn filter [f source]
  (reify Observable
    (observe [this observer]
      (observe source
               (reify Observer
                 (message [this m] (when (f m) (message observer m)))
                 (done [this] (done observer))
                 (error [this e] (error observer e)))))))

(defn drop [n source]
  {:pre [(<= 0 n)]}
  (reify Observable
    (observe [this observer]
      (observe source
               (observer-agent
                (make-agent n observer)
                ;; on message:
                (fn [state m]
                  (when (zero? state)
                    (message observer m))
                  (if (pos? state)
                    (dec state)
                    state))
                ;; on done:
                (fn [state]
                  (when (not (neg? state))
                    (done observer))
                  -1))))))

(defn take [n source]
  {:pre [(<= 0 n)]}
  (if (zero? n)
    Never
    (auto-unsubscribe
     (reify Observable
       (observe [this observer]
         (observe source
                  (observer-agent
                   (make-agent n observer)
                   ;; on message:
                   (fn [state m]
                     (if (pos? state)
                       (do (message observer m)
                           (when (= 1 state) (done observer))
                           (dec state))
                       state))
                   ;; on done:
                   (fn [state]
                     (when (pos? state)
                       (done observer))
                     -1))))))))