(ns message.zeromq-test
  (:use message.zeromq
	message.api
	message.test-helpers
	[lazytest.expect :only (expect)]
	[lazytest.describe :only (describe do-it given for-any)]
	[lazytest.random :only (list-of string-of pick alphanumeric
					rand-int-in-range)]))

(describe "ZeroMQ PUB/SUB sockets over TCP"
  (given [port (rand-int-in-range 11000 20000)
	  log (atom [])
	  listener (fn [msg] (swap! log conj (Integer/parseInt (String. msg))))
	  size 50] ; this fails over 85, why?
    (do-it (str "can send/receive" size "messages")
      (listen (zmq-tcp-sub-socket :bind "*" port) listener)
      (Thread/sleep 500)
      (dotimes [i size]
	(send-message (zmq-tcp-pub-socket :connect "127.0.0.1" port)
		      (.getBytes (str i))))
      (Thread/sleep 1000)
      (expect (= (vec (range size)) @log))
      (stop (zmq-tcp-sub-socket :bind "*" port))
      (stop (zmq-tcp-pub-socket :connect "127.0.0.1" port)))))
