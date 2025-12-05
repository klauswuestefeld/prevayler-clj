(ns house.jux--.prevayler-impl5--.storage)

(defprotocol Storage
  "Storage mechanism for snapshots and journaled events."

  (latest-journal! [this default-state]
    "Return a map with:
       :snapshot - The state saved in the last snapshot. Defaults to default-state.
       :events - A lazy sequence of all events after that snapshot.
     Must be called only once.")

  (append-to-journal! [this event]
    "Serializes event (opaque value) and appends it to some reasonably durable journal.
     Throws on failure.
     Must be called after the latest-journal events have been consumed.
     Must not be called concurrently with start-taking-snapshot! (caller must synchronize externally) because this Storage does not have information to properly sequence them.")

  (start-taking-snapshot! [this state]
    "Serializes state (opaque value) and stores it as a snapshot asynchronously.
     Returns an IDeref that resolves to :done or throws on error.
     Must be called after the latest-journal! events have been consumed.
     Must not be called concurrently with append-to-journal! (caller must synchronize externally) because this Storage does not have information to properly sequence them."))
