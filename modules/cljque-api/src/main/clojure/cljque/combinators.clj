(ns cljque.combinators
  (:use cljque.api))

;;; Reusable Observable implementation

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

(defn once [value]
  (reify Observable
         (subscribe [this key f]
                     (f this key value))
         (unsubscribe [this key] nil)))

(defn never []
  (reify Observable
	 (subscribe [this key f] nil)
	 (unsubscribe [this key] nil)))
