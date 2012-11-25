(ns user
  (:refer-clojure :exclude (promise deliver future future-call))
  (:use clojure.repl
        clojure.tools.namespace.repl
        cljque.promises))

(defn thread []
  (.. Thread currentThread getName))

(comment
  (def p1 (promise))

  (def p2 (on [v p1] (prn :on (thread) :p1 v) (inc v)))

  (def p3 (on [v p2] (prn :on (thread) :p2 v) (inc v)))

  (def p4 (on [v p3] (prn :on (thread) :p3 v) (inc v))))

(def start (promise))

(def p2 (on [_ start]
            (Thread/sleep 1010)
            (future (Thread/sleep 505))))

(defn g1 []
  (time (do (deliver start 1)
            @p2)))
