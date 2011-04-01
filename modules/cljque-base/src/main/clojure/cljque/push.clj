(ns cljque.push
  "'Push' sequence API, mirroring the API of Clojure's 'pull' sequences."
  (:use cljque.observe
        [cljque.schedule :only (delay-call periodic-send)] )
  (:refer-clojure :exclude (concat cycle delay distinct drop filter
                                   first map merge range rest take)))

;;; Error handling

(defn- make-agent [state observer]
  (agent state
         :error-handler (fn [agnt err]
                          (error observer err))
         :error-mode :fail))

;;; One-time event generators

(defn messages
  "Returns an observable which, when observed, invokes
  (message observer x) for each xs, in order, then invokes 
  (done observer)."
  [& xs]
  (reify Observable
    (observe [this observer]
      (let [a (make-agent xs observer)]
        (send a (fn thisfn [state]
                  (let [x (clojure.core/first xs)]
                    (when x
                      (message observer x)
                      (let [more (next xs)]
                        (if more
                          (send *agent* thisfn more)
                          (done observer)))))))
        (fn [] (send a (constantly nil)))))))

(def ^{:doc "An Observable which immediately signals done."}
  DONE
  (reify Observable
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
                            (done [this] (@unsub) (done observer)))))
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

(defn- update-multimap [r i m]
  (dosync
   (when @r
     (alter r update-in [i] conj m)
     (when (every? seq @r)
       (let [rv (clojure.core/map clojure.core/first @r)]
         (alter r (fn [state] (vec (clojure.core/map pop state))))
         rv)))))

(defn map
  "With two arguments, returns an Observable which genarates messages
  that are the result of calling f on each message from source.

  With more than two arguments, enqueues messages from sources.  As
  soon as all sources have generated at least one message, calls f
  with the first message from each source as arguments.  Then waits
  until all sources have generated at least one more message, and so
  on.  Note that if one source produces messages much faster than
  other sources, the queues could grow quite large."
  ([f source]
     (reify Observable
       (observe [this observer]
         (observe source
                  (reify Observer
                    (message [this m] (message observer (f m)))
                    (done [this] (done observer))
                    (error [this e] (error observer e)))))))
  ([f source & more]
     (auto-unsubscribe
      (reify Observable
        (observe [this observer]
          (let [sources (cons source more)
                r (ref (vec (repeat (count sources)
                                    clojure.lang.PersistentQueue/EMPTY)))
                unsubs (doall
                        (map-indexed
                         (fn [i source]
                           (observe source
                                    (reify Observer
                                      (message [this m]
                                        (when-let [v (update-multimap r i m)]
                                          (message observer (apply f v))))
                                      (error [this e]
                                        (error observer e))
                                      (done [this]
                                        (when (and @r (empty? (nth @r i)))
                                          (done observer))))))
                         sources))]
            (fn [] (doseq [u unsubs] (u)))))))))

(defn filter
  "Returns an Observable which relays messages from source for
  which (f message) is true."
  [f source]
  (reify Observable
    (observe [this observer]
      (observe source
               (reify Observer
                 (message [this m] (when (f m) (message observer m)))
                 (done [this] (done observer))
                 (error [this e] (error observer e)))))))

(defn drop
  "Returns an Observable which skips the first n messages from
  source, then relays all subsequent messages."
  [n source] {:pre [(<= 0 n)]}
  (reify Observable
    (observe [this observer]
      (let [a (atom n)]
        (observe source
                 (reify Observer
                   (message [this m]
                     (when (neg? (swap! a (fn [x] (if (neg? x) x (dec x)))))
                       (message observer m)))
                   (error [this e]
                     (error observer e))
                   (done [this]
                     (done observer))))))))

(defn take
  "Returns an Observable which relays the first n messages from
  source, then signals done."
  [n source] {:pre [(<= 0 n)]}
  (if (zero? n)
    DONE
    (auto-unsubscribe
     (reify Observable
       (observe [this observer]
         (let [a (atom n)]
           (observe source
                    (reify Observer
                      (message [this m]
                        (let [n (swap! a dec)]
                          (when (not (neg? n))
                            (message observer m))
                          (when (zero? n)
                            (done observer))))
                      (error [this e]
                        (error observer e))
                      (done [this]
                        (let [n (swap! a dec)]
                          (when (not (neg? n))
                            (done observer))))))))))))

(defn first-in
  "Returns an Observable which relays messages from the source
  which produces a message first, and only that source."
  [& sources]
  (reify Observable
    (observe [this observer]
      (let [unsubs (promise)
            a (make-agent -1 observer)]
        (deliver
         unsubs
         (doall
          (map-indexed
           (fn [i source]
             (observe source
                      (observer-agent
                       a
                       ;; on message:
                       (fn [state m]
                         (cond
                          (neg? state)
                          (do (message observer m)
                              (dorun (map-indexed
                                      (fn [j u]
                                        (when (not= j i) (u)))
                                      @unsubs))
                              i)
                          (= state i)
                          (do (message observer m)
                              state)
                          :else state))
                       ;; on done:
                       (fn [state m]
                         (when (= state i)
                           (done observer))
                         state))))
           sources)))
        (fn [] (doseq [u @unsubs] (u)))))))

;; Time-based event generators

(defn delay
  "For each message from source, generates the same message d units of
  time later."
  [d units source]
  (reify Observable
    (observe [this observer]
      (observe source
               (reify Observer
                 (message [this m]
                   (delay-call d units #(message observer m)))
                 (done [this]
                   (delay-call d units #(done observer)))
                 (error [this err]
                   (error observer err)))))))

(defn regular
  "Returns an Observable which generates messages pulled from sequence
  s, one every d units of time, after an initial delay of init units."
  [init d units s]
  (reify Observable
    (observe [this observer]
      (let [a (make-agent s observer)
            unsub (promise)]
        (periodic-send init d units a
                       (fn [state]
                         (when-let [x (clojure.core/first state)]
                           (message observer x)
                           (if-let [more (next state)]
                             more
                             (do (done observer) nil)))))))))
