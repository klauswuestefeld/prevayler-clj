(ns house.jux--.prevayler-impl5--.storage)

(defprotocol Storage
  "Storage mechanism for snapshots and journaled events."

  (latest-journal! [this default-state]
    "Return a map with:
       :snapshot - The state saved in the last snapshot. Defaults to default-state.
       :events - A lazy sequence of all events after that snapshot.
     Must be called before the first invocation of append-to-journal!")

  (append-to-journal! [this event]
    "Serializes event (opaque value) and appends it to some reasonably durable journal.
     Throws on failure.")

  (start-taking-snapshot! [this state]
    "Serializes state (opaque value) and stores it as a snapshot asynchronously.
     Returns an IDeref that resolves to :done or throws on error."))

; TODO: Be resilient to errors.
