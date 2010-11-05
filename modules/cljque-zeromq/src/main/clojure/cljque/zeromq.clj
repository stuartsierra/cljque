(ns cljque.zeromq
  (:use cljque.api)
  (:require [cljque.zeromq.eventloop :as el])
  (:import (org.zeromq ZMQ)))

(def ^:private zmq-commander (atom nil))

(defn zmq []
  (or @zmq-commander
      (swap! zmq-commander
	     (fn [state] (or state @(el/start-event-loop))))))

(defn stop-zmq []
  (swap! zmq-commander
	 (fn [state] (when state (state (el/make-shutdown-command))))))

(def socket-types {:pub ZMQ/PUB
		   :sub ZMQ/SUB
		   :push ZMQ/PUSH
		   :pull ZMQ/PULL
		   :xreq ZMQ/XREQ
		   :xrep ZMQ/XREP})

(def connection-types #{:connect :bind})

(defn make-socket [socket-type connection-type host port]
  (let [endpoint (str "tcp://" host ":" port)
	socket (el/socket (socket-types socket-type))]
    (if (= :bind connection-type)
      (.bind socket endpoint)
      (.connect socket endpoint))
    socket))

(defrecord ZMQTCPSUBSocket [connection-type host port subscription]
  Listener
    (listen [this f]
      ((zmq) (el/make-add-listener-command
	      this
	      #(doto (make-socket :sub connection-type host port)
		 (.subscribe subscription))
	      f)))
  Stoppable
    (stop [this] ((zmq) (el/make-close-socket-command this)))
  MessageTarget
    (send-message [this message] ((zmq) (el/make-send-command this message))))

(defrecord ZMQTCPPUBSocket [connection-type host port]
  Stoppable
    (stop [this] ((zmq) (el/make-close-socket-command this)))
  MessageTarget
    (send-message [this message] ((zmq) (el/make-send-command this message))))

(def socket-types {:pub ZMQ/PUB
		   :sub ZMQ/SUB
		   :push ZMQ/PUSH
		   :pull ZMQ/PULL
		   :xreq ZMQ/XREQ
		   :xrep ZMQ/XREP})

(defn zmq-tcp-sub-socket
  ([connection-type host port]
     (zmq-tcp-sub-socket connection-type host port (byte-array 0)))
  ([connection-type host port subscription]
     {:pre [(contains? connection-types connection-type)
	    (string? host)
	    (integer? port)]}
     (ZMQTCPSUBSocket. connection-type host port subscription)))

(defn zmq-tcp-pub-socket [connection-type host port]
  {:pre [(contains? connection-types connection-type)
	 (string? host)
	 (integer? port)]}
  (let [key (ZMQTCPPUBSocket. connection-type host port)]
    ((zmq) (el/make-ensure-socket-command
	    key
	    #(make-socket :pub connection-type host port)))
    key))
