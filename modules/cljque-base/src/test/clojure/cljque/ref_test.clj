(ns cljque.ref-test
  (:use cljque.api
	cljque.combinators
	[lazytest.describe :only (describe do-it)]
	[lazytest.expect :only (expect)]))

;; (defn expect-change-events [s]
;;   (expect (= (list [1 2] [2 4] [4 8] [8 16]) (take 4 s))))

;; (describe "Refs"
;;   (do-it "are observable as a seq of change events"
;;     (let [r (ref 1)
;; 	  s (seq-observable r)]
;;       (dotimes [i 4] (dosync (alter r * 2)))
;;       (expect-change-events s))))

;; (describe "Atoms"
;;   (do-it "are observable as a seq of change events"
;;     (let [a (atom 1)
;; 	  s (seq-observable a)]
;;       (dotimes [i 4] (swap! a * 2))
;;       (expect-change-events s))))

;; (describe "Agents"
;;   (do-it "are observable as a seq of change events"
;;     (let [a (agent 1)
;; 	  s (seq-observable a)]
;;       (dotimes [i 4] (send a * 2))
;;       (await a)
;;       (expect (= (list [1 2] [2 4] [4 8] [8 16]) (take 4 s))))))
