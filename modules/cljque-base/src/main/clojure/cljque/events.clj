(ns cljque.events
  (:refer-clojure :exclude (map filter take)))

(defprotocol Observable
  (register [this f]))

(defprotocol Observation
  (value [this])
  (observe [this]))

(defprotocol Closeable
  (close [this]))

(extend-type java.io.Closeable
  Closeable
  (close [this] (.close this)))

(extend-type nil
  Observation
    (value [this] nil)
    (observe [this] nil)
  Observable
    (register [this f]
      (future (f nil))
      nil)
  Closeable
    (close [this] nil))

(deftype ObservedValue [x closer]
  Observation
  (value [this] x)
  (observe [this] this)
  Closeable
  (close [this] (close closer)))

(deftype ObservedThrowable [t]
  Observation
  (value [this] (throw t))
  (observe [this] this))

(defn ^:dynamic relay [x] nil)

(defn stop [closer]
  (close closer)
  (relay nil))

(defn push [x closer]
  (relay (ObservedValue. x closer)))

(defmacro lens [event-source & body]
  `(reify Observable
     (register [this g#]
       (register ~(second event-source)
                 (fn [~(first event-source)]
                   (binding [relay g#]
                     (try ~@body
                          (catch Throwable t#
                            (relay (ObservedThrowable. t#))))))))))

(defn observable-seq [s]
  (reify Observable
    (register [this f]
      (let [open? (atom true)
            closer (reify java.io.Closeable
                     (close [this]
                       (reset! open? false)))]
        (future
          (try 
            (loop [s s]
              (when (and (seq s) @open?)
                (f (ObservedValue. (first s) closer))
                (recur (rest s))))
            (f nil)
            (catch Throwable t
              (f (ObservedThrowable. t)))))))))

(defn seq-observable [src]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        NIL (Object.)  ; can't put nil in a Queue
        consumer (fn thisfn []
                   (lazy-seq
                    (let [event (.take q)]
                      (when (not= NIL event)
                        (cons (value event) (thisfn))))))]
    (register src (fn [event] (.put q (if (nil? event) NIL event))))
    (consumer)))

(defn map [f src]
  (lens [event src]
        (if (observe event)
          (push (f (value event)) event)
          (stop event))))

(defn filter [f src]
  (lens [event src]
        (if (observe event)
          (when (f (value event))
            (relay event))
          (stop event))))

(defn take [n src]
  (let [a (atom n)]
    (lens [event src]
          (if (observe event)
            (let [x (swap! a #(if (neg? %) % (dec %)))]
              (when (not (neg? x))
                (relay event))
              (when (zero? x)
                (stop event)))))))

(let [a (agent nil)]
  (defn safe-prn [& args]
    (send-off a (fn [_] (apply prn args)))))

(def debug-observable
  (reify Observable
    (register [this f]
      (safe-prn "Registered")
      (let [open? (atom true)
            closer (reify Closeable
                     (close [this]
                       (safe-prn "Closed")
                       (reset! open? false)))]
        (future
          (dotimes [i 5]
            (when @open?
              (safe-prn "Generating" i)
              (f (ObservedValue. i closer))
              (Thread/sleep 100)))
          (when @open?
            (safe-prn "Generating" nil)
            (f nil)
            (close closer)))
        closer))))