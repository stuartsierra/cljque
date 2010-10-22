(ns message)

;;; Private, local-only implementation

(def ^{:private true} listeners (ref {}))

(defn- add-listener [address f]
  (dosync
   (alter listeners update-in [address] (fnil conj #{}) f)))

(defn- remove-listener [address f]
  (dosync
   (alter listeners
	  (fn [state]
	    (if (= 1 (count (get state address)))
	      (dissoc state address)
	      (update-in state [address] disj f))))))

(defn- remove-all-listeners [address]
  (dosync
   (alter listeners dissoc address)))

(defn- unique-id []
  (str (java.util.UUID/randomUUID)))

(def ^{:private true} empty-promise (doto (promise) (deliver true)))

;;; Generic send/listen message API

(defn send-message
  "Sends a message to an address. Both message and and address may be
  any type. Returns a promise which blocks until the message has been
  sent (but not necessarily received)."
  [address message]
  (doseq [f (get @listeners address)]
    (future (f message)))
  empty-promise)

(defn listen
  "Starts listening to messages for address. When a message is
  received, f is invoked with the message as its argument.  Returns a
  promise which blocks until the listener is ready to receive
  messages."
  [address f]
  (add-listener address f)
  empty-promise)

(defn unlisten
  "Stops listening to an address. Returns a promise which blocks until
  listening has stopped."
  ([address]
     (remove-all-listeners address)
     empty-promise)
  ([address f]
     (remove-listener address f)
     empty-promise))

;;; Request/response pattern

(defn reply-to
  "Wraps message in an envelope with a reply-to header of address."
  [address message]
  {:reply-to address, :message message})

(defn request
  "Sends a message to address and expects a response. When the
  response is received, f will be invoked with the response message as
  its argument."
  [address message f]
  (let [token (unique-id)]
    @(listen token (fn [response] (f response) (unlisten token)))
    (send-message address (reply-to token message))))

(defn responder
  "Returns a function wrapping f which expects a message with a
  reply-to wrapper. The function will invoke f on the original message
  and send back its return value."
  [f]
  (fn [{:keys [reply-to message]}]
    (send-message reply-to (f message))))

(defn respond
  "Responds to messages sent to address with the result of invoking f
  on the message."
  [address f]
  (listen address (responder f)))

;;; Message acknowledgement pattern

(defn acker
  "Returns a function wrapping f that acknowledges messages a soon as
  they are received."
  [f]
  (fn [{:keys [reply-to message]}]
    (send-message reply-to true)
    (f message)))

(defn listen-ack
  "Listen on address; acknowledge messages as soon as as they are
  received."
  [address f]
  (listen address (acker f)))

(defn send-ack
  "Sends message to address. Returns a promise which blocks until the
  message has been acknowledged as received."
  [address message]
  (let [token (unique-id)
	p (promise)]
    @(listen token (fn [response] (deliver p response)))
    (send-message address (reply-to token message))
    p))

;;; Forward-to-group or "mailing list" pattern

(defn group-forwarder
  "Returns a function which forwards each message it receives to a
  group of addresses."
  [addresses]
  (fn [msg]
    (doseq [a addresses]
      (send-message a msg))))

(defn forward-to-group [address recipients]
  (listen address recipients))
