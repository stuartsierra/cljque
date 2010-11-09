(ns cljque.combinator-test
  (:use cljque.api
	cljque.combinators
	[lazytest.describe :only (describe it given for-any)]
	[lazytest.random :only (integer)]))

;;; All these will fail until observables support "stop" events.

;; (describe observable-seq
;;   (for-any [n (integer :max 100)]
;; 	   (= (range n) (seq-observable (observable-seq (range n))))))

;; (describe seq-observable
;;   (for-any [n (integer :max 100)]
;; 	   (= (range n) (seq-observable (range n)))))

;; (describe range-events
;;   (for-any [n (integer)]
;; 	   (it "generates n events"
;; 	     (= n (count (seq-observable (range-events n)))))))