(ns cljque.api)

;;; Message receiver protocols

(defprotocol Observer
  (event [observer observable event]
    "Called when observable generates an event.")
  (done [observer observable]
    "Called when observable is finished generating events.")
  (error [observer observable e]
    "Called when observable throws an exception e."))

(defprotocol Observable
  (subscribe [observable observer]
    "Subscribes observer to events generated by observable.  Returns a
    no-arg function which unsubscribes the observer."))

(defn subscribe-events
  "Subscribes only to `event` messages from observable. When a message
  is received, invokes f with one argument, the event. Silently
  discards `done` and `error` messages."
  [observable f]
  (subscribe observable (reify Observer
			       (event [_ _ event] (f event))
			       (done [_ _])
			       (error [_ _ _]))))

(defn subscribe-errors
  "Subscribes only to `error` messages from observable. When a message
  is received, invokes f with one argument, the exception. Silently
  discards `done` and `event` messages."
  [observable f]
  (subscribe observable (reify Observer
			       (event [_ _ _])
			       (done [_ _])
			       (error [_ _ err] (f err)))))

(defn subscribe-done
  "Subscribes only to the `done` message from observable. When that
  message is received, invokes f with no arguments. Silently discards
  `error` and `event` messages."
  [observable f]
  (subscribe observable (reify Observer
			       (event [_ _ _])
			       (done [_ _] (f))
			       (error [_ _ _]))))

;;; Message sending protocols

(defprotocol MessageTarget
  (send! [this message]
    "Sends a message asynchronously."))

;;; IRefs are Observable

(extend clojure.lang.IRef
  Observable
  {:subscribe (fn [this-ref observer]
		(let [key (Object.)]
		  (add-watch this-ref key
			     (fn [key iref old new]
			       (event observer iref new)))
		  (fn [] (remove-watch this-ref key))))})

;;; An Agent can wrap a MessageTarget and forward to it

(extend clojure.lang.Agent
  MessageTarget
  {:send! (fn [this-agent message]
	    (send this-agent send! message))})

;;; Functions are Observers

(extend clojure.lang.IFn
  Observer
  {:event (fn [this-fn observable event]
	    (this-fn observable event))
   :done (fn [this-fn observable]
	   (this-fn observable ::done))
   :error (fn [this-fn observable e]
	    (this-fn observable {::error e}))})
