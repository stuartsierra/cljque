(ns cljque.push
  "'Push' sequence API, mirroring the API of Clojure's 'pull' sequences."
  (:use cljque.observe
        [cljque.schedule :only (delay-send periodic-send)] )
  (:refer-clojure :exclude (concat cycle delay distinct drop filter
                            first map merge range rest take)))

;;; Error handling

(defn make-agent [state observer]
  (agent state
         :error-handler (fn [agnt err]
                          (error observer err))
         :error-mode :fail))

(defn agent-observer [a on-message on-done]
  (reify Observer
    (message [this m] (send a on-message m))
    (done [this] (send a on-done))
    (error [this e] (send a (fn [_] (throw e))))))

;;; One-time event generators

(defn messages
  "Returns an observable which, when observed, synchronously invokes
  (message observer x) for each xs, in order, then invokes 
  (done observer).  The returned unsubscribe function has no effect."
  [& xs]
  (reify Observable
    (observe [this observer]
      (doseq [x xs]
        (message observer x))
      (done observer)
      (constantly nil))))

;;; "Push" sequence API

(defn range
  "Returns an observable which, when observed, generated a series of
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
