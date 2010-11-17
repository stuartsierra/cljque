(ns cljque.agent-test
  (:use cljque.api
	cljque.local
	cljque.combinators
	[lazytest.describe :only (describe do-it for-any)]
	[lazytest.expect :only (expect)]
	[lazytest.random :only (list-of string-of pick alphanumeric integer)]))

;; (describe "Agents"
;;   (for-any [nums (list-of (integer))]
;; 	   (do-it "can accept send!"
;; 	     (let [t (local ((string-of (pick alphanumeric))))
;; 		   a (agent t)
;; 		   s (seq-observable t)]
;; 	       (doseq [n nums] (send! a n))
;; 	       (await a)
;; 	       (expect (= nums (take 1 s)))))))
