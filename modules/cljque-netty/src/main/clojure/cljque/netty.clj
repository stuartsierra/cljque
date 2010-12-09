(ns cljque.netty
  (:use [clojure.java.io :only (reader resource)]))

(defmacro import-netty
  "Import all public Netty class names into the current namespace,
  except those that have external dependencies beyond Netty itself."
  []
  (with-open [r (reader (resource "cljque/netty-classes.txt"))]
    (list* 'clojure.core/import
	   (doall (map symbol (line-seq r))))))
