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

(defn delay-send [d units agnt action & args]
  (.schedule (force executor)
             #(apply send agnt action args)
             d (time-unit units)))

(defn periodic-send [init d units agnt action & args]
  (.scheduleAtFixedRate (force executor)
                        #(apply send agnt action args)
                        init d (time-unit units)))
