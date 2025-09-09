(ns house.jux--.prevayler--)

(defprotocol ^:export Prevayler
  "Impls of this protocol must also implement clojure.lang.IDeref and java.io.Closeable"
  (handle! [this event] "Journals the event, applies the business function to the state and the event; and returns the new state.")
  (snapshot! [this] "Creates a snapshot of the current state.")
  (timestamp [this] "Calls the timestamp-fn"))
