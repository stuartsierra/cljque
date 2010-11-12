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