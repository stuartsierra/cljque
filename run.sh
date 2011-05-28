#!/bin/sh

cd `dirname $0`

exec java -cp "src/main/clojure:target/dependency/*" \
    clojure.main -i "src/main/clojure/cljque/inotify.clj" \
    -e "(in-ns 'cljque.inotify)" -r