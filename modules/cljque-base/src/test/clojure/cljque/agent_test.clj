(ns cljque.agent-test
  (:use cljque.api
	cljque.local
	cljque.combinators
	[lazytest.describe :only (describe do-it for-any)]
	[lazytest.expect :only (expect)]
	[lazytest.random :only (list-of string-of pick alphanumeric integer)]))

(describe "Agents"
  (do-it "can accept send!"
    (let [t (local ((string-of (pick alphanumeric))))
	  a (agent t)
	  state (atom nil)
	  observer (fn [_ event] (reset! state event))]
      (subscribe t observer)
      (send! a 5)
      (Thread/sleep 500)
      (expect (= 5 @state)))))
