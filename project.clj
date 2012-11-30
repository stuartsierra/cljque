(defproject cljque "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.5.0-master-SNAPSHOT"]
                 [org.codehaus.jsr166-mirror/jsr166y "1.7.0"]]
  :repositories {"sonatype-oss-public"
                 "https://oss.sonatype.org/content/groups/public/"}
  :aliases {"dumbrepl" ["trampoline" "run" "-m" "clojure.main"]}
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[org.clojure/tools.namespace "0.2.1"]
                                  [org.clojure/java.classpath "0.2.0"]]}})
