(ns message.zeromq
  (:use message.api)
  (:require [message.zeromq.eventloop :as el])
  (:import (org.zeromq ZMQ)))

(def ^:private zmq-commander (atom nil))

(defn zmq []
  (or @zmq-commander
      (swap! zmq-commander
	     (fn [state] (or state (el/start-event-loop))))))

(defn stop-zmq []
  (swap! zmq-commander
	 (fn [state] (when state (state (el/make-shutdown-command))))))

(deftype ZMQTCPSocket [socket-type connection-type host port]
  Listener
  (listen [this f] ((zmq) (make-add-listener-command this f)))
  Stoppable
  (stop [this] ((zmq) (make-close-socket-command this)))
  MessageTarget
  (send-message [this message] ((zmq (make-send-command this message)))))

