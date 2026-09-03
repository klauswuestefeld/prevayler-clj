(ns tools.cleanup-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [house.jux--.prevayler-impl5--.cleanup :as sut])
  (:import (java.io File)
           (java.time LocalDate ZoneOffset)))

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


(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- ->journal! [dir number size]
  (spit (io/file dir (format "%09d.journal5" number))
        (apply str (repeat size \x))))  ; 1 byte per char.

(defn- journals-to-delete [dir bytes-needed bytes-usable oldest-snapshot-number]
  (->> (sut/journals-to-delete dir bytes-needed bytes-usable oldest-snapshot-number)
       (map #(.getName ^File %))))

(deftest journals-to-delete-test
  (let [dir (tmp-dir)]

    (testing "No journals at all"
      (is (= [] (journals-to-delete dir 10000 0 5))))

    (->journal! dir 0 100)
    (->journal! dir 1 100)
    (->journal! dir 2 100)
    (->journal! dir 3 100) ; Not a candidate: same number as the oldest snapshot.
    (->journal! dir 4 100) ; Not a candidate: newer than the oldest snapshot.
    (let [oldest-snapshot-number 3]

      (testing "No space is needed"
        (is (= [] (journals-to-delete dir 0 0 oldest-snapshot-number))))

      (testing "Enough space is already free"
        (is (= [] (journals-to-delete dir 50 100 oldest-snapshot-number))))

      (testing "One journal is enough"
        (is (= ["000000000.journal5"]
               (journals-to-delete dir 100 0 oldest-snapshot-number))))

      (testing "Deletion stops as soon as there is enough space"
        (is (= ["000000000.journal5" "000000001.journal5"] ; Journal 2 is a candidate but is no longer necessary.
               (journals-to-delete dir 150 0 oldest-snapshot-number))))

      (testing "Already free space counts"
        (is (= ["000000000.journal5"]
               (journals-to-delete dir 250 150 oldest-snapshot-number))))

      (testing "More space is needed than the candidates can free"
        (is (= ["000000000.journal5" "000000001.journal5" "000000002.journal5"] ; Journals 3 and 4 are not candidates.
               (journals-to-delete dir 10000 0 oldest-snapshot-number)))))

    (testing "No journal is older than the oldest snapshot"
      (is (= [] (journals-to-delete dir 10000 0 0))))))
