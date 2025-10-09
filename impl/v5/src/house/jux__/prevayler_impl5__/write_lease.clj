; TODO: Cooperative Consistency Across Multiple Instances Sharing the Same Network Dir
; ====================================================================================
;
; Init
;  Loop until successful:
;    Delete all files inside the "lock" folder, if any.
;    Delete the "lock" folder, if it exists.
;    Create "lock" folder. This is important: I must create it, it can't already exist.
;      Create owner-{uuid}.a file.
;
; Wait 1min, so the old owner, if any, can yield.
; Loop every 30s, to see if I still am the owner: (lease mechanism)
;   Rename lock/owner-{uuid}.a file to lock/owner-{uuid}.b or vice-versa.
;     If success: lastSuccessfulLeaseCheck atom = currentTimeMillis; error atom = nil.
;     If neither file (a nor b) exist: deposed atom = true
;     If some other error: error atom = error message
;    
; Check before every write: Prevayler is free to write if:
;   Not deposed
;   AND Not error
;   AND lastSuccessfulLeaseCheck newer than 40s.
;
;
; TODO: Bonus: Extra Resilience Against Zombie Journal Writes
; -----------------------------------------------------------
;
; Zombie writes are writes arriving late at the server from some old dead owner that were buffered on a temporarily disconnected network client.
; To ignore zombie journal appends:
; 
; For each journal to read:
;   Does a file called {journal-number}.journal-metadata5 exist?
;     No: read the whole journal and write this metadata file with the number of events I just read.
;     Yes: read only as many events as the amount recorded in the metadata file.
;
; To avoid late zombie journal file creation from overriding existing journals:
;   Before creating a new journal file:
;     Create a lock/{journal-number}.journal-lock directory (MKDIR the only atomic mutually-exclusive operation we can trust. Even file renames will override each other.)
;       If it already exists, but the journal file does not, treat the same as an empty journal.

(ns house.jux--.prevayler-impl5--.write-lease)

(set! *warn-on-reflection* true)

(defn dir [lease]
  (-> lease deref :dir))

(defn acquire-for!
  "Acquires a virtual, exclusive lease to write on dir. Implementation:
     - Deletes the lock file of the previous owner, if any. Deletes the 'lock' directory in dir.
     - Creates the 'lock' directory atomically (via rename) in dir with our own lock file in it.
     - Starts to periodically refresh the lease (rename our lock file to make sure it still exists).
   Cooperatively and definitively yields the lease when some other owner deletes our lock file.
   Returns the lease (an opaque handle) to be used with the other fns in this namespace. 
   Calls f when we gain knowledge that some other owner (normally a different machine) has taken over the lease."
  ;; This "opaque handle + fns" smells like a protocol.
  [dir f]
  (atom {:dir dir})
  ;; TODO: Implement
  )

(defn check!
  "Throws an exception with a helpful message in these situations:
     - The lock file has been deleted (normally by another owner taking over).
     - The last attempt to refresh the lease (rename our lock file) has failed and we cannot be certain whether the file still exists or has been deleted."
  [my-lease]

  ;; TODO: Implement

  )
