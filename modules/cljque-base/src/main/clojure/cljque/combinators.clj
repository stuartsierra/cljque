(ns cljque.combinators
  (:use cljque.api))

;;; Reusable Observable implementations

(defn seq-observable
  "Creates an infinite sequence of events from an observable.
  Consuming the sequence will block if no more events have been
  generated."
  [o]
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
	consumer (fn this []
		   (lazy-seq
		    (cons (.take q) (this))))]
    (subscribe o (gensym "seq-observable")
	       (fn [o key value]
		 (.put q value)))
    (consumer)))

(defn observable-seq
  "Creates an observer that generates events by consuming a sequence."
  [s]
  (let [keyset (atom #{})]
    (reify Observable
	   (subscribe [this key f]
		      (swap! keyset conj key)
		      (future (loop [xs s]
				(when (contains? @keyset key)
				  (when-first [x xs]
					      (f this key x)
					      (recur (next xs)))))))
	   (unsubscribe [this key]
			(swap! keyset disj key)))))

(defn range-events
  ([]
     (let [keyset (atom #{})]
       (reify Observable
              (subscribe [this key f]
                          (swap! keyset conj key)
                          (future (loop [i 0]
                                    (when (contains? @keyset key)
                                      (f this key i)
                                      (recur (inc i))))))
              (unsubscribe [this key]
                            (swap! keyset disj key)))))
  ([finish]
     (range-events 0 finish))
  ([start finish]
     (let [keyset (atom #{})]
       (reify Observable
              (subscribe [this key f]
                          (swap! keyset conj key)
                          (future (loop [i start]
                                    (when (and (contains? @keyset key)
                                               (< i finish))
                                      (f this key i)
                                      (recur (inc i))))))
              (unsubscribe [this key]
                            (swap! keyset disj key))))))

(defn once [value]
  (reify Observable
         (subscribe [this key f]
                     (f this key value))
         (unsubscribe [this key] nil)))

(defn never []
  (reify Observable
	 (subscribe [this key f] nil)
	 (unsubscribe [this key] nil)))

;;; Wrapper implementations

(comment
  (defmacro wrap-observable [obs &body]
    `(reify Observable
	    ~@body
	    (unsubscribe [this key] (unsubscribe ~obs key)))))

(defn map-events [f o]
  (reify Observable
	 (subscribe [this key g]
		     (subscribe o key (comp g f)))
	 (unsubscribe [this key]
		       (unsubscribe o key))))

(defn filter-events [f o]
  (reify Observable
	 (subscribe [this key g]
		     (subscribe o key (fn [o k m]
					 (when (f m) (g this key m)))))
	 (unsubscribe [this key]
		       (unsubscribe o key))))

(defn change-events [o]
  (let [values (atom [nil ::unset])]
    (reify Observable
	   (subscribe [this key f]
		      (subscribe o key (fn [this key new]
					 (f this key
					    (swap! values (fn [[older old]] [old new]))))))
	   (unsubscribe [this key]
			(unsubscribe o key)))))

(defn distinct-events [o]
  (let [o (change-events o)]
    (reify Observable
	   (subscribe [this key f]
		      (subscribe o key (fn [this key [old new]]
					 (when-not (= old new)
					   (f this key new)))))
	   (unsubscribe [this key]
			(unsubscribe o key)))))

(defn delta-events [f o]
  (let [o (change-events o)]
    (reify Observable
	   (subscribe [this key g]
		      (subscribe o key (fn [this key [old new]]
					 (when-not (= old ::unset)
					   (g this key (f new old))))))
	   (unsubscribe [this key]
			(unsubscribe o key)))))
