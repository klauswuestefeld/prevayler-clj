(ns tools.cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [house.jux--.prevayler-impl5--.tools :as sut])
  (:import (java.time LocalDate ZoneOffset)))

(deftest no-files
  (let [max-files-to-keep 3
        no-files []]
    (is (= []
           (sut/files-to-delete max-files-to-keep no-files)))))


(defn- ->millis [^String ymd] ; Interpret YYYY-MM-DD as midnight UTC
  (-> ymd LocalDate/parse (.atStartOfDay ZoneOffset/UTC) .toInstant .toEpochMilli))

(defn- ->file [ymd]
  {:name ymd
   :last-modified (->millis ymd)})

(defn- run-scenario [max-files-to-keep steps]
  (loop [current-files []
         [[file-created expected-kept] & next-steps] steps]
    (when file-created
      (let [current-files' (cons (->file file-created) current-files)
            delete? (->> (sut/files-to-delete max-files-to-keep current-files' :last-modified)
                         set)
            current-files' (remove delete? current-files')]
        (is (= expected-kept (->> current-files' (map :name) sort)))
        (recur current-files' next-steps)))))

(deftest keep-2-files
  (run-scenario
   2
   ; FILE CREATED   EXPECTED FILES KEPT
   [["2024-01-10"  ["2024-01-10"]]               ; Oldest overall = Jan newest
    ["2024-01-20"  ["2024-01-10" "2024-01-20"]]  ; Oldest overall, Jan newest
    ["2024-02-05"  ["2024-01-20" "2024-02-05"]]  ; Jan newest, Feb newest
    ["2024-02-25"  ["2024-01-20" "2024-02-25"]]  ; Feb newest updated
    ["2024-03-01"  ["2024-02-25" "2024-03-01"]]  ; Feb newest, Mar newest
    ["2024-05-01"  ["2024-03-01" "2024-05-01"]]  ; No files were created in Apr.
    ]))

(deftest keep-3-files
  (run-scenario
   3
   ; FILE CREATED   EXPECTED FILES KEPT
   [["2024-01-10"  ["2024-01-10"]]                            ; Oldest overall = Jan newest
    ["2024-01-20"  ["2024-01-10" "2024-01-20"]]               ; Oldest overall, Jan newest
    ["2024-02-05"  ["2024-01-10" "2024-01-20" "2024-02-05"]]  ; Oldest overall, Jan newest, Feb newest
    ["2024-02-25"  ["2024-01-10" "2024-01-20" "2024-02-25"]]  ; Feb newest updated
    ["2024-03-01"  ["2024-01-20" "2024-02-25" "2024-03-01"]]  ; Jan newest (oldest overall no longer forced), Feb newest, Mar newest
    ["2024-04-15"  ["2024-02-25" "2024-03-01" "2024-04-15"]]  ; Newest: Feb, Mar, Apr
    ["2024-05-01"  ["2024-03-01" "2024-04-15" "2024-05-01"]]  ; Newest: Mar, Apr, May
    ["2024-05-20"  ["2024-03-01" "2024-04-15" "2024-05-20"]]  ; May newest updated
    ["2024-07-01"  ["2024-04-15" "2024-05-20" "2024-07-01"]]  ; No files were created in Jun.
    ]))
