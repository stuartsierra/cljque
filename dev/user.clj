(ns user
  (:refer-clojure :exclude (promise deliver future future-call))
  (:use clojure.repl
        clojure.tools.namespace.repl
        cljque.promises))

(defn thread []
  (.. Thread currentThread getName))

(def p1 (promise))


(def p2
  (-> p1
      (then v (prn :on (thread) :v v) (+ v 1))
      (then v (prn :on (thread) :v v) (+ v 2))
      ;;(then v (throw (Exception. "BOOM!")))
      (then v (prn :on (thread) :v v) (+ v 4))
      (recover ex (prn :on (thread) :error ex) -1)
      (then v (prn :on (thread) :v v) (* v 10))))


