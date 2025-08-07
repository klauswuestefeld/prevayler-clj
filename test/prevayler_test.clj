(ns prevayler-test
  (:require
   [prevayler-clj.prevayler5 :refer [prevayler! handle! timestamp snapshot!]]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io File]))

(def initial-state {:contacts []})

(defn- contact-list
  "Our contact list system function."
  [state event timestamp]
  (prn state)
  (case event
    "do-nothing" state
    "simulate-a-bug" (throw (RuntimeException.))
    (-> state
        (update :contacts conj event)
        (assoc :last-timestamp timestamp))))

(defn- tmp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn total-file-length [dir & [suffix]]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(.endsWith (.getName %) (or suffix "")))
       (map #(.length %))
       (apply +)))

(defn counter [start]
  (let [counter-atom (atom start)]
    #(swap! counter-atom inc)))

(def t0 1598800000000)  ; System/currentTimeMillis at some arbitrary moment in the past.

(deftest prevayler!-test
  (let [prevayler-dir (tmp-dir)]
    (testing "The System clock is used as the default timestamp-fn"
      (with-open [p (prevayler! {:initial-state initial-state
                                 :business-fn contact-list
                                 :dir prevayler-dir})]
        (handle! p "Ann")
        (is (-> @p :last-timestamp (> t0)))))
    (testing "journal5 is the default file name and it is released after Prevayler is closed (Relevant in Windows)."
      (is (.delete (File. prevayler-dir "000000000.journal5")))))

  (let [counter (counter t0)
        prevayler-dir (tmp-dir)
        options {:initial-state initial-state
                 :business-fn contact-list
                 :timestamp-fn counter ; Timestamps must be controlled while testing.
                 :dir prevayler-dir}
        prev! #(prevayler! options)]
    (testing "The timestamp is accessible."
      (with-open [p (prev!)]
        (is (= 1598800000001 (timestamp p)))))
    (testing "First run uses initial state"
      (with-open [p (prev!)]
        (is (= [] (:contacts @p)))))
    (testing "Restart after no events recovers initial state"
      (with-open [p (prev!)]
        (is (= [] (:contacts @p)))
        (is (= {:contacts ["Ann"] :last-timestamp 1598800000002}
               (handle! p "Ann")))
        (is (= ["Ann"] (:contacts @p)))
        (handle! p "Bob")
        (is (= ["Ann" "Bob"] (:contacts @p)))))
    (testing "Restart after some events recovers last state"
      (with-open [p (prev!)]
        (is (= ["Ann" "Bob"] (:contacts @p)))))
    (testing "Events that don't change the state are not journalled"
      (with-open [p (prev!)]
        (let [previous-length (total-file-length prevayler-dir)]
          (is (= ["Ann" "Bob"] (:contacts (handle! p "do-nothing"))))
          (is (= previous-length (total-file-length prevayler-dir))))))
    #_(testing "Simulated crash during snapshot is survived"
        (spit (File. prevayler-dir "000000000000.snapshot5") "#$@%@corruption&@#$@")
        (with-open [p (prev!)]
          (is (= ["Ann" "Bob"] (:contacts @p)))))
    (testing "Exception during event handle doesn't affect state"
      (with-open [p (prev!)]
        (is (thrown? RuntimeException (handle! p "simulate-a-bug")))
        (is (= ["Ann" "Bob"] (:contacts @p)))
        (handle! p "Cid")
        (is (= ["Ann" "Bob" "Cid"] (:contacts @p)))))
    (testing "Restart after some crash during event handle recovers last state"
      (with-open [p (prev!)]
        (is (= {:contacts ["Ann" "Bob" "Cid"]
                :last-timestamp 1598800000006}
               @p))))
    (testing "exception in replay "
      (with-open [p (prev!)]
        (handle! p "Dan")) ;; this transaction is not necessary for this test
      (with-out-str
        (is (thrown? IllegalStateException (prevayler! (assoc options :business-fn (fn [_ _ _] (throw (IllegalStateException. "test")))))))))
    (testing "snapshot! saves the state"
        (with-open [p (prev!)]
          (handle! p "Edd")
          (snapshot! p))
        ;; TODO fix test
        (with-open [p (prevayler! (assoc options :business-fn (constantly "rubbish")))]
          (is (= {:contacts ["Ann" "Bob" "Cid" "Dan" "Edd"]
                  :last-timestamp 1598800000008}
                 @p))))
    (testing "journals are unaffected by snapshot"
        (with-open [p (prev!)]
          (handle! p "Edd")
          (let [previous-length (total-file-length prevayler-dir "journal5")]
            (snapshot! p)
            (is (= previous-length (total-file-length prevayler-dir "journal5"))))))))
