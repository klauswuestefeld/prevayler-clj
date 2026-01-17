(ns house.jux--.prevayler-impl5--
  (:require
   [house.jux--.prevayler-- :as api]
   [house.jux--.prevayler-impl5--.cleanup :as cleanup]
   [house.jux--.prevayler-impl5--.storage :as storage]
   [house.jux--.prevayler-impl5--.storage.file-directory :as file-directory]
   [house.jux--.prevayler-impl5--.util :as util])
  (:import
   [clojure.lang IDeref]
   [java.io Closeable]))

(set! *warn-on-reflection* true)

;; TODO: move these to storage implementation
(def delete-old-snapshots! cleanup/delete-old-snapshots!)
(defn last-snapshot-file [dir] (last (util/snapshots-sorted dir)))

(defn- restore! [handler initial-state storage]
  (let [{:keys [snapshot events]} (storage/latest-journal! storage initial-state)]
    (reduce
     (fn [acc [timestamp event]]
       (handler acc event timestamp))
     snapshot
     events)))

(defn prevayler! [{:keys [initial-state business-fn timestamp-fn storage]
                   :or {initial-state {}
                        timestamp-fn #(System/currentTimeMillis)}
                   :as opts}]
  (let [storage (or storage (file-directory/open! opts))
        state-atom (atom (restore! business-fn initial-state storage))
        event-monitor (Object.)
        snapshot-monitor (Object.)]
    (reify
      api/Prevayler

      (handle! [_ event]
        (locking event-monitor ; (I)solation: strict serializability.
          (let [state @state-atom
                timestamp (timestamp-fn)
                new-state (business-fn state event timestamp)] ; (C)onsistency: must be guaranteed by the handler. The event won't be journalled nor applied when the handler throws an exception.
            (when-not (identical? new-state state)
              (storage/append-to-journal! storage [timestamp event]) ; (D)urability
              (reset! state-atom new-state))   ; (A)tomicity
            new-state)))

      (snapshot! [_]
        (locking snapshot-monitor
          (-> (locking event-monitor
                (storage/start-taking-snapshot! storage @state-atom))
              deref)))

      (timestamp [_] (timestamp-fn))

      IDeref (deref [_] @state-atom)

      Closeable (close [_] (.close ^java.io.Closeable storage)))))
