Message - experiments in message-passing architectures in Clojure

by Stuart Sierra, http://stuartsierra.com/

Copyright (c) Stuart Sierra, 2010. All rights reserved.  The use and
distribution terms for this software are covered by the Eclipse Public
License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can
be found in the file LICENSE.html at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.  You must not remove this notice, or any
other, from this software.


Getting Started
========================================

Step 1: Install ZeroMQ.  On OSX, using MacPorts:

    sudo port install pkgconfig
    sudo port install zmq

Step 2: Install the ZeroMQ Java bindings. You will need git, Maven, and C compiler tools:

    git clone http://github.com/zeromq/jzmq.git
    cd jzmq
    git checkout 1a9840617601002290e0
    ./autogen.sh
    make
    sudo make install
    mvn install:install-file -Dfile=/usr/local/share/java/zmq.jar -DgroupId=org.zeromq -DartifactId=zmq -Dversion=2.0.9 -Dpackaging=jar

Step 3: Test ann install message:

    git clone http://github.com/stuartsierra/message
    cd message
    mvn install

Step 4: Run a REPL:

    mvn clojure:repl

Or SWANK:

    mvn clojure:swank



Using the Library
========================================

Message defines a simple API for sending messages and listening for
messages.  The core functions are defined by protocols in
`message.api`.

The `listen` function starts listening for messages received by some
Listener object.

    (listen listener-target receiver-fn)

The listener-target may be any object implementing the `Listener`
protocol.  When listener-target receives a message, it invokes the
given receiver-fn with the message as its argument.

To send a message, use the `send-message` function:

    (send-message target message)

The target may be any object implementing the `MessageTarget`
protocol.

To stop listening for messages on a given target, use the `stop`
function:

    (stop listener-target)

Different implementations provide access to different messaging
systems.  `message.local` does in-process messaging between threads.
`message.zeromq` does messaging using ZeroMQ sockets.
