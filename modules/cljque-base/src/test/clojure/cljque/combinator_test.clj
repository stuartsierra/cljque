(ns cljque.combinator-test
  (:use cljque.api
	cljque.combinators
	[lazytest.describe :only (describe it given for-any)]
	[lazytest.random :only (integer)]))

(describe observable-seq
  (for-any [n (integer :min 0 :max 100)]
	   (it (= (range n) (seq-observable (observable-seq (range n)))))))

(describe seq-observable
  (for-any [n (integer :min 0 :max 100)]
	   (it (= (range n) (seq-observable (range-events n))))))

(describe range-events
  (for-any [n (integer :min 0 :max 100)]
	   (it "generates n events"
	     (= n (count (seq-observable (range-events n)))))))

(describe change-events
  (it (= (list [0 1] [1 2] [2 3])
	 (rest (seq-observable (change-events (range-events 4)))))))

(describe distinct-events
  (it (= (list 1 2 1 3)
	 (seq-observable (distinct-events (observable-seq (seq [1 1 2 2 1 1 3])))))))

(describe delta-events
  (it (= (list 0 1 1 2)
	 (seq-observable (delta-events - (observable-seq (seq [1 1 2 3 5])))))))