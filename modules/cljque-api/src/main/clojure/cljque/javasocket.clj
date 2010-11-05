(ns cljque.javasocket
  (:use cljque.api
	[clojure.contrib.logging :only (debug error)])
  (:import (org.apache.commons.io IOUtils)))

(defn- open-socket [host port]
  {:pre [(or (string? host) (nil? host))
	 (integer? port)]}
  (debug "Opening socket on" host port)
  (java.net.Socket. host port))

(defn- open-server-socket [port]
  {:pre [(integer? port)]}
  (debug "Opening server socket on port" port)
  (java.net.ServerSocket. port))

(defn- close-socket [socket-atom]
  (swap! socket-atom #(when-not % (.close %))))

(defrecord JavaSocket [socket-atom host port]
  MessageTarget
    (send! [this message]
      (if @socket-atom
	(do (debug "Sending" (count message) "bytes to" host port)
	    (.. @socket-atom getOutputStream (write message)))
	(do (swap! socket-atom #(or % (open-socket host port)))
	    (send! this message))))
  Stoppable
    (stop! [this]
      (debug "Closing socket to" host port)
      (close-socket socket-atom)))

(defn java-socket
  "Creates a java.net.Socket that can send binary messages to the host and port.
  Does not open the socket until you send a message."
  [host port]
  (JavaSocket. (atom nil) host port))

(defn- accept-loop [server-socket f]
  (loop []
    (try 
      (let [socket (.accept server-socket)]
	(future
	 (let [message (IOUtils/toByteArray (.getInputStream socket))]
	   (.close socket)
	   (f message)))
	(recur))
      (catch Throwable t
	(error t "Error on ServerSocket.accept"))))  )

(defrecord JavaServerSocket [socket-atom port]
  Listener
    (listen! [this f]
      (swap! socket-atom
	     (fn [socket]
	       (if socket
		 (throw (Exception. (str "Already listening on port" port)))
		 (open-server-socket port))))
      (future (accept-loop @socket-atom f)))
  Stoppable
    (stop! [this]
      (debug "Closing server socket on" port)
      (close-socket socket-atom)))

(defn java-server-socket [port]
  (JavaServerSocket. (atom nil) port))
