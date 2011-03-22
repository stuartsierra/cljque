(ns cljque.schedule
  (:import (java.util.concurrent Executors TimeUnit)))

(def executor (delay (Executors/newScheduledThreadPool 1)))

(def time-unit
  {:days TimeUnit/DAYS
   :minutes TimeUnit/MINUTES
   :hours TimeUnit/HOURS
   :seconds TimeUnit/SECONDS
   :milliseconds TimeUnit/MILLISECONDS
   :microseconds TimeUnit/MICROSECONDS
   :nanoseconds TimeUnit/NANOSECONDS})

;; Instead of bound-fn*, I would like to use the (private)
;; clojure.core/binding-conveyor-fn

(defn delay-call
  "Invokes f after d units of time. Returns a no-argument function
  which cancels the invocation if it has not already occurred."
  [d units f]
  (let [fut (.schedule (force executor) f
                       d (time-unit units))]
    (fn [] (.cancel fut false))))

(defn delay-send
  "Sends action to agnt after d units of time, with extra arguments."
  [d units agnt action & args]
  (delay-call d units (bound-fn* #(apply send agnt action args))))

(defn periodic-call
  "Invokes f every d units of time, after an initial delay of init
  units.  Returns a no-argument function which cancels the scheduled
  event."
  [init d units f]
  (let [fut (.scheduleAtFixedRate (force executor) f                                  
                                  init d (time-unit units))]
    (fn [] (.cancel fut false))))

(defn periodic-send
  "Sends action to agnt every d units of time, after an initial delay
  of init units."
  [init d units agnt action & args]
  (periodic-call init d units (bound-fn* #(apply send agnt action args))))
