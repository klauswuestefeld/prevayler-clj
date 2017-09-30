[![Clojars Project](http://clojars.org/prevayler-clj/latest-version.svg)](http://clojars.org/prevayler-clj)

## Features

Prevalence is the fastest possible and third simplest ACID persistence technique, combining the two simplest ones: http://en.wikipedia.org/wiki/System_Prevalence

"Simplicity is prerequisite for reliability." Dijkstra (1970)

## Usage

- Get enough RAM to hold all your data.
- Model your business system as a pure (no I/O) event handling function.
- Guarantee persistence by applying all events to you system through Prevayler, like this:

```clojure
(defn my-system [state event]            
  ...)                                   ; Any function returning a pair [new-state event-result].

(with-open [p1 (prevayler! my-system {})]
  (assert (= @p1 {}))                    ; The default initial state is an empty map.
  (handle! p1 event1)                    ; Your events can be any Clojure value or Serializable object.
  (handle! p1 event2)
  (assert (= @p1 new-state)))            ; Your system state with the events applied.

(with-open [p2 (prevayler! my-system {})]   ; Next time you run,
  (assert (= @p2 new-state)))               ; the state is recovered, even if there was a system crash.
```

## Transient Mode for Tests
The transient-prevayler! function returns a transient prevayler the you can use for fast testing.

## What it Does

Prevayler-clj implements the [system prevalence pattern](http://en.wikipedia.org/wiki/System_Prevalence): it keeps a snapshot of your business system state followed by a journal of events. On startup or crash recovery it reads the last state and reapplies all events since.

## Shortcomings

- RAM: Requires enough RAM to hold all the data in your system.
- Start-up time: Entire system is read into RAM.


## Files

Prevayler's default file name is "journal" but you can pass in your own file. Prevayler-clj will create and write to it like this:

### journal
Contains the state at the moment your system was last started, followed by all events since. Serialization is done using [Nippy](https://github.com/ptaoussanis/nippy).

### journal.backup
On startup, the journal is renamed to journal.backup and a new journal file is created.
This new journal will only be consistent after the system state has been written to it so when journal.backup exists, it takes precedence over journal.

### journal.backup-[timestamp]
After a new consistent journal is written, journal.backup is renamed with a timestamp appendix. You can keep these old versions elsewhere if you like. Prevayler no longer uses them.
