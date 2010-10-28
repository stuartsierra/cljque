(ns message.local-test
  (:use message.local
	message.test-helpers
	[lazytest.describe :only (describe do-it given for-any)]
	[lazytest.random :only (list-of string-of pick alphanumeric)]))

(describe local
  (for [address ((list-of (string-of (pick alphanumeric))
			  :min 5 :max 5))]
    (given [local-factory (fn [] (local address))]
      (send-message-test local-factory local-factory)
      (request-response-test local-factory local-factory))))
