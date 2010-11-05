Cljque - experiments in message-passing architectures in Clojure

Cljque is pronounced "clique" or "click"

by Stuart Sierra, http://stuartsierra.com/

Copyright (c) Stuart Sierra, 2010. All rights reserved.  The use and
distribution terms for this software are covered by the Eclipse Public
License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can
be found in the file LICENSE.html at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.  You must not remove this notice, or any
other, from this software.



Using the Library
========================================

Cljque defines a simple API for sending messages and listening for
messages.  The core functions are defined by protocols in
`cljque.api`.

The `listen!` function starts listening for messages received by some
Listener object.

    (listen! listener-target receiver-fn)

The listener-target may be any object implementing the `Listener`
protocol.  When listener-target receives a message, it invokes the
given receiver-fn with the message as its argument.

To send a message, use the `send!` function:

    (send! target message)

The target may be any object implementing the `MessageTarget`
protocol.

To "close" or "shutdown" a target, i.e. to stop all listening and
sending, use the `stop!` function:

    (stop! target)

Different implementations provide access to different messaging
systems.

`cljque.local` does in-process messaging between threads.

`cljque.javasocket` uses Java Socket and ServerSocket.

`cljque.zeromq` uses ZeroMQ sockets.



Installing ZeroMQ
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
