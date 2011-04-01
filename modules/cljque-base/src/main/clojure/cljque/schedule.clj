(ns cljque.schedule
  (:import (java.util.concurrent Executors TimeUnit)))

(def ^{:private true
       :doc "ScheduledThreadPoolExecutor with 1 thread."}
  scheduled-executor (Executors/newScheduledThreadPool 1))

(let [java-time-units {:days TimeUnit/DAYS
                       :minutes TimeUnit/MINUTES
                       :hours TimeUnit/HOURS
                       :seconds TimeUnit/SECONDS
                       :milliseconds TimeUnit/MILLISECONDS
                       :microseconds TimeUnit/MICROSECONDS
                       :nanoseconds TimeUnit/NANOSECONDS}]
  (defn- time-unit [keyword]
    (or (get java-time-units keyword)
        (throw (IllegalArgumentException.
                (str "Invalid time-unit keyword " keyword))))))

(defn delay-call
  "Invokes f after d units of time. Returns a no-argument function
  which cancels the invocation if it has not already occurred.

  Valid units of time are :days, :minutes, :hours, :seconds, 
  :milliseconds, :microseconds, and :nanoseconds.  Time resolution is
  the same as provided by the JVM."
  [d units f]
  (let [fut (.schedule scheduled-executor f
                       d (time-unit units))]
    (fn [] (.cancel fut false))))

(defn periodic-call
  "Invokes f every d units of time, after an initial delay of init
  units.  Returns a no-argument function which cancels the scheduled
  event.  Valid time units are the same as for delay-call."
  [init d units f]
  (let [fut (.scheduleAtFixedRate scheduled-executor f                                  
                                  init d (time-unit units))]
    (fn [] (.cancel fut false))))

;; Instead of bound-fn*, I would like to use the (private)
;; clojure.core/binding-conveyor-fn

(defn delay-send
  "Like delay-call but sends an action to an agent, conveying current
  thread-local bindings."
  [d units agnt action & args]
  (delay-call d units (bound-fn* #(apply send agnt action args))))

(defn periodic-send
  "Like periodic-call but sends an action to an agent, conveying
  current thread-local bindings."
  [init d units agnt action & args]
  (periodic-call init d units (bound-fn* #(apply send agnt action args))))
