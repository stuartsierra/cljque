#!/bin/sh

cd `dirname $0`

exec java -cp src/main/clojure:/Users/stuart/.m2/repository/org/clojure/clojure/1.3.0-master-SNAPSHOT/clojure-1.3.0-master-SNAPSHOT.jar:/Users/stuart/.m2/repository/swank-clojure//swank-clojure/1.3.0-SNAPSHOT/swank-clojure-1.3.0-SNAPSHOT.jar clojure.main