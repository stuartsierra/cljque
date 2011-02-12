(ns cljque.schedule
  (:use cljque.api)
  (:import (java.util.concurrent Executors TimeUnit)))

(def executor (Executors/newScheduledThreadPool 1))

(def time-unit
  {:days TimeUnit/DAYS
   :hours TimeUnit/HOURS
   :microseconds TimeUnit/MICROSECONDS
   :milliseconds TimeUnit/MILLISECONDS
   :minutes TimeUnit/MINUTES
   :nanoseconds TimeUnit/NANOSECONDS
   :seconds TimeUnit/SECONDS})

(defn schedule [f delay unit]
  (.schedule executor f delay (time-unit unit)))

(defn schedule-periodic [f initial-delay delay unit]
  (.scheduleAtFixedRate executor f initial-delay delay (time-unit unit)))

(defn scheduled-event [value delay unit]
  (reify Observable
    (subscribe [this observer]
      (schedule #(on-event observer this value)
		delay unit))))

(defn periodic-event [value initial-delay delay unit]
  (reify Observable
    (subscribe [this observer]
      (let [fut (schedule-periodic #(on-event observer this value)
				   initial-delay delay unit)]
	(fn [] (.cancel fut false))))))
