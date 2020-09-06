## prevayler-clj/prevayler4

- Timestamps: Events are now timestamped with a `timestamp-fn` that you can pass in when creating Prevayler. It defaults to System/currentTimeMillis. Timestamps are now passed as the third argument to the business function.
- New State: The business function simply returns the new state now. It no longer needs to return a vector with new-state and event-result. If you want to return an event-result, simply assoc it to returned new-state.
