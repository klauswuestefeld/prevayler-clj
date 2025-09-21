(ns house.jux--.prevayler-impl5--.cleanup
  (:require
   [clojure.java.io :as io]
   [house.jux--.prevayler-impl5--.util :refer [check sorted-files]])
  (:import
   [java.io File]
   [java.time Instant LocalDate YearMonth ZoneOffset]))

(set! *warn-on-reflection* true)

(def filename-number-mask "%09d")
(def journal-ending   ".journal5")
(def snapshot-ending  ".snapshot5")
(def part-file-ending ".part")


(defn delete-old-snapshots!
  "Deletes all .part files and deletes all but the newest snapshot files. Receives options with the number of snapshot files to keep. Example: {:keep 2}"
  [dir & [options]]
  (let [^File dir (io/file dir)
        snapshots-to-keep (-> (:keep options) (or 1) (max 1))]
    (->>
     (sorted-files dir snapshot-ending)
     (drop-last snapshots-to-keep)
     (concat (sorted-files dir part-file-ending))
     (run! #(.delete ^File %)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(def ^ZoneOffset tz-utc ZoneOffset/UTC)

(defn- file->month ^YearMonth [file]
  (let [local-date (-> file :lastModified Instant/ofEpochMilli (.atZone tz-utc) .toLocalDate)]
    (YearMonth/of (.getYear  local-date)
                  (.getMonth local-date))))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn choose-backup-names
  "Receives:
    max-files-to-keep: number of files to keep
    files: seq of {:name (String)
                   :lastModified (millis)}
  Keeps the newest file for each month for the last max-files-to-keep months. Includes the oldest file if there are less months than max-files-to-keep.
  Returns {:keep   [names of files to keep]
           :delete [names of files to delete]}."
  [max-files-to-keep files]
  (check (pos? max-files-to-keep) "At least 1 backup file must be kept")
  (let [sorted-files   (sort-by :lastModified files)
        oldest-file    (first sorted-files)
        month->files   (->> sorted-files
                            (group-by file->month) ; Group by month
                            (sort-by key))         ; Sort by month
        kept-files-set (->> month->files
                            (map (comp last val)) ; Newest file from each month
                            (cons oldest-file)    ; Oldest will be kept if there are not sufficient files from newer months.
                            (take-last max-files-to-keep)
                            set)
        kept-names     (->> sorted-files (filter kept-files-set) (map :name))
        deleted-names  (->> sorted-files (remove kept-files-set) (map :name))
        newest-file    (last sorted-files)]
    (check (-> (count kept-names) (+ (count deleted-names)) (= (count files))) "Backup file count mismatch")
    (check (= (:name newest-file) (last kept-names)) "Newest backup file (" (:name newest-file) ") should have been the last kept file (" (last kept-names) ")")
    {:keep kept-names
     :delete deleted-names}))
