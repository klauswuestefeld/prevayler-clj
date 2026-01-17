(ns prevayler-test
  (:require
   [house.jux--.prevayler-- :refer [handle! timestamp snapshot!]]
   [house.jux--.prevayler-impl5-- :refer [prevayler! delete-old-snapshots!]]
   [house.jux--.prevayler-impl5--.util :as util]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io File]))

(def initial-state {:contacts []})

(defn- contact-list
  "Our contact list system function."
  [state event timestamp]
  (case event
    "do-nothing" state
    "simulate-a-bug" (throw (RuntimeException.))
    (-> state
        (update :contacts conj event)
        (assoc :last-timestamp timestamp))))

(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(def t0 1600000000000)  ; System/currentTimeMillis at some arbitrary moment in the past.

(defn- file-names [dir ending]
  (->> (util/sorted-by-number dir ending)
       (map #(.getName %))))

(defn- assert-snapshot-journal [dir snapshot-index journal-index]
  (let [last-snapshot-index (-> (util/sorted-by-number dir util/snapshot-ending) last util/filename-number)
        last-journal-index (-> (util/sorted-by-number dir util/journal-ending) last util/filename-number)]
    (is (= snapshot-index last-snapshot-index))
    (is (= journal-index last-journal-index))))

(deftest prevayler!-test
  (let [prevayler-dir (tmp-dir)]
    (testing "The System clock is used as the default timestamp-fn"
      (with-open [p (prevayler! {:initial-state initial-state
                                 :business-fn contact-list
                                 :dir prevayler-dir})]
        (handle! p "Ann")
        (is (-> @p :last-timestamp (> t0)))))
    (assert-snapshot-journal prevayler-dir 0 0)
    (testing "journal5 is the default file name and it is released after Prevayler is closed (Relevant in Windows)."
      (is (.delete (File. prevayler-dir "000000000.journal5")))))

  (let [prevayler-dir (tmp-dir)
        counter-atom (atom t0)
        options {:initial-state initial-state
                 :business-fn contact-list
                 :timestamp-fn #(swap! counter-atom inc)
                 :sleep-period 10000 ; TODO reduce this number when we fix infinite loop
                 :dir prevayler-dir}
        prev! #(prevayler! options)]

    (testing "The timestamp is accessible."
      (with-open [p (prev!)]
        (is (= 1600000000001 (timestamp p)))))

    (testing "First run uses initial state"
      (with-open [p (prev!)]
        (is (= [] (:contacts @p)))))

    (testing "Restart after no events recovers initial state"
      (with-open [p (prev!)]
        (is (= [] (:contacts @p)))
        (is (= {:contacts ["Ann"] :last-timestamp 1600000000002}
               (handle! p "Ann")))
        (is (= ["Ann"] (:contacts @p)))
        (handle! p "Bob")
        (is (= ["Ann" "Bob"] (:contacts @p)))))

    (assert-snapshot-journal prevayler-dir 0 0)

    (testing "Restart after some events recovers last state"
      (with-open [p (prev!)]
        (is (= ["Ann" "Bob"] (:contacts @p)))))

    (assert-snapshot-journal prevayler-dir 0 0)

    (testing "Events that don't change the state are not journalled"
      (with-open [p (prev!)]
        (is (= ["Ann" "Bob"] (:contacts (handle! p "do-nothing"))))))

    (assert-snapshot-journal prevayler-dir 0 0)

    (testing "Exception during event handle doesn't affect state"
      (with-open [p (prev!)]
        (is (thrown? RuntimeException (handle! p "simulate-a-bug")))
        (is (= ["Ann" "Bob"] (:contacts @p)))
        (handle! p "Cid")
        (is (= ["Ann" "Bob" "Cid"] (:contacts @p)))))

    (assert-snapshot-journal prevayler-dir 0 1)

    (testing "Restart after some crash during event handle recovers last state"
      (with-open [p (prev!)]
        (is (= {:contacts ["Ann" "Bob" "Cid"]
                :last-timestamp 1600000000006}
               @p))))

    (assert-snapshot-journal prevayler-dir 0 1)

    (testing "exception during replay is not swallowed"
      (with-out-str
        (is (thrown? IllegalStateException (prevayler! (assoc options :business-fn (fn [_ _ _] (throw (IllegalStateException. "test")))))))))

    (assert-snapshot-journal prevayler-dir 0 1)

    (testing "snapshot! saves the state and previous journals are not replayed"
      (with-open [p (prev!)]
        (handle! p "Dan")
        (snapshot! p))
      (with-open [p (prevayler! (assoc options :business-fn (fn [_ _ _]
                                                              (throw (IllegalStateException. "Should not happen")))))]
        (is (= {:contacts ["Ann" "Bob" "Cid" "Dan"]
                :last-timestamp 1600000000007}
               @p))))

    (assert-snapshot-journal prevayler-dir 3 2)

    (testing "Journaling continues after snapshot"
      (with-open [p (prev!)]
        (handle! p "Edd")
        (snapshot! p)
        (handle! p "Flo"))
      (with-open [p (prev!)]
        (handle! p "Gil")))

    (assert-snapshot-journal prevayler-dir 4 5)

    (testing "Corrupted snapshot can be deleted"
      (let [snapshot-file (File. prevayler-dir "000000004.snapshot5")]
        (is (.exists snapshot-file))
        (spit snapshot-file "...corruption...")
        (with-out-str
          (is (thrown? clojure.lang.ExceptionInfo (prev!))))
        (.delete snapshot-file))
      (with-open [p (prev!)]
        (is (= ["Ann" "Bob" "Cid" "Dan" "Edd" "Flo" "Gil"] (:contacts @p)))))

    (testing "Old snapshot deletion"
      (let [part-file (File. prevayler-dir "000000000.snapshot5.part")]
        (spit part-file "anything"))
      (is (= ["000000000.snapshot5.part"]
             (file-names prevayler-dir ".snapshot5.part")))

      (delete-old-snapshots! prevayler-dir {:keep 3})
      (is (= []
             (file-names prevayler-dir ".snapshot5.part"))) ; All .snapshot5.part files were deleted.
      (is (= ["000000000.snapshot5" "000000003.snapshot5"]
             (file-names prevayler-dir ".snapshot5")))

      (delete-old-snapshots! prevayler-dir {:keep 2})
      (is (= ["000000000.snapshot5" "000000003.snapshot5"]
             (file-names prevayler-dir ".snapshot5")))

      (delete-old-snapshots! prevayler-dir {:keep 1})
      (is (= ["000000003.snapshot5"]
             (file-names prevayler-dir ".snapshot5")))

      (is (thrown? RuntimeException
                   (delete-old-snapshots! prevayler-dir {:keep 0}))) ; At least one must be kept
      
      (with-open [p (prev!)]
        (is (= ["Ann" "Bob" "Cid" "Dan" "Edd" "Flo" "Gil"] (:contacts @p)))))
    
    (testing "Old journal files can be deleted"
      (is (.delete (File. prevayler-dir "000000000.journal5")))
      (is (.delete (File. prevayler-dir "000000001.journal5"))) 
      (is (.delete (File. prevayler-dir "000000002.journal5"))) 
      (with-open [p (prev!)]
        (is (= ["Ann" "Bob" "Cid" "Dan" "Edd" "Flo" "Gil"] (:contacts @p)))))))
