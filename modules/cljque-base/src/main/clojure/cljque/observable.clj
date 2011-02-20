(ns cljque.observable
  (:use [cljque.schedule :only (delay-send periodic-send)] )
  (:refer-clojure :exclude (concat cycle delay distinct drop filter
                            first map merge range rest take)))

(defprotocol Observable
  (observe [this agnt on-message on-done]
    "When this Observable generates a message,
    calls (send agnt on-message this message)

    When this Observable is finished generating messages,
    calls (send agnt on-done this)

    Returns a no-arg function that cancels the subscription."))

;;; Actions without state

(defn nop [state & args] state)

(defn static-receiver [f]
  (fn [state source message] (f message) state))

;;; Subscription entry point

(defn subscribe
  "Subscribes to messages."
  ([observable f]
     (observe observable (agent nil) (static-receiver f) nop))
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
                     (send agnt on-message this new-value)))
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
                  (do (send agnt on-message this x)
                      (recur (next xs)))
                  (send agnt on-done this))))
            (catch Throwable t
              (send agnt (fn [state] (throw t))))))
        (fn [] (reset! continue false))))))

(defn seq-observable [observable]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        consumer (fn this []
                   (lazy-seq
                    (let [x (.take q)]
                      (when-not (= x ::done)
                        (cons x (this))))))]
    (subscribe observable
               (agent nil)
               (fn [state src message] (.put q message))
               (fn [state src] (.put q ::done)))
    (consumer)))

;;; One-time event generators

(defn value [x]
  (reify Observable
    (observe [this agnt on-message on-done]
      (send agnt on-message this x)
      (send agnt on-done this)
      (constantly nil))))

(defn never []
  (reify Observable
    (observe [this agnt on-message on-done]
      (send agnt on-done this)
      (constantly nil))))

;;; "Push" sequence API

(defn range
  ([]
     (reify Observable
       (observe [this agnt on-message on-done]
         (let [a (agent 0)]
           (send a
                 (fn thisfn [state]
                   (when state
                     (send agnt on-message this state)
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
         (let [a (agent start)]
           (send a
                 (fn thisfn [state]
                   (if (< state end)
                     (do (send agnt on-message this state)
                         (send *agent* thisfn)
                         (+ state step))
                     (send agnt on-done this))))
           (fn [] (send a (constantly end))))))))

(defn map [f source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (subscribe source agnt
                 (fn [state src message]
                   (on-message state src (f message)))))))

(defn filter [pred source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (subscribe source agnt
                 (fn [state src message]
                   (when (pred message)
                     (on-message state src message)))))))

(defn take [n source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [unsub (promise)]
        (deliver unsub
                 (subscribe source (agent n)
                            (fn [state src message]
                              (if (zero? state)
                                (do (@unsub)
                                    (send agnt on-done this))
                                (send agnt on-message this message))
                              (dec state))
                            (fn [_ _] (send agnt on-done this))))
        (fn [] (@unsub))))))

(defn drop [n source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [unsub (subscribe source (agent n)
                            (fn [state src message]
                              (if (zero? state)
                                (do (send agnt on-message this message)
                                    state)
                                (dec state)))
                            (fn [_ _] (send agnt on-done this) nil))]
        (fn [] (unsub))))))

(defn first [source]
  (take 1 source))

(defn rest [source]
  (drop 1 source))

(defn concat [& sources]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [a (agent (clojure.core/next sources))]
        (letfn [(on-msg [state src message]
                  (send agnt on-message this message)
                  state)
                (on-dn [state src]
                  (if state
                    (do (subscribe (clojure.core/first state) a on-msg on-dn)
                        (clojure.core/next state))
                    (send agnt on-done this)))]
          (subscribe (clojure.core/first sources) a on-msg on-dn))))))

(defn distinct [source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (subscribe source (agent #{})
                 (fn [state src message]
                   (if (contains? state message)
                     state
                     (do (send agnt on-message src message)
                         (conj state message))))
                 (fn [state src]
                   (send agnt on-done src)
                   nil)))))

(defn cycle [coll]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [v (vec coll)
            c (count coll)
            a (agent 0)]
        (send a (fn thisfn [state]
                  (when state
                   (send agnt on-message this (nth v state))
                   (send *agent* thisfn)
                   (mod (inc state) c))))
        (fn [] (send a (constantly false)))))))

;;; Changes in value

(defn transitions [source]
  (reify Observable
    (observe [this agnt on-message on-done]
      (subscribe source (agent ::unset)
                 (fn [state src message]
                   (send agnt on-message src [state message])
                   message)
                 (fn [state src]
                   (send agnt on-done this)
                   nil)))))

(defn changes [source]
  (filter #(apply not= %) (transitions source)))

(defn new-values [source]
  (map second (changes source)))

;;; Combining message streams

(defn gather [& sources]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [results (agent (vec (repeat (count sources) ::unset)))
            unsubs (promise)]
        (deliver unsubs
                 (doall
                  (map-indexed
                   (fn [i source]
                     (subscribe
                      (take 1 source) results
                      (fn [state src message]
                        (let [new-state (assoc state i message)]
                          (when (every? #(not= ::unset %) new-state)
                            (send agnt on-message this new-state))
                          new-state))
                      (fn [state src]
                        (when (= ::unset (nth state i))
                          (doseq [u @unsubs] (u))
                          (send agnt on-done this))
                        state)))
                   sources)))
        (fn [] (doseq [u @unsubs] (u)))))))

(defn merge [& sources]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [counter (agent (count sources))
            unsubs (doall
                    (map-indexed
                     (fn [i source]
                       (subscribe
                        source counter
                        (fn [state src message]
                          (send agnt on-message this message)
                          state)
                        (fn [state src]
                          (let [new-state (dec state)]
                            (when (zero? new-state)
                              (send agnt on-done this))
                            new-state))))
                     sources))]
        (fn [] (doseq [u unsubs] (u)))))))

;;; Scheduled messages

(defn delay [d units message]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [fut (delay-send d units agnt on-message this message)]
        (fn [] (.cancel fut false))))))

(defn periodic [init d units message]
  (reify Observable
    (observe [this agnt on-message on-done]
      (let [fut (periodic-send init d units agnt on-message this message)]
        (fn [] (.cancel fut false))))))

(defn timeout [d units source]
  (first (merge source (delay d units ::timeout))))
