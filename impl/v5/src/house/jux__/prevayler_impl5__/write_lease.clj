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

                                        ; TODO: Implement

  )

(defn check!
  "Throws an exception with a helpful message in these situations:
     - The lock file has been deleted (normally by another owner taking over).
     - The last attempt to refresh the lease (rename our lock file) has failed and we cannot be certain whether the file still exists or has been deleted."
  [my-lease]

  ; TODO: Implement

  )
