(ns cleanup-test
  (:require [clojure.test :refer [deftest is]]
            [house.jux--.prevayler-impl5--.cleanup :as sut])
  (:import (java.time LocalDate ZoneOffset)))

(defn- ->millis [^String ymd] ; Interpret YYYY-MM-DD as midnight UTC
  (-> ymd LocalDate/parse (.atStartOfDay ZoneOffset/UTC) .toInstant .toEpochMilli))

(defn- ->file [ymd]
  {:name ymd
   :lastModified (->millis ymd)})

(deftest backup-retention
  (let [max-files-to-keep 3
        ;;      FILE CREATED   EXPECTED FILES KEPT
        steps [["2024-01-10"   ["2024-01-10"]]                             ; Oldest overall = Jan newest
               ["2024-01-20"   ["2024-01-10" "2024-01-20"]]                ; Oldest overall, Jan newest
               ["2024-02-05"   ["2024-01-10" "2024-01-20" "2024-02-05"]]   ; Oldest overall, Jan newest, Feb newest
               ["2024-02-25"   ["2024-01-10" "2024-01-20" "2024-02-25"]]   ; Feb newest updated
               ["2024-03-01"   ["2024-01-20" "2024-02-25" "2024-03-01"]]   ; Jan newest (oldest overall no longer forced), Feb newest, Mar newest
               ["2024-04-15"   ["2024-02-25" "2024-03-01" "2024-04-15"]]   ; Newest: Feb, Mar, Apr
               ["2024-05-01"   ["2024-03-01" "2024-04-15" "2024-05-01"]]   ; Newest: Mar, Apr, May
               ["2024-05-20"   ["2024-03-01" "2024-04-15" "2024-05-20"]]   ; May newest updated
               ["2024-07-01"   ["2024-04-15" "2024-05-20" "2024-07-01"]]]] ; Last 3 existing months: Apr, May, Jul. No files were created in Jun
        (loop [files []
               [[file-created expected-kept] & next-steps] steps]
           (when file-created
             (let [files' (conj files (->file file-created))
                   kept   (:keep (sut/choose-backup-names max-files-to-keep files'))]
               (is (= expected-kept kept))
               (recur files' next-steps))))))

(deftest no-files
  (let [max-files-to-keep 3
        no-files []]
   (is (= []
          (:keep (sut/choose-backup-names max-files-to-keep no-files))))))
