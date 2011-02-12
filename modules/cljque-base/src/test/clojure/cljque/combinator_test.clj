(ns cljque.combinator-test
  (:use cljque.api
	cljque.combinators
	[lazytest.describe :only (describe it testing do-it given for-any)]
	[lazytest.expect :only (expect)]
	[lazytest.random :only (integer string-of pick alphanumeric)])
  (:import (java.util.regex Pattern)))

(defn contains-message? [obj message]
  {:pre [(instance? Exception obj)
	 (string? message)]}
  (.contains (.getMessage obj) message))

(describe observe-seq
  (for-any [n (integer :min 0 :max 100)]
	   (it (= (range n) (seq-observable (observe-seq (range n)))))))

(describe observe-seq
 (do-it "catches exceptions"
   (let [errors (atom nil)
	 message ((string-of (pick alphanumeric)))
	 my-observer (reify Observer
			    (on-event [_ _ _])
			    (on-done [_ _])
			    (on-error [_ _ err] (reset! errors err)))]
     (subscribe (observe-seq (lazy-seq (throw (Exception. message)))) my-observer)
     (Thread/sleep 100)
     (expect (contains-message? @errors message)))))

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
	 (seq-observable (take-events 5 (range-events 10)))))
  (testing "with multiple subscribers"
    (do-it (let [r (range-events 10)
		 t1 (take-events 5 r)
		 t2 (take-events 3 r)
		 s1 (seq-observable t1)
		 s2 (seq-observable t2)]
	     (expect (= (list 0 1 2 3 4) s1))
	     (expect (= (list 0 1 2) s2))))))
