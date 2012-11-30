(ns user
  (:refer-clojure :exclude (promise deliver future future-call))
  (:use clojure.repl
        clojure.tools.namespace.repl
        cljque.promises)
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

(defn promise-reduce* [return executor f init input]
  (attend input
          (fn [p]
            (try (if @p
                   (let [[x next-input] @p
                         next-init (f init x)]
                     (if (reduced? next-init)
                       (deliver return @next-init)
                       (promise-reduce* return executor
                                        f next-init next-input)))
                   (deliver return init))
                 (catch Throwable t
                   (fail return t))))
          executor))

(defn promise-reduce [f init pcoll]
  (let [return (promise)]
    (promise-reduce* return *callback-executor*
                     f init pcoll)
    return))

(defn push [promise value]
  (let [p (promise)]
    (if (deliver promise [value p])
      p
      (if @promise
        (let [[_ next-promise] @promise]
          (push next-promise value))
        (throw (ex-info
                "Cannot push to a realized promise with a nil value"
                {:promise promise
                 :value value}))))))

(extend-protocol clojure.core.protocols/CollReduce
  cljque.promises.Promise
  (coll-reduce [coll f]
    (throw (ex-info "Not implemented" {})))
  (coll-reduce [coll f val]
    (promise-reduce f val coll)))
