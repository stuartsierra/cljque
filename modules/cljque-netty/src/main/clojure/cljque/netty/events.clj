(ns cljque.netty.events
  (:use cljque.api
	cljque.netty.util))

(import-netty)

(defn observed-event [observable subscribers-map event]
  (if (instance? event ExceptionEvent)
    (doseq [subscriber (vals subscribers-map)]
      (error subscriber observable (.getCause event)))
    (doseq [subscriber (vals subscribers-map)]
      (event subscriber observable event))))

(defn observable-server [server-state]
  (let [{:keys [bootstrap]} server-state
	old-factory (.getPipelineFactory bootstrap)
	subscribers (atom {})
	observable (reify Observable
			  (subscribe [this observer]
				     (let [key (Object.)]
				       (swap! subscribers assoc key observer)
				       (fn [] (swap! subscribers dissoc key)))))]
    (.setPipelineFactory bootstrap
			 (channel-pipeline-factory
			  #(add-to-pipeline
			    (.getPipeline old-factory)
			    "observable" (channel-upstream-handler
					  (fn [context event]
					    (observed-event observable
							    @subscribers
							    event))))))
    observable))
