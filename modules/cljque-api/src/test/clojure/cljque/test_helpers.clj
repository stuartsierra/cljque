(ns cljque.test-helpers
  (:use cljque.api
	[lazytest.expect :only (expect)]
	[lazytest.random :only (string-of pick alphanumeric)]
	[lazytest.describe :only (do-it)]))

(defn random-string []
  ((string-of (pick alphanumeric) :min 5 :max 10)))

(defn send-message-test [listener-factory sender-factory]
  (let [message (random-string)
	received (atom [])
	receiver (fn [msg] (swap! received conj msg))]
    (do-it "can send and receive a message"
      (let [listener (listener-factory)]
	(listen! listener receiver)
	(Thread/sleep 100)
	(send! (sender-factory) message)
	(Thread/sleep 100)
	(stop! listener)
	(expect (= @received [message]))))))


