## Advantages

Prevalence is the fastest possible and third simplest ACID persistence technique, combining the two simplest ones: http://en.wikipedia.org/wiki/System_Prevalence

"Simplicity is prerequisite for reliability." Dijkstra (1970)

## Usage

- Get enough RAM to hold all your data.
- Model your business system as a pure (no I/O) event handling function.
- Guarantee persistence by applying all events to you system through Prevayler, like this:
```
(let [my-file (File. "my-file")
      my-handler (fn [state event] (str state "Event:" event " "))  ; Any function
      initial-state ""]                                             ; Any value
  
  (with-open [p1 (prevayler! my-handler initial-state my-file)]
    (assert (= @p1 initial-state))
    (handle! p1 "A")                                                ; Your events
    (handle! p1 "B")
    (assert (= @p1 "Event:A Event:B ")))                            ; Your system state

  (with-open [p2 (prevayler! my-handler initial-state my-file)]
    (assert (= @p2 "Event:A Event:B "))))
```

## What it Does

Prevayler-clj implements the [system prevalence pattern](http://en.wikipedia.org/wiki/System_Prevalence): it keeps a snapshot of your business system state followed by a journal of events. On startup or crash recovery it reads the last state and reapplies all events since.

## Shortcomings

- RAM: Requires enough RAM to hold all the data in your system.
- Start-up time: Entire system is read into RAM.


## Files

If your persistence file is called "my-file", prevayler-clj will write files like this for you:

### my-file
A Java serialization object stream with the system state at the moment the file was created followed by events.

### my-file.backup
On startup, my-file is renamed to my-file.backup and a new my-file is created.
This new my-file will only be consistent after the system state has been written to it so when my-file.backup exists, it takes precedence over my-file.

### my-file.backup-[timestamp]
After a new consistent my-file is written, my-file.backup is renamed to this. You can keep these old versions or delete them.
