(ns cljque.combinators
  (:use cljque.api))

;;(defn latch-events [obs]
  ;; when all observables have sent an event
  ;; send an event containing all those events
;;)

(defn gather-events
  "Returns on Observable which waits for all observables to signal an
  event, then generates one event whose value a vector of all the
  events.  If any observable signals an error or done, passes that
  through and aborts."
  [& observables]
  (reify Observable
    (subscribe [this observer]
      (let [unsub-all (promise)
	    unset (Object.)
	    results (atom (vec (repeat (count observables) unset)))
	    unsubs (doall
		    (map-indexed
		     (fn [i obs]
		       (subscribe
			obs
			(reify Observer
			  (on-event [_ observable evnt]
			    (when (every? #(not= unset %)
					  (swap! results update-in [i]
						 #(if (= unset %) evnt %)))
			      (@unsub-all)
			      (on-event observer this @results)))
			  (on-done [_ observable]
			    (@unsub-all)
			    (on-done observer this))
			  (on-error [_ observable err]
			    (@unsub-all)
			    (on-error observer this err)))))
		     observables))]
	(deliver unsub-all (fn [] (doseq [c unsubs] (c))))
	@unsub-all))))

(defn any-event
  "Returns an Observable which relays events and errors from all given
  observables.  Signals done when all observables have signaled done."
  [& observables]
  (reify Observable
    (subscribe [this observer]
      (let [dones (atom (vec (repeat (count observables) false)))
	    closers (doall
		     (map-indexed
		      (fn [i obs]
			(subscribe
			 obs
			 (reify Observer
			   (on-error [_ observable err]
			     (on-error observer this err))
			   (on-done [_ observable]
			     (when (every? identity (swap! dones assoc i true))
			       (on-done observer this)))
			   (on-event [_ observable evnt]
			     (on-event observer this evnt)))))
 		      observables))]
	(fn [] (doseq [c closers] (c)))))))

;;; Convenience subscription models

(deftype FunctionObserver [event-fn done-fn error-fn]
  Observer
  (on-event [observer observable evnt] (event-fn evnt))
  (on-done [observer observable] (done-fn))
  (on-error [observer observable err] (error-fn err)))

(defn subscribe-fns
  "Subscribes to messages from observable. When an `event` message is received,
  invokes event-fn wih one argument, the event. When a `done` message
  is received, invokes done-fn with no arguments. When an exception is
  received, invokes error-fn with one argument, the exception."
  ([observable event-fn error-fn]
     (subscribe-fns observable event-fn (constantly nil) error-fn))
  ([observable event-fn error-fn done-fn]
     (subscribe observable (FunctionObserver. event-fn done-fn error-fn))))

(defn subscribe-events
  "Subscribes only to `event` messages from observable. When a message
  is received, invokes f with one argument, the event. Silently
  discards `done` messages, rethrows errors on the invoking thread."
  [observable f]
  (subscribe observable (reify Observer
			       (on-event [_ _ event] (f event))
			       (on-done [_ _])
			       (on-error [_ _ err] (throw err)))))

(defn subscribe-errors
  "Subscribes only to `error` messages from observable. When a message
  is received, invokes f with one argument, the exception. Silently
  discards `done` and `event` messages."
  [observable f]
  (subscribe observable (reify Observer
			       (on-event [_ _ _])
			       (on-done [_ _])
			       (on-error [_ _ err] (f err)))))

(defn subscribe-done
  "Subscribes only to the `done` message from observable. When that
  message is received, invokes f with no arguments. Silently `event`
  messages, rethrows errors on the invoking thread."
  [observable f]
  (subscribe observable (reify Observer
			       (on-event [_ _ _])
			       (on-done [_ _] (f))
			       (on-error [_ _ err] (throw err)))))

;;; Reusable Observable implementations

(deftype SeqObservableExceptionContainer [exception])

(defn seq-observable
  "Returns a sequence of events from an observable.
  Consuming the sequence will block if no more events have been
  generated."
  [o]
  (let [terminator (Object.)
	q (java.util.concurrent.LinkedBlockingQueue.)
	consumer (fn this []
		   (lazy-seq
		    (let [x (.take q)]
		      (cond
		       (instance? SeqObservableExceptionContainer x) (throw (. x exception))
		       (not= x terminator) (cons x (this))))))]
    (subscribe o (reify Observer
			(on-event [this observed event]
			       (.put q event))
			(on-done [this observed]
			      (.put q terminator))
			(on-error [this observed e]
			       (.put q (SeqObservableExceptionContainer. e)))))
    (consumer)))

(defn observe-seq
  "Returns an observer that generates events by consuming a sequence
  on a separate thread."
  [s]
  (reify Observable
	 (subscribe [this observer]
		    (let [continue (atom true)]
		      (future (loop [xs s]
				(when @continue
				  (try
				    (if-let [x (first xs)]
				      (do (on-event observer this x)
					  (recur (next xs)))
				      (on-done observer this))
				    (catch Throwable t
				      (on-error observer this t))))))
		      (fn [] (reset! continue false))))))

(defn range-events
  ([]
     (reify Observable
	    (subscribe [this observer]
		       (let [continue (atom true)]
			 (future (loop [i 0]
				   (when @continue
				     (on-event observer this i)
				     (recur (inc i)))))
			 (fn [] (reset! continue false))))))
  ([finish]
     (range-events 0 finish))
  ([start finish]
     (reify Observable
	    (subscribe [this observer]
		       (let [continue (atom true)]
			 (future (loop [i start]
				   (when @continue
				     (if (< i finish)
				       (do (on-event observer this i)
					   (recur (inc i)))
				       (on-done observer this)))))
			 (fn [] (reset! continue true)))))))

(defn once
  "Returns an Observable which, when subscribed, generates one event
  with value then immediately signals 'done'."
  [value]
  (reify Observable
         (subscribe [this observer]
		    (future (on-event observer this value)
			    (on-done observer this))
		    (constantly nil))))

(defn never
  "Returns an Observable which, when subscribed, signals 'done'
  immediately."
  []
  (reify Observable
	 (subscribe [this observer]
		    (on-done observer this)
		    (constantly nil))))

;;; Wrappers

(defn handle-events
  "Returns an Observable which wraps the events generated by
  Observable o.  When an Observer subscribes to the returned
  Observable, f will be invoked instead of that observer's 'event'
  method.  'done' and 'error' signals are passed through to the
  Observer unchanged."
  [f o]
  (reify Observable
	 (subscribe [this observer]
		    (subscribe o (reify Observer
					(on-event [this observable value]
					       (f observer observable value))
					(on-done [this observable]
					      (on-done observer observable))
					(on-error [this observable e]
					       (on-error observer observable e)))))))

(defn take-events
  "Returns an Observable which wraps Observable o and passes up to n
  events to each subscriber."
  [n o]
  (reify Observable
	 (subscribe [this observer]
	   (let [counter (atom 0)]
	     (subscribe o
			(reify Observer
			  (on-event [this observable value]
			    (when (= n (swap! counter
					      (fn [state]
						(when (< state n)
						  (on-event observer observable value))
						(inc state))))
			      (on-done observer observable)))
			  (on-done [this observable]
			    (when (< @counter n)
			      (on-done observer observable)))
			  (on-error [this observable e]
			    (on-error observer observable e))))))))


(defn map-events
  "Returns an Observable which wraps Observable o by applying f to the
  value of each event."
  [f o]
  (handle-events (fn [observer observable value]
		   (on-event observer observable (f value)))
		 o))

(defn filter-events
  "Returns an Observable which wraps Observable o by only passing
  through events for which pred is true."
  [pred o]
  (handle-events (fn [observer observable value]
		   (when (pred value)
		     (on-event observer observable value)))
		 o))

(defn watch-events
  "Returns an Observable which generates events with
  [previous-value new-value] pairs for each event of Observable o.
  The first previous-value is the ns-qualified keyword ::unset"
  [o]
  (let [values (atom [nil ::unset])]
    (handle-events (fn [observer observable value]
		     (on-event observer observable
			    (swap! values (fn [[older old]] [old value]))))
		   o)))

(defn change-events
  "Returns an Observable which wraps Observable o and only generates
  events when the value changes."
  [o]
  (let [o (watch-events o)]
    (handle-events (fn [observer observable [old new]]
		     (when-not (= old new)
		       (on-event observer observable new)))
		   o)))

(defn delta-events
  "Returns an Observable which wraps Observable o. After the first
  event, applies f to the previous and current value of o and
  generates an event with f's return value."
  [f o]
  (let [o (watch-events o)]
    (handle-events (fn [observer observable [old new]]
		     (when-not (= old ::unset)
		       (on-event observer observable (f new old))))
		   o)))

(defn distinct-events
  "Returns an Observable which wraps Observable o and only generates
  events whose value has never been seen before."
  [o]
  (let [seen (ref #{})]
    (handle-events (fn [observer observable value]
		     (when-not (dosync
				(let [old-seen @seen]
				  (commute seen conj value)
				  (contains? old-seen value)))
		       (on-event observer observable value)))
		   o)))

(defn forward [source & targets]
  ;; How do you unsubscribe this?
  ;; Subscrib on source returns a fn, just like always.
  (subscribe source
	     (reify Observer
		    (on-event [this observed event]
			   (doseq [t targets]
			     (send! t event)))
		    (on-done [this observed] nil)
		    (on-error [this observed e]
			   (throw e)))))
