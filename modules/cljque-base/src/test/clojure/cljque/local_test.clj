(ns cljque.local-test
  (:use cljque.api
	cljque.local
	cljque.test-helpers
	[lazytest.describe :only (describe do-it it given for-any)]
	[lazytest.expect :only (expect)]
	[lazytest.expect.thrown :only (throws? ok?)]
	[lazytest.random :only (list-of string-of pick alphanumeric)]))

(describe local
  (for [address ((list-of (string-of (pick alphanumeric))
			  :min 5 :max 5))]
    (given [local-factory (fn [] (local address))]
      (send-message-test local-factory local-factory))))

(describe local "in a transaction"
  (it "cannot send!"
    (let [lcl (local (gensym))
	  agnt (agent lcl)]
      (throws? Exception
	       (fn [] (dosync (send! lcl "hello"))))))

  (it "can send to an agent"
    (let [lcl (local (gensym))
	  agnt (agent lcl)]
      (ok? (fn [] (dosync (send agnt send! "hello"))))))

  (do-it "can send a value to an agent"
    (let [lcl (local (gensym))
	  agnt (agent lcl)
	  log (atom [])
	  obs (fn [observed key value]
		(swap! log conj value))]
      (subscribe lcl (gensym) obs)
      (dosync (send agnt send! "hello"))
      (Thread/sleep 500)
      (expect (= ["hello"] @log))))

  (do-it "does not send a value if transaction fails"
    (let [lcl (local (gensym))
	  agnt (agent lcl)
	  log (atom [])
	  obs (fn [observed key value]
		(swap! log conj value))]
      (subscribe lcl (gensym) obs)
      (try (dosync (throw (Exception.))
		   (send agnt send! "hello"))
	   (catch Exception e nil))
      (Thread/sleep 100)
      (expect (= [] @log)))))
