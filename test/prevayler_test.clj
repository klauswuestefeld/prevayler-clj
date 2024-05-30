(ns prevayler-test
  (:require
   [prevayler-clj.prevayler4 :refer [prevayler! handle! timestamp snapshot!]]
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

(defn- tmp-file []
  (doto
   (File/createTempFile "test-" ".tmp")
    (.delete)))

(defn counter [start]
  (let [counter-atom (atom start)]
    #(swap! counter-atom inc)))

(def t0 1598800000000)  ; System/currentTimeMillis at some arbitrary moment in the past.

(deftest prevayler!-test
  (testing "The System clock is used as the default timestamp-fn"
    (with-open [p (prevayler! {:initial-state initial-state
                               :business-fn contact-list})]
      (handle! p "Ann")
      (is (-> @p :last-timestamp (> t0)))))
  (testing "journal4 is the default file name and it is released after Prevayler is closed (Relevant in Windows)."
    (is (.delete (File. "journal4"))))

  (let [counter (counter t0)
        journal (tmp-file)
        options {:initial-state initial-state
                 :business-fn contact-list
                 :timestamp-fn counter ; Timestamps must be controlled while testing.
                 :journal-file journal}
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
        (let [previous-length (.length journal)]
          (is (= ["Ann" "Bob"] (:contacts (handle! p "do-nothing"))))
          (is (= previous-length (.length journal))))))
    (testing "Simulated crash during restart is survived"
      (is (.renameTo journal (File. (str journal ".backup"))))
      (spit journal "#$@%@corruption&@#$@")
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
    (testing "Restart with inconsistent business-fn throws exception"
      (with-open [p (prev!)]
        (handle! p "Dan"))
      (with-out-str
        (is (thrown? IllegalStateException (prevayler! (assoc options :business-fn (constantly "rubbish")))))))
    (testing "snapshot! starts new journal with current state (business function is never called during start up)"
      (with-open [p (prev!)]
        (handle! p "Edd")
        (snapshot! p))
      (with-open [p (prevayler! (assoc options :business-fn (constantly "rubbish")))]
        (is (= {:contacts ["Ann" "Bob" "Cid" "Dan" "Edd"]
                :last-timestamp 1598800000008}
               @p))))))
