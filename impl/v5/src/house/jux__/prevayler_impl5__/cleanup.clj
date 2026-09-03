(ns house.jux--.prevayler-impl5--.cleanup
  (:require
   [clojure.java.io :as io]
   [house.jux--.prevayler-impl5--.util :refer [check delete! filename-number journals-sorted list-files part-file-ending snapshot-ending snapshots-sorted]])
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

(defn- delete-printing! [description ^File file]
  (println (str "Deleting " description ": " file))
  (delete! file))

(defn- delete-snapshot-part-files! [dir]
  (->> (list-files dir (str snapshot-ending part-file-ending))
       (run! (partial delete-printing! "incomplete snapshot"))))

(defn- delete-snapshot-files! [dir max-snapshots-to-keep]
  (->> (snapshots-sorted dir)
       (files-to-delete max-snapshots-to-keep)
       (run! (partial delete-printing! "old snapshot"))))

(defn delete-old-snapshots!
  "Deletes all snapshot .part files and deletes all but the newest snapshot files.
   Receives options with the number of snapshot files to keep. Example: {:keep 3}"
  [dir {max-snapshots-to-keep :keep}]
  (let [^File dir (io/file dir)]
    (delete-snapshot-part-files! dir)
    (delete-snapshot-files! dir max-snapshots-to-keep)))

(defn journals-to-delete
  "Internal use only.
   Receives:
    dir: the prevayler directory
    bytes-needed: number of bytes that must be free
    bytes-usable: number of bytes currently free (received instead of read from dir, for easier testing)
    oldest-snapshot-number: only journals with a smaller file number are candidates
  Returns seq of journals to delete, oldest first: just enough of them to make bytes-needed available."
  [dir bytes-needed bytes-usable oldest-snapshot-number]
  (loop [[journal & remaining] (->> (journals-sorted dir)
                                    (filter #(< (filename-number %) oldest-snapshot-number)))
         usable bytes-usable
         result []]
    (if (and journal (< usable bytes-needed))
      (recur remaining (+ usable (.length ^File journal)) (conj result journal))
      result)))

(defn delete-old-journals!
  "Deletes old journal files to make space for new snapshots.
   Deletes nothing when there are no snapshots. Only journals older (smaller file number) than the oldest
   snapshot are deleted, oldest first, and only as many as necessary to make room for the given number of
   snapshots the size of the newest one.
   Receives options with the number of snapshots to make space for. Example: {:snapshot-count-to-make-space-for 3}"
  [dir {snapshot-count :snapshot-count-to-make-space-for}]
  (check (and (number? snapshot-count) (pos? snapshot-count)) "Space must be made for at least 1 snapshot")
  (let [^File dir (io/file dir)]
    (when-let [snapshots (seq (snapshots-sorted dir))]
      (let [^File newest-snapshot (last snapshots)
            bytes-needed (* snapshot-count (.length newest-snapshot))]
        (->> (journals-to-delete dir bytes-needed (.getUsableSpace dir) (filename-number (first snapshots)))
             (run! (partial delete-printing! "old journal")))))))
