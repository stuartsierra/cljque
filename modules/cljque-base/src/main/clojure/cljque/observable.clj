(ns cljque.observable
  (:use [cljque.schedule :only (delay-send periodic-send)] )
  (:refer-clojure :exclude (concat cycle delay distinct drop filter
                            first map merge range rest take)))

(defprotocol Observable
  (observe [this agnt on-message on-done]
    "When this Observable generates a message,
    calls (send agnt on-message message)

    When this Observable is finished generating messages,
    calls (send agnt on-done)

    Returns a no-arg function that cancels the subscription."))

;;; Error handling

(defn make-agent [state]
  (agent state
         :error-handler (fn [agnt err]
                          (.println *err* (str "Error on " (pr-str agnt)))
                          (.printStackTrace err *err*))
         :error-mode :fail))

;;; Actions without state

(defn nop [state & args] state)

(defn static-receiver [f]
  (fn [state message] (f message) state))

;;; Subscription entry point

(defn subscribe
  "Subscribes to messages."
  ([observable f]
     (observe observable (make-agent nil) (static-receiver f) nop))
  ([observable agnt on-message]
     (observe observable agnt on-message nop))
  ([observable agnt on-message on-done]
     (observe observable agnt on-message on-done)))

;;; Observable references

(defn observable-iref [iref]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [key (Object.)]
        (add-watch iref key
                   (fn [_ _ old-value new-value]
                     (send agnt on-message new-value)))
        (fn [] (remove-watch iref key))))))

;;; Observables and Sequences

(defn observable-seq [s]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [continue (atom true)]
        (future
          (try
            (loop [xs s]
              (when @continue
                (if-let [x (clojure.core/first xs)]
                  (do (send agnt on-message x)
                      (recur (next xs)))
                  (send agnt on-done))))
            (catch Throwable t
              (send agnt (fn [state] (throw t))))))
        (fn [] (reset! continue false))))))

(defn seq-observable [observable]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        consumer (fn thisfn []
                   (lazy-seq
                    (let [x (.take q)]
                      (when-not (= x ::done)
                        (cons x (thisfn))))))]
    (subscribe observable
               (make-agent nil)
               (fn [state message] (.put q message))
               (fn [state] (.put q ::done)))
    (consumer)))

;;; One-time event generators

(defn value [x]
  (reify Observable
    (observe [this agnt on-message on-done]
      (send agnt on-message x)
      (send agnt on-done)
      (constantly nil))))

(defn never []
  (reify Observable
    (observe [this agnt on-message on-done]
      (send agnt on-done)
      (constantly nil))))

;;; "Push" sequence API

(defn range
  ([]
     (reify Observable
       (observe [this agnt on-message on-done]
         (let [a (make-agent 0)]
           (send a
                 (fn thisfn [state]
                   (when state
                     (send agnt on-message state)
                     (send *agent* thisfn)
                     (inc state))))
           (fn [] (send a (constantly false)))))))
  ([end]
     (range 0 end 1))
  ([start end]
     (range start end 1))
  ([start end step]
     (reify Observable
       (observe [this agnt on-message on-done]
         (let [a (make-agent start)]
           (send a
                 (fn thisfn [state]
                   (if (< state end)
                     (do (send agnt on-message state)
                         (send *agent* thisfn)
                         (+ state step))
                     (send agnt on-done))))
           (fn [] (send a (constantly end))))))))

(defn map [f source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (subscribe source agnt
                 (fn [state message]
                   (on-message state (f message)))))))

(defn filter [pred source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (subscribe source agnt
                 (fn [state message]
                   (when (pred message)
                     (on-message state message)))))))

(defn take [n source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [unsub (promise)]
        (deliver unsub
                 (subscribe source (make-agent n)
                            (fn [state message]
                              (if (zero? state)
                                (do (@unsub)
                                    (send agnt on-done))
                                (send agnt on-message message))
                              (dec state))
                            (fn [state] (send agnt on-done) nil)))
        (fn [] (@unsub))))))

(defn drop [n source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [unsub (subscribe source (make-agent n)
                            (fn [state message]
                              (if (zero? state)
                                (do (send agnt on-message message)
                                    state)
                                (dec state)))
                            (fn [state] (send agnt on-done) nil))]
        (fn [] (unsub))))))

(defn first [source]
  (take 1 source))

(defn rest [source]
  (drop 1 source))

(defn concat [& sources]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [a (make-agent (clojure.core/next sources))]
        (letfn [(on-msg [state message]
                  (send agnt on-message message)
                  state)
                (on-dn [state]
                  (if state
                    (do (subscribe (clojure.core/first state) a on-msg on-dn)
                        (clojure.core/next state))
                    (send agnt on-done)))]
          (subscribe (clojure.core/first sources) a on-msg on-dn))))))

(defn distinct [source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (subscribe source (make-agent #{})
                 (fn [state message]
                   (if (contains? state message)
                     state
                     (do (send agnt on-message message)
                         (conj state message))))
                 (fn [state]
                   (send agnt on-done)
                   nil)))))

(defn cycle [coll]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [v (vec coll)
            c (count coll)
            a (make-agent 0)]
        (send a (fn thisfn [state]
                  (when state
                   (send agnt on-message (nth v state))
                   (send *agent* thisfn)
                   (mod (inc state) c))))
        (fn [] (send a (constantly false)))))))

;;; Changes in value

(defn transitions [source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (subscribe source (make-agent ::unset)
                 (fn [state message]
                   (send agnt on-message [state message])
                   message)
                 (fn [state]
                   (send agnt on-done)
                   nil)))))

(defn changes [source]
  (filter #(apply not= %) (transitions source)))

(defn new-values [source]
  (map second (changes source)))

;;; Combining message streams

(defn gather [& sources]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [results (make-agent (vec (repeat (count sources) ::unset)))
            unsubs (promise)]
        (deliver unsubs
                 (doall
                  (map-indexed
                   (fn [i source]
                     (subscribe
                      (take 1 source) results
                      (fn [state message]
                        (let [new-state (assoc state i message)]
                          (when (every? #(not= ::unset %) new-state)
                            (send agnt on-message new-state))
                          new-state))
                      (fn [state]
                        (when (= ::unset (nth state i))
                          (doseq [u @unsubs] (u))
                          (send agnt on-done))
                        state)))
                   sources)))
        (fn [] (doseq [u @unsubs] (u)))))))

(defn merge [& sources]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [counter (make-agent (count sources))
            unsubs (doall
                    (map-indexed
                     (fn [i source]
                       (subscribe
                        source counter
                        (fn [state message]
                          (send agnt on-message message)
                          state)
                        (fn [state]
                          (let [new-state (dec state)]
                            (when (zero? new-state)
                              (send agnt on-done))
                            new-state))))
                     sources))]
        (fn [] (doseq [u unsubs] (u)))))))

;;; Scheduled messages

(defn delay [d units message]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [fut (delay-send d units agnt on-message message)]
        (fn [] (.cancel fut false))))))

(defn periodic [init d units message]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [fut (periodic-send init d units agnt on-message message)]
        (fn [] (.cancel fut false))))))

(defn timeout [d units source]
  (first (merge source (delay d units ::timeout))))
