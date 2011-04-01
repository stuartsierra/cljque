Cljque - experiments in event-based architectures in Clojure

Cljque is pronounced "clique" or "click"

by Stuart Sierra, http://stuartsierra.com/

Copyright (c) Stuart Sierra, 2010. All rights reserved.  The use and
distribution terms for this software are covered by the Eclipse Public
License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can
be found in the file LICENSE.html at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.  You must not remove this notice, or any
other, from this software.


Overview
========================================

Goals: See [Asynchronous Events](http://dev.clojure.org/display/design/Asynchronous+Events) on the Clojure wiki

Modules:

* cljque-parent - the top-level container for other modules
* cljque-base - the core library, with no external dependencies
* cljque-netty - adaptors between the Netty I/O library and Cljque; *currently broken*

Namespaces in cljque-base:

* cljque.observe - core protocols for generators/consumers of asynchronous events
* cljque.push - library of functions, similar to Clojure's sequence API, for "push"-style events
* cljque.schedule - thin layer over Java's ScheduledThreadPoolExecutor


Inspirations / References
========================================

[Netty](http://www.jboss.org/netty)

[Reactive Extensions for .NET (Rx)](http://msdn.microsoft.com/en-us/devlabs/ee794896)

[101 Rx Samples](http://rxwiki.wikidot.com/101samples)

[RxDG] [Rx Design Guidelines](http://blogs.msdn.com/b/rxteam/archive/2010/10/28/rx-design-guidelines.aspx)


Differences from Reactive Extensions for .NET
------------------------------------------------------------

* Rx does not permit any more 'OnNext' events after an 'OnError' event [RxDG sec 4.1]; Cljque permits events to continue after an error.
* Rx promises that event consumers will not be called on multiple threads simultaneously [RxDG sec 4.2]; Cljque requires the addition of Ageets to ensure serialization.
* Rx permits you to specify a Scheduler to execute the callback; Cljque does not