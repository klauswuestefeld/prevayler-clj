# prevayler-clj

- Get enough RAM to hold all you data.
- Model your business system as an event handling function.
- Use prevayler-clj like this to guarantee persistence:
```
(let [my-file (File. "my-file")
      my-handler (fn [state event] (str state "Event:" event " "))
      initial-state ""]
  
  (with-open [p1 (prevayler! my-handler initial-state my-file)]
    (assert (= @p1 initial-state))
    (handle! p1 "A")
    (handle! p1 "B")
    (assert (= @p1 "Event:A Event:B ")))

  (with-open [p2 (prevayler! my-handler initial-state my-file)]
    (assert (= @p2 "Event:A Event:B "))))
```
