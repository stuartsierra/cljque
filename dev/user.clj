(ns user
  (:require [clojure.repl :refer :all]
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [clojure.core.reducers :as r]
            [cljque.promises :as p
             :refer (then recover any all all-realized)]
            [cljque.future-seq :refer :all]))

(defn thread []
  (.. Thread currentThread getName))

(def p1 (p/promise))


(def p2
  (-> p1
      (then v (prn :on (thread) :v v) (+ v 1))
      (then v (prn :on (thread) :v v) (+ v 2))
      ;;(then v (throw (Exception. "BOOM!")))
      (then v (prn :on (thread) :v v) (+ v 4))
      (recover ex (prn :on (thread) :error ex) -1)
      (then v (prn :on (thread) :v v) (* v 10))))


(def a (p/promise))
(def b (p/promise))
(def c (p/promise))
(def d (p/promise))
(def e (p/promise))

(def all-3 (all a b c))

(def any-1 (any a b c d e))

(def all-3-realized (all-realized a b c))

(def f1 (future-seq))

(defn run1 [] (dotimes [i 100] (push! f1 i)))