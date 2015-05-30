# Usage

- Get enough RAM to hold all you data.
- Model your business system as an event handling function.
- Guarantee persistence like this:
```
(let [my-file (File. "my-file")
      my-handler (fn [state event] (str state "Event:" event " "))  ; Any function
      initial-state ""]                                             ; Any value
  
  (with-open [p1 (prevayler! my-handler initial-state my-file)]
    (assert (= @p1 initial-state))
    (handle! p1 "A")                                                ; Your events
    (handle! p1 "B")
    (assert (= @p1 "Event:A Event:B ")))

  (with-open [p2 (prevayler! my-handler initial-state my-file)]
    (assert (= @p2 "Event:A Event:B "))))
```

# How it works internally

If your persistence file is called "my-file", prevayler-clj will write files like this for you:

## my-file
A Java serialization object stream with the system state at the moment the file was created followed by events.

## my-file.backup
On startup, my-file is renamed to my-file.backup and a new my-file is created.
This new my-file will only be consistent after the system state has been written to it so if my-file.backup exists, it takes precedence over my-file.

## my-file.backup-[timestamp]
After a new consistent my-file is written, my-file.backup is renamed to this. You can keep these old versions or delete them.
