(ns cljque.observe
  "The Observer/Observable protocols, with bridges to sequences and IRefs.")

(defprotocol Observable
  (observe [observable observer]
    "Asynchronously subscribes observer to events generated by
    observable.  May have side effects.  Returns an no-arg function
    which unsubscribes the observer.  The unsubscribe function may
    have side effects but should be idempotent."))

(defprotocol Observer
  (message [observer m]
    "Called when an observable this observer is subscribed to
    generates a message m.  This method must not block; use future or
    send-off for I/O.")
  (done [observer]
    "Called when an observable this observer is subscribed to is
    finished generating events.  This method must not block; use
    future or send-off for I/O.")
  (error [observer e]
    "Called when an observable this observer is subscribed to throws
    an exception e.  This method must not block; use future or
    send-off for I/O."))

;;; Observable references

(defn observable-watch
  "Returns an observable which, when subscribed, places a watch on
  iref. Calls 'message' on observers every time iref changes, with the
  new value of iref as its argument."
  [iref]
  (reify Observable
    (observe [this observer]
      (let [key (Object.)]
        (add-watch iref key
                   (fn [_ _ old-value new-value]
                     (message observer new-value)))
        (fn [] (remove-watch iref key))))))

;;; Observables and Sequences

(defn observable-seq
  "Returns an Observable which, when subscribed, consumes elements
  from sequence s and calls 'message' on its observers for each
  element.  When s has no more elements, calls 'done' on observers.
  If consuming s throws an exception, calls 'error' on observers."
  [s]
  (reify Observable
    (observe [this observer]
      (let [continue (atom true)]
        (future
          (try
            (loop [xs s]
              (when @continue
                (if-let [x (clojure.core/first xs)]
                  (do (message observer x)
                      (recur (next xs)))
                  (done observer))))
            (catch Throwable t
              (error observer t))))
        (fn [] (reset! continue false))))))

(deftype ObservableError [e])

(defn seq-observable
  "Returns a lazy sequence of events from observable.  The sequence
  will block until each event occurs.  If the observable signals an
  exception, consuming the sequence will throw the same exception."
  [observable]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        terminator (Object.)
        consumer (fn thisfn []
                   (lazy-seq
                    (let [x (.take q)]
                      (cond
                       (= x terminator) nil
                       (instance? ObservableError x) (throw (.e x))
                       :else (cons x (thisfn))))))]
    (observe observable
             (reify Observer
               (message [this m] (.put q m))
               (done [this] (.put q terminator))
               (error [this e] (.put q (ObservableError. e)))))
    (consumer)))

;;; Agents as Observers

(defn observer-agent [a on-message on-done]
  "Returns an Observer which forwards messages to Agent a:
   When it receives a message m, calls (send a on-message m) ;
   When it receives done, calls (send a on-done) ;
   And when it receives an exception, sends a function which 
   rethrows the exception."
  (reify Observer
    (message [this m] (send a on-message m))
    (done [this] (send a on-done))
    (error [this e] (send a (fn [_] (throw e))))))
