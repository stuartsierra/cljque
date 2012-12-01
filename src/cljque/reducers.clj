(ns cljque.reducers
  (:refer-clojure :exclude (promise deliver))
  (:require clojure.core.protocols
            [cljque.promises
             :refer (promise deliver fail attend *callback-executor*)]))

(defn- promise-reduce [return executor f init fseq]
  (attend fseq
          (fn [p]
            (try (if @p
                   (let [[x pnext] @p
                         val (f init x)]
                     (if (reduced? val)
                       (deliver return @val)
                       (promise-reduce return executor f val pnext)))
                   (deliver return init))
                 (catch Throwable t
                   (fail return t))))
          executor))

(defn future-seq
  "Returns a future sequence (fseq), a reducible sequence of values
  which will be supplied asynchronously. Extend the sequence with
  push! and terminate it with end!. Reducing a future sequence returns
  a promise for the reduced value. Supports the same functions as
  reducible collections. Calling seq returns a blocking lazy sequence."
  []
  (let [pcons (promise)]
    (reify
      cljque.promises/IDeliver
      (-deliver [_ val]
        (cljque.promises/-deliver pcons val))
      (-fail [_ ex]
        (cljque.promises/-fail pcons ex))
      cljque.promises/INotify
      (-attend [_ f executor]
        (cljque.promises/-attend pcons f executor))
      cljque.promises/IFail
      (-failed? [_]
        (cljque.promises/-failed? pcons))
      clojure.lang.IDeref
      (deref [_] (deref pcons))
      clojure.lang.IBlockingDeref
      (deref [_ timeout-ms timeout-val]
        (deref pcons timeout-ms timeout-val))
      clojure.lang.IPending
      (isRealized [_] (.isRealized pcons))
      clojure.core.protocols/CollReduce
      (coll-reduce [_ f]
        (let [return (promise)
              executor *callback-executor*]
          (attend pcons
                  (fn [p]
                    (try (if @p
                           (let [[init pnext] @p]
                             (promise-reduce return executor f init pnext))
                           (deliver return (f)))
                         (catch Throwable t
                           (fail return t))))
                  executor)
          return))
      (coll-reduce [_ f init]
        (let [return (promise)]
          (promise-reduce return *callback-executor* f init pcons)
          return))
      clojure.lang.Seqable
      (seq [_]
        (lazy-seq
         (when @pcons
           (let [[x next-pcons] @pcons]
             (cons x (.seq next-pcons)))))))))

(defn push!
  "Pushes a new value on to the end of the future sequence. Returns a
  future sequence (promise) to which the next value can be pushed.
  Throws an exception if the future sequence has already been ended."
  [fseq value]
  (io! "Cannot push! inside a transaction")
  (let [return (future-seq)]
    (loop [p fseq]
      (if (deliver p [value return])
        return
        (if @p
          (let [[_ next-promise] @p]
            (recur next-promise))
          (throw (ex-info
                  "Cannot push! to a realized promise with a nil value"
                  {:promise promise
                   :value value})))))))

(defn end!
  "Terminates a future sequence. Throws an exception if the future
  sequence has already been ended."
  [fseq]
  (io! "Cannot end! inside a transaction")
  (when-not (deliver fseq nil)
    (if @fseq
      (let [[_ next-promise] @fseq]
        (recur next-promise))
      (throw (ex-info
              "Cannot end! a realized promise with a nil value"
              {:promise promise})))))
