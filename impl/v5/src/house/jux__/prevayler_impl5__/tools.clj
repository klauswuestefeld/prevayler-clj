(ns house.jux--.prevayler-impl5--.tools
  (:require
   [clojure.java.io :as io]
   [house.jux--.prevayler-impl5--.util :refer [check list-files part-file-ending snapshot-ending]])
  (:import
   [java.io File]
   [java.time Instant YearMonth ZoneOffset]))

(set! *warn-on-reflection* true)

(def ^ZoneOffset tz-utc ZoneOffset/UTC)

(defn- millis->month ^YearMonth [millis]
  (let [local-date (-> millis Instant/ofEpochMilli (.atZone tz-utc) .toLocalDate)]
    (YearMonth/of (.getYear  local-date)
                  (.getMonth local-date))))

(defn files-to-delete
  "Internal use only.
   Receives:
    max-files-to-keep: number of files to keep
    files: seq of files candidates for deletion
    last-modified-fn: an optional override to be used instead of .lastModified (for tests)
  Keeps the newest file for each month for the last max-files-to-keep months. Keeps the oldest file overall if there are less months than max-files-to-keep.
  Returns seq of files to delete (the ones not kept above)."
  [max-files-to-keep files & [last-modified-fn]]
  (check (pos? max-files-to-keep) "At least 1 file must be kept")
  (let [last-modified-fn (or last-modified-fn #(.lastModified ^File %))
        files-in-order (sort-by last-modified-fn files)
        oldest-file    (first files-in-order)
        newest-file    (last  files-in-order)
        month->files   (->> files-in-order
                            (group-by (comp millis->month last-modified-fn)) ; Group by month. Groups retain order.
                            (sort-by key))                                   ; Sort [month files-in-order] pairs by month
        kept-files (->> month->files
                        (map (comp last val)) ; Newest file from each month
                        (cons oldest-file)    ; Oldest file overall will be kept if there are not sufficient files from newer months.
                        (take-last max-files-to-keep))]
    (check (= (last kept-files) newest-file) "Newest backup file (" newest-file ") and last kept file (" (last kept-files) ") should have been the same.") ; Defensive sanity check
    (remove (set kept-files) files-in-order)))

(defn- delete-snapshot-part-files! [dir]
  (->> (list-files dir (str snapshot-ending part-file-ending))
       (run! #(.delete ^File %))))

(defn- delete-snapshot-files! [dir options]
  (let [max-snapshots-to-keep (:keep options)]
    (->> (snapshots dir)
         (files-to-delete max-snapshots-to-keep)
         (run! #(.delete ^File %)))))

(defn delete-old-snapshots!
  "Deletes all snapshot .part files and deletes all but the newest snapshot files.
   Receives options with the number of snapshot files to keep. Example: {:keep 3}"
  [dir & [options]]
  (let [^File dir (io/file dir)]
    (delete-snapshot-part-files! dir)
    (delete-snapshot-files! dir options)))
