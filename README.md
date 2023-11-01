[![Clojars Project](http://clojars.org/prevayler-clj/prevayler5/latest-version.svg)](http://clojars.org/prevayler-clj/prevayler5)

## Feature

Write your business logic as a pure event-handling Clojure function, without any database complexity. It will be much simpler and orders of magnitude faster.

Prevayler takes care of persistence.

See what's [new in Prevayler4 and Prevayler5](CHANGELOG.md).

## Usage

- Get enough RAM to hold all your data.
- Implement the business logic of your system as a [PURE](https://en.wikipedia.org/wiki/Deterministic_algorithm) event handling function. Keep any I/O logic (accessing some web service, for example) separate.
- Guarantee persistence by applying all events to your business system through Prevayler, like this:

```clojure
(defn my-business [state event timestamp]            
  ...)                                   ; Your business logic as a pure function. Returns the new state with event applied.

(with-open [p1 (prevayler! {:business-fn my-business})]
  (assert (= @p1 {}))                    ; The default initial state is an empty map.
  (handle! p1 event1)                    ; Your events can be any Clojure value or Serializable object.
  (handle! p1 event2)
  (assert (= @p1 new-state)))            ; Your system state with the events applied.

(with-open [p2 (prevayler! my-business)] ; Next time you run,
  (assert (= @p2 new-state)))            ; the state is recovered, even if there was a system crash.
```

## How it Works

Prevayler-clj implements the [system prevalence pattern](http://en.wikipedia.org/wiki/System_Prevalence): it keeps a file with a snapshot of your business state followed by a journal of events. On startup or crash recovery it reads the last snapshot and reapplies all events since: your business state is restored to where it was.

## Shortcomings

- RAM: Requires enough RAM to hold all the data in your business state.
- Start-up time: Entire state is read into RAM.

## File Format

Prevayler's default file name is `journal4` but you can pass in your own file (see tests). Prevayler-clj will create and write to it like this:

### journal4
Contains the state at the moment your system was last started, followed by all events since. Serialization is done using [Nippy](https://github.com/ptaoussanis/nippy).

### journal4.backup
On startup, the journal is renamed to `journal4.backup` and a new `journal4` file is created.
This new journal will only be consistent after the business state has been written to it so when `journal4.backup` exists, it takes precedence over `journal4`.

### journal4.backup-[timestamp]
After a new consistent journal is written, `journal4.backup` is renamed with a timestamp appendix. You can keep these old files elsewhere if you like. Prevayler no longer uses them.

---

"Simplicity is prerequisite for reliability." Dijkstra (1970)
