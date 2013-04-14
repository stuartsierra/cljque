* API goals
** Listen for incoming messages
*** Invoke function with message
*** OR invoke function with communications channel, ready to receive
** Receive a message
** Send a message
*** Asynchronous
** Listen for incoming connection requests
*** Invoke function with new communications channel

"Lazy future" or "delay" - does not begin computing its value until you ask for it, either by dereferencing or attending.

"Timeout" - wraps another promise with a timeout. Requires a scheduler!

