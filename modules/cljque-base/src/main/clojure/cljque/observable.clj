(ns cljque.observable
  (:use [cljque.schedule :only (delay-send periodic-send)] )
  (:refer-clojure :exclude (delay drop filter first map merge rest take)))

(defprotocol Observable
  (subscribe [this agnt action]
    "Subscribes to messages.  When this Observable generates a
    message, calls (send agnt action this message).
    Returns a no-arg function that cancels the subscription."))

;;; Actions without state

(defn static-receiver [f]
  (fn [state source message] (f message) state))

(defn subscribe-static
  "Subscribes to messages. When observable generates a message,
  calls (f message).  Returns a no-arg function that cancels the
  subscription."
  [observable f]
  (subscribe observable (agent nil) (static-receiver f)))

;;; Observable references

(defn observable-iref [iref]
  (reify Observable
    (subscribe [this agnt action]
      (let [key (Object.)]
        (add-watch iref key
                   (fn [_ _ old-value new-value]
                     (send agnt action this new-value)))
        (fn [] (remove-watch iref key))))))

;;; Observables and Sequences

(defn observable-seq [s]
  (reify Observable
    (subscribe [this agnt action]
      (let [continue (atom true)]
        (future
          (try
            (loop [xs s]
              (when @continue
                (if-let [x (clojure.core/first xs)]
                  (do (send agnt action this x)
                      (recur (next xs)))
                  (send agnt action this ::done))))
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
    (subscribe-static observable #(.put q %))
    (consumer)))

;;; Combinators

(defn value [x]
  (reify Observable
    (subscribe [this agnt action]
      (send agnt action x)
      (send agnt action ::done)
      (constantly nil))))

(defn never []
  (reify Observable
    (subscribe [this agnt action]
      (send agnt action ::done)
      (constantly nil))))

(defn map [f source]
  (reify Observable
    (subscribe [this agnt action]
      (subscribe source agnt
                 (fn [state src message]
                   (action state src (f message)))))))

(defn filter [pred source]
  (reify Observable
    (subscribe [this agnt action]
      (subscribe source agnt
                 (fn [state src message]
                   (when (pred message)
                     (action state src message)))))))

(defn take [n source]
  (reify Observable
    (subscribe [this agnt action]
      (let [unsub (promise)]
        (deliver unsub
                 (subscribe source (agent n)
                            (fn [state src message]
                              (if (zero? state)
                                (do (@unsub)
                                    (send agnt action src ::done))
                                (send agnt action src message))
                              (dec state))))
        (fn [] (@unsub))))))

(defn drop [n source]
  (reify Observable
    (subscribe [this agnt action]
      (let [unsub (promise)]
        (deliver unsub
                 (subscribe source (agent n)
                            (fn [state src message]
                              (if (zero? state)
                                (send agnt action src message)
                                (dec state)))))
        (fn [] (@unsub))))))

(defn first [source]
  (take 1 source))

(defn rest [source]
  (drop 1 source))

(defn gather [& sources]
  (reify Observable
    (subscribe [this agnt action]
      (let [results (agent (vec (repeat (count sources) ::unset)))
            unsubs (doall
                    (map-indexed
                     (fn [i source]
                       (subscribe
                        (take 1 source)
                        results
                        (fn [state src message]
                          (let [new-state (assoc state i message)]
                            (when (every? #(not= ::unset %) new-state)
                              (send agnt this new-state))
                            new-state))))
                     sources))]
        (fn [] (doseq [u unsubs] (u)))))))

(defn merge [& sources]
  (reify Observable
    (subscribe [this agnt action]
      (let [done (agent (vec (repeat (count sources) false)))
            unsubs (doall
                    (map-indexed
                     (fn [i source]
                       (subscribe
                        source
                        done
                        (fn [state src message]
                          (if (= ::done message)
                            (let [new-state (assoc state i true)]
                              (when (every? identity new-state)
                                (send agnt action this ::done))
                              new-state)
                            (do (send agnt action this message)
                                state)))))
                     sources))]
        (fn [] (doseq [u unsubs] (u)))))))

(defn delay [d units message]
  (reify Observable
    (subscribe [this agnt action]
      (let [fut (delay-send d units agnt action this message)]
        (fn [] (.cancel fut false))))))

(defn periodic [init d units message]
  (reify Observable
    (subscribe [this agnt action]
      (let [fut (periodic-send init d units agnt action this message)]
        (fn [] (.cancel fut false))))))

(defn timeout [d units source]
  (first (merge source (delay d units ::timeout))))
