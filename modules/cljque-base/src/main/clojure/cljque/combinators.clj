(ns cljque.combinators
  (:use cljque.api))

;;; Reusable Observable implementations

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
		      (when (not= x terminator)
			(cons x (this))))))]
    (subscribe o (gensym "seq-observable")
	       (reify Observer
		      (event [this observed key event]
			     (.put q event))
		      (done [this observed key]
			    (.put q terminator))
		      (error [this observed key e]
			     (throw e))))
    (consumer)))

(defn observe-seq
  "Returns an observer that generates events by consuming a sequence
  on a separate thread."
  [s]
  (let [keyset (atom #{})]
    (reify Observable
	   (subscribe [this key observer]
		      (swap! keyset conj key)
		      (future (loop [xs s]
				(when (contains? @keyset key)
				  (let [x (first xs)]
				    (if x
				      (do (event observer this key x)
					  (recur (next xs)))
				      (done observer this key)))))))
	   (unsubscribe [this key]
			(swap! keyset disj key)))))

(defn range-events
  ([]
     (let [keyset (atom #{})]
       (reify Observable
              (subscribe [this key observer]
			 (swap! keyset conj key)
			 (future (loop [i 0]
				   (when (contains? @keyset key)
				     (event observer this key i)
				     (recur (inc i))))))
              (unsubscribe [this key]
			   (swap! keyset disj key)))))
  ([finish]
     (range-events 0 finish))
  ([start finish]
     (let [keyset (atom #{})]
       (reify Observable
              (subscribe [this key observer]
			 (swap! keyset conj key)
			 (future (loop [i start]
				   (if (and (contains? @keyset key)
					    (< i finish))
				     (do (event observer this key i)
					 (recur (inc i)))
				     (done observer this key)))))
              (unsubscribe [this key]
			   (swap! keyset disj key))))))

(defn once [value]
  (reify Observable
         (subscribe [this key observer]
		    (event observer this key value)
		    (done observer this key))
         (unsubscribe [this key] nil)))

(defn never []
  (reify Observable
	 (subscribe [this key observer]
		    (done observer this key))
	 (unsubscribe [this key] nil)))

;;; Wrappers

(defn handle-events [f o]
  (reify Observable
	 (subscribe [this key observer]
		    (subscribe o key
			       (reify Observer
				      (event [this observable key value]
					     (f observer observable key value))
				      (done [this observable key]
					    (done observer observable key))
				      (error [this observable key e]
					     (error observer observable key e)))))
	 (unsubscribe [this key]
		      (unsubscribe o key))))

(defn take-events [n o]
  (let [sub-counts (ref {})]
    (reify Observable
	   (subscribe [this key observer]
		      (dosync (alter sub-counts assoc key 0))
		      (subscribe o key
				 (reify Observer
					(event [this observable key value]
					       (let [c (dosync
							(when (contains? @sub-counts key)
							  (alter sub-counts update-in [key] inc)
							  (get @sub-counts key)))]
						 (cond (= c n)  (do (event observer observable key value)
								    (done observer observable key)
								    (dosync (alter sub-counts dissoc key)))
						       (< c n)  (event observer observable key value))))
					(done [this observable key]
					      (when (dosync
						     (when (contains? @sub-counts key)
						       (alter sub-counts update-in [key] inc)))
						(done observer observable key)))
					(error [this observable key e]
					       (error observer observable key e)))))
	   (unsubscribe [this key]
			(dosync (alter sub-counts dissoc key))))))

(defn map-events [f o]
  (handle-events (fn [observer observable key value]
		   (event observer observable key (f value)))
		 o))

(defn filter-events [f o]
  (handle-events (fn [observer observable key value]
		   (when (f value)
		     (event observer observable key value)))
		 o))

(defn watch-events [o]
  (let [values (atom [nil ::unset])]
    (handle-events (fn [observer observable key value]
		     (event observer observable key
			    (swap! values (fn [[older old]] [old value]))))
		   o)))

(defn change-events [o]
  (let [o (watch-events o)]
    (handle-events (fn [observer observable key [old new]]
		     (when-not (= old new)
		       (event observer observable key new)))
		   o)))

(defn delta-events [f o]
  (let [o (watch-events o)]
    (handle-events (fn [observer observable key [old new]]
		     (when-not (= old ::unset)
		       (event observer observable key (f new old))))
		   o)))

(defn distinct-events [o]
  (let [seen (ref #{})]
    (handle-events (fn [observer observable key value]
		     (when-not (dosync
				(let [old-seen @seen]
				  (commute seen conj value)
				  (contains? old-seen value)))
		       (event observer observable key value)))
		   o)))
