(ns cljque.events
  (:refer-clojure :exclude (map filter take merge)))

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

(defn observation [x closer]
  (ObservedValue. x closer))

(deftype ObservedThrowable [t]
  Observation
  (value [this] (throw t))
  (observe [this] this))

(defmacro lens [[target event source] & body]
  `(reify Observable
     (register [this# ~target]
       (register ~source
                 (fn [~event]
                   (try ~@body
                        (catch Throwable t#
                          (~target (ObservedThrowable. t#)))))))))

(defn shutter [target event]
  (close event)
  (target nil))

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
            (when @open? (f nil))
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
  (lens [target event src]
        (if (observe event)
          (target (observation (f (value event)) event))
          (shutter target event))))

(defn filter [f src]
  (lens [target event src]
        (if (observe event)
          (when (f (value event))
            (target event))
          (shutter target event))))

(defn take [n src]
  (let [a (atom n)]
    (lens [target event src]
          (if (observe event)
            (let [x (swap! a #(if (neg? %) % (dec %)))]
              (when (not (neg? x))
                (target event))
              (when (zero? x)
                (shutter target event)))
            (shutter target event)))))

(deftype MultiCloserEvent [event closers]
  Observation
  (value [this] (value event))
  (observe [this] this)
  Closeable
  (close [this] (doseq [c @closers] (close c))))

(deftype MultiCloser [source closers]
  Observable
  (register [this f]
    (register source
              (fn [event]
                (f (MultiCloserEvent. event closers)))))
  Closeable
  (close [this]
    (doseq [c @closers] (close c))))

(defn merge [& sources]
  (reify Observable
    (register [this f]
      (let [closers (promise)]
        (deliver closers
                  (doall (clojure.core/map
                         (fn [source]
                           (register (MultiCloser. source closers) f))
                         sources)))
        (MultiCloser. this closers)))))

(defn merge-indexed [& sources]
  (apply merge (clojure.core/map-indexed
                (fn [i source]
                  (map #(vector i %) source))
                sources)))

(comment
  (defn first-in [& sources]
    (let [a (atom nil)]
      (map second
           (take-while
            ;; This doesn't work because closing 1 closes them all
            (fn [[i x]] (= i (swap! a (fn [n] (if (nil? n) i n)))))
            (apply merge-indexed sources))))))

(defn ignore-stop [source]
  (lens [target event source]
        (when (observe event)
          (target event))))

(defn first-in [& sources]
  (let [a (atom nil)]
    (reify Observable
      (register [this f]
        (dorun
         (clojure.core/map-indexed
          (fn [i source]
            (register (ignore-stop
                       (take-while (fn [_] (= i (swap! a (fn [n] (if (nil? n) i n)))))
                                   source))
                      f))
          sources))))))

(let [a (agent nil)]
  (defn safe-prn [& args]
    (send-off a (fn [_] (apply prn args)))))

(defn debug-observable []
  (reify Observable
    (register [this f]
      (safe-prn "Registered")
      (let [open? (atom true)
            closer (reify Closeable
                     (close [this]
                       (safe-prn "Closed")
                       (reset! open? false)))]
        (future
          (try
           (dotimes [i 5]
             (when @open?
               (safe-prn "Generating" i)
               (f (ObservedValue. i closer))
               (Thread/sleep 100)))
           (when @open?
             (safe-prn "Generating" nil)
             (f nil)
             (close closer))
           (catch Throwable t
             (safe-prn "THROWN" t))))
        closer))))