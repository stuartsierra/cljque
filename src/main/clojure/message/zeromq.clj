(ns message.zeromq
  (:use [clojure.contrib.logging :only (error warn info debug)])
  (:import (org.zeromq ZMQ ZMQ$Socket ZMQ$Context ZMQ$Poller)))

(def ^{:private true} zmq-context (atom nil))

(defn context
  "Returns a singleton ZMQ$Context, creating it if necessary."
  []
  (or @zmq-context
      (swap! zmq-context
	     (fn [state] (or state (ZMQ/context 1))))))

(defn stop-context
  "Terminates the singleton ZMQ$Context if it exists."
  []
  (swap! zmq-context
	 (fn [state] (when state (.term state)))))

(defn poller
  "Creates a ZMQ$Poller of the given size"
  [size]
  (.poller (context) size))

(defn socket
  "Creates a ZMQ$Socket of the given type."
  [type]
  (.socket (context) type))

(defn- register-sockets [poller sockets]
  (doseq [s sockets] (.register poller s 1))) ; 1 is ZMQ_POLLIN

(defn- poller-for-sockets [sockets]
  (doto (poller (count sockets))
    (.setTimeout -1)
    (register-sockets sockets)))

(defn- sockets-from-poller [poller]
  (doall (map #(.getSocket poller %) (range (.getSize poller)))))

(defn- handle-command [command-queue state socket]
  (debug "Received command signal")
  (.recv socket 0)
  (let [f (.take command-queue)]
    (debug "Invoking command" f)
    (f state)))

(defn- initialize-event-loop [command-queue command-socket]
  {:poller (poller-for-sockets [command-socket])
   :sockets {::command-socket command-socket}
   :handlers {command-socket (partial handle-command command-queue)}})

(defn- handle-polled [state]
  (let [{:keys [poller handlers]} state]
    (reduce (fn [state i]
	      (debug "Checking for events on poller socket" i)
	      (cond (.pollin poller i)
		    (do (debug "pollin on poller socket" i)
			(let [socket (.getSocket poller i)
			      handler (get handlers socket)]
			  (if handler
			    (do (debug "Invoking handler" handler)
				(handler state socket))
			    (do (warn "No handler for socket" socket)
				state))))
		    (.pollout poller i)
  		    (do (debug "pollout on poller socket" i)
			state)
		    (.pollerr poller i)
		    (do (debug "pollerr on poller socket" i)
			state)
		    :else state))
	    state (range (.getSize poller)))))

(defn- event-loop-body
  "Executes the body of the event loop, given the initial state."
  [initial-state]
  (loop [state initial-state]
    (debug "Polling with timeout" (.getTimeout (:poller state)))
    (let [i (.poll (:poller state))]
      (debug "Poller signalled" i "events")
      (when (neg? i)
	(throw (Exception. "Poller failed"))))
    (let [state' (handle-polled state)]
      (when (not= state state')
	(debug "Event loop state changed to" state'))
      (if state'
	(recur state')
	(info "Event loop stopped.")))))

(defn- unique-id []
  (str (java.util.UUID/randomUUID)))

(defn- make-command-fn
  "Returns the function that sends commands to the event loop."
  [command-endpoint command-queue]
  (fn [f]
    (debug "Sending command" f)
    (.put command-queue f)
    (doto (socket ZMQ/PUB)
      (.connect command-endpoint)
      (.send (byte-array 0) 0)
      (.close))
    nil))

(defn start-event-loop []
  (let [command-queue (java.util.concurrent.LinkedBlockingQueue.)
	command-endpoint (str "inproc://" (unique-id))]
    (info "Starting event loop")
    (info "Command endpoint is" command-endpoint)
    (future
     (try
       (let [command-socket (doto (socket ZMQ/SUB)
			      (.subscribe (byte-array 0))
			      (.bind command-endpoint))]
	 (event-loop-body
	  (initialize-event-loop command-queue command-socket)))
       (catch Throwable t
	 (error t))))
    (make-command-fn command-endpoint command-queue)))

(defn make-add-socket-command
  ([key constructor]
     (make-add-socket-command key constructor nil))
  ([key constructor handler]
     (fn [state]
       (let [{:keys [poller handlers sockets]} state
	     new-socket (constructor)]
	 {:poller (poller-for-sockets (conj (sockets-from-poller poller) new-socket))
	  :sockets (assoc sockets key new-socket)
	  :handlers (assoc handlers new-socket handler)}))))

(defn make-add-listener-command [key constructor handler]
  (make-add-socket-command
   key
   constructor
   (fn [state socket]
     (handler (.recv socket 0))
     state)))

(defn make-send-command [key message]
  (fn [state]
    (let [socket (get (:sockets state) key)]
      (.send socket message 0))
    state))

(defn make-close-socket-command [key]
  (fn [state]
    (let [{:keys [poller handlers sockets]} state
	  socket (get sockets key)]
      (.close socket)
      {:poller (poller-for-sockets (remove #{socket} (sockets-from-poller poller)))
       :sockets (dissoc sockets key)
       :handlers (dissoc handlers socket)})))

(defn make-shutdown-command []
  (fn [state]
    (let [{:keys [sockets]} state]
      (doseq [socket (vals sockets)]
	(.close socket)))))

