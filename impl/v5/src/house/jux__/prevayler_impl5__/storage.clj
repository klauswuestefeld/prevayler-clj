(ns house.jux--.prevayler-impl5--.storage)

(defprotocol Storage
  "Storage mechanism for snapshots and journaled events."

  (latest-snapshot! [this]
    "Returns the most recent snapshot, or nil if none exists.
     Must be called first and only once.
     Throws on error.")

  (latest-journaled-events! [this]
    "Returns a lazy sequence of all events journaled after the latest snapshot.
     Must be called after 'latest-snapshot' and only once.
     Throws on error.
     Returned lazy seq also throws on errors during realization.")

  (append-to-journal! [this event]
    "Serializes event (opaque value) and appends it to some reasonably durable journal.
     Throws on failure.")

  (start-taking-snapshot! [this state]
    "Serializes state (opaque value) and stores it as a snapshot asynchronously.
     Returns an IDeref that resolves to :done or throws on error."))

; TODO: Be resilient to errors.