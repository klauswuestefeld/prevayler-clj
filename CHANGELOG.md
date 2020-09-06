## prevayler-clj/prevayler4

- Timestamps: Events are now timestamped with a `timestamp-fn` that you can pass in when creating Prevayler. It defaults to System/currentTimeMillis. Timestamps are now passed as the third argument to the business function.
- New State: The business function simply returns the new state now. It no longer needs to return a vector with new-state and event-result. If you still want to return an event-result, simply assoc it to returned new-state.

### Migrating from Prevayler 3
- Add Prevayler 4 to your project along with Prevayler 3. They have different package names and use different journal file names by default.
- Simply recover your state from Prevayler 3 and then use that as the initial-state the first time you start Prevayler 4.
