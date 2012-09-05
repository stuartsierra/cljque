(ns cljque.future-seq
  (:use cljque.inotify))

(defprotocol IFutureSeq
  (current [this])
  (later [this]))

(deftype FutureCons [current later]
  IFutureSeq
  (current [this] current)
  (later [this] later))

(extend nil IFutureSeq
        {:current (constantly nil)
         :later (constantly nil)})

(defn follow [a b]
  (FutureCons. a b))

(defn map& [f fseq]
  (lazy-on [c fseq]
    (when c
      (follow (f (current c))
              (map& f (later c))))))

(defn take& [n fseq]
  (lazy-on [x fseq]
    (when (and (pos? n) x)
      (follow (current x) (take& (dec n) (later x))))))

(defn drop& [n fseq]
  (let [out (notifier)
        step (fn thisfn [n x]
               (if x
                 (if (zero? n)
                   (supply out x)
                   (attend (later x) (partial thisfn (dec n))))
                 (supply out nil)))]
    (attend fseq (partial step (dec n)))
    out))

(defn filter& [f fseq]
  (let [out (notifier)
        step (fn thisfn [x]
               (if x
                 (let [c (current x)]
                   (if (f c)
                     (supply out (follow c (filter& f (later x))))
                     (attend (later x) thisfn)))
                 (supply out nil)))]
    (attend fseq step)
    out))

(defn reduce& [f init fseq]
  (let [out (notifier)
        step (fn thisfn [init x]
               (if x
                 (attend (later x)
                           (partial thisfn (f init (current x))))
                 (supply out init)))]
    (attend fseq (partial step init))
    out))

(defn push!
  "Extends a future-seq by supplying one Cons cell containing x and
  another future-seq. Returns the next future-seq."
  [fseq x]
  (later (supply fseq (follow x (notifier)))))

(defn stop!
  "Ends a future-seq by supplying nil. Returns nil."
  [fseq]
  (later (supply fseq nil)))

(defn default-sink-error-handler [s e]
  (let [err (java.io.PrintWriter. *err*)]
    (.printStackTrace e err)
    (.flush err)))

(defn sink
  "Calls f for side effects on each successive value of fseq.

  Optional :error-handler is a function which will be called with the
  current future-seq and the exception. Default error handler prints a
  stacktrace to *err*. After an error is thrown, future values of fseq
  will be ignored."
  [f fseq & options]
  (let [{:keys [error-handler]
         :or {error-handler default-sink-error-handler}}
        options]
    (attend fseq
              (fn thisfn [s]
                (when s
                  (try (f (current s))
                       (attend (later s) thisfn)
                       (catch Throwable t
                         (error-handler s t))))))
    nil))

(defmacro doseq& [bindings & body]
  {:pre [(vector? bindings) (= 2 (count bindings))]}
  `(sink (fn [~(first bindings)] ~@body) ~(second bindings)))

(defn first-in
  "Returns a notifier which receives the value of the first given
  notifier to have a value."
  [& notifiers]
  (let [out (notifier)]
    (doseq [n notifiers]
      (attend n #(supply out %)))
    out))

(defn future-call& [f]
  (let [out (notifier)]
    (future-call #(supply out (try (f) (catch Throwable t t))))
    out))

(defmacro future& [& body]
  `(future-call& (fn [] ~@body)))

(comment
  ;; Sample usage
  (def a (notifier))
  (def b (filter& even? a))
  (def c (map& #(* 5 %) b))
  (def d (take& 10 c))
  (def e (reduce& + 0 d))
  (doseq& [x d] (println "d is" x))
  (do-when-ready [x e] (println "e is" x))
  
  ;; This must be an Agent, not an Atom or Ref:
  (def tick (agent a))
  (dotimes [i 100]
    (send tick push! i))
  (send tick stop!)
  )

(comment
  ;; Performance comparison of derived-notifier vs lazy-notifier
  (dotimes [i 10]
    (let [origin (notifier)
          terminus (loop [i 0, n origin]
                     (if (= i 1000) n
                         (recur (inc i) (derived-notifier n inc))))]
      (time (do (supply origin 1) (deref terminus)))))
"Elapsed time: 8.002 msecs"
"Elapsed time: 1.529 msecs"
"Elapsed time: 1.558 msecs"
"Elapsed time: 1.494 msecs"
"Elapsed time: 1.496 msecs"
"Elapsed time: 1.522 msecs"
"Elapsed time: 1.534 msecs"
"Elapsed time: 1.432 msecs"
"Elapsed time: 1.652 msecs"
"Elapsed time: 1.53 msecs"

  (dotimes [i 10]
    (let [origin (notifier)
          terminus (loop [i 0, n origin]
                     (if (= i 1000) n
                         (recur (inc i) (lazy-notifier n inc))))]
      (time (do (supply origin 1) (deref terminus)))))
"Elapsed time: 2.095 msecs"
"Elapsed time: 0.857 msecs"
"Elapsed time: 0.725 msecs"
"Elapsed time: 0.851 msecs"
"Elapsed time: 0.866 msecs"
"Elapsed time: 0.86 msecs"
"Elapsed time: 0.887 msecs"
"Elapsed time: 0.866 msecs"
"Elapsed time: 0.841 msecs"
"Elapsed time: 0.513 msecs"
)

;; "lazy-on": occur with or as a result of; wait on (an important person)

;; Still TODO:
;; - Supply chunked seqs to notifiers

;; Open questions:
;; - Should FutureCons implement ISeq?
;;   - implies all of IPersistentCollection and Seqable (and maybe Sequential)
;; - Should INotify be extended to other types?
;;   - future-seq fns would work on regular seqs
;;   - They would invoke callback immediately
;; - Should registration be cancellable?
;;   - Complicates bookkeeping
;; - Use WeakReferences for callback queue?
;;   - if result is not used, notification can be skipped
;; - Should there be any integration with Agents?
;;   - e.g. 'sink' that takes agent & function?

