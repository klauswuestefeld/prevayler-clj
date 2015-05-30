# prevayler-clj

```
(require '[org.prevayler :refer [prevayler!]])

(let [my-file (java.io.File. "my-file")
      my-handler (fn [state event] (conj state event)) ; Any function
      initial-state []]                                ; Any value.
  
  (with-open [p1 (prevayler! my-handler initial-state my-file)]
    (assert (= @p1 initial-state))
    (handle! p1 "Event A")
    (handle! p1 "Event B")
    (assert (= @p1 ["Event A" "Event B"])))

  (with-open [p2 (prevayler! my-handler initial-state my-file)]
    (assert (= @p2 ["Event A" "Event B"]))))
```
