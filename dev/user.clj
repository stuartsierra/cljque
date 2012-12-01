(ns user
  (:refer-clojure :exclude (promise deliver future future-call))
  (:use clojure.repl
        clojure.tools.namespace.repl
        cljque.promises
        cljque.reducers)
  (:require [clojure.core.reducers :as r]))

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


(def a (promise))
(def b (promise))
(def c (promise))
(def d (promise))
(def e (promise))

(def all-3 (all a b c))

(def any-1 (any a b c d e))

(def all-3-realized (all-realized a b c))

(def f1 (future-seq))

(defn run1 [] (dotimes [i 100] (push! f1 i)))