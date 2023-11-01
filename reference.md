## Inconsistent State Detected

From version 5 onwards, Prevayler saves, along with each event in the journal, the [Clojure hash](https://clojuredocs.org/clojure.core/hash) of the state produced by that event to check state consistency during journal restoration.

If you are getting an `Inconsistent State Detected` exception when starting your system and replaying your journal, that means the state produced by an event is not the same as the first time that event was handled. This could [unravel](https://en.wikipedia.org/wiki/Butterfly_effect) into serious inconsistencies if not checked.

Possible reasons for the error:

 1. Your `business-fn` is not a pure, referentially transparent function, [as it should be](https://github.com/klauswuestefeld/prevayler-clj/tree/master#usage). It might be running [non-deterministic code](https://stackoverflow.com/a/17626306/3343799).
    - Impact: You might have lost information you can't recover.
    - Workaround: Recover whatever you can by [reading the journal file](https://github.com/klauswuestefeld/prevayler-clj/blob/master/README.md#file-format) directly. The events are there. You might want to handle them with a modified version of your `business-fn` to recover the most you can.
 2. You are running your system with a different (probably newer) version of the code than the one used when the event was handled the first time. You should always keep your `business-fn` backward compatible with any events that are still present in your last journal file.
    - Fix: Start your system with the same code that ran when the events were handled the first time. Make sure no new events are reaching your system while you do this. This will handle all the events in the journal and produce a new journal containing only the snapshot for the latest state, with no events. You can then stop your system and restart it with the new code.
