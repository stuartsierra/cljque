(defproject cljque "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.4.0"]]
  :aliases {"dumbrepl" ["trampoline" "run" "-m" "clojure.main"]}
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.1"]
                                  [org.clojure/java.classpath "0.2.0"]]}})
