(ns cljque.combinator-test
  (:use cljque.api
	cljque.combinators
	[lazytest.describe :only (describe it given for-any)]
	[lazytest.random :only (integer)]))

(describe observe-seq
  (for-any [n (integer :min 0 :max 100)]
	   (it (= (range n) (seq-observable (observe-seq (range n)))))))

(describe seq-observable
  (for-any [n (integer :min 0 :max 100)]
	   (it (= (range n) (seq-observable (range-events n))))))

(describe range-events
  (for-any [n (integer :min 0 :max 100)]
	   (it "generates n events"
	     (= n (count (seq-observable (range-events n)))))))

(describe watch-events
  (it (= (list [0 1] [1 2] [2 3])
	 (rest (seq-observable (watch-events (range-events 4)))))))

(describe change-events
  (it (= (list 1 2 1 3)
	 (seq-observable (change-events (observe-seq (seq [1 1 2 2 1 1 3])))))))

(describe delta-events
  (it (= (list 0 1 1 2)
	 (seq-observable (delta-events - (observe-seq (seq [1 1 2 3 5])))))))

(describe distinct-events
  (it (= (list 1 2 3)
	 (seq-observable (distinct-events (observe-seq (seq [1 1 2 2 1 1 3])))))))

(describe take-events
  (it (= (list 0 1 2 3 4)
	 (seq-observable (take-events 5 (range-events 10))))))
