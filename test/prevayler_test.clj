(ns prevayler-test
  (:require
    [prevayler4 :refer [prevayler! handle!]]
    [midje.sweet :refer [facts fact => throws]])
  (:import
    [java.io File]))

(def initial-state {:contacts []})

(defn- contact-list
  "Our contact list system function."
  [state event timestamp]
  (when (= event "boom")
    (throw (RuntimeException.))) ; Simulate a bug.
  (-> state
      (update :contacts conj event)
      (assoc :last-timestamp timestamp)))

(defn- tmp-file []
  (doto
    (File/createTempFile "test-" ".tmp")
    (.delete)))

(defn counter [start]
  (let [counter-atom (atom start)]
    #(swap! counter-atom inc)))

(def t0 1598801265000)  ; System/currentTimeMillis at some arbitrary moment in the past.

(facts "About prevalence"
  (with-open [p (prevayler! {:initial-state initial-state
                             :business-fn contact-list})]
    (fact "The System clock is used as the default timestamp-fn"
      (handle! p "Ann")
      (-> @p :last-timestamp (> t0)) => true))
       
  (fact "journal4 is the default file name and it is released after Prevayler is closed (Relevant in Windows)."
    (.delete (File. "journal4")) => true)
       
  (let [counter (counter t0)
        journal (tmp-file)
        prev! #(prevayler! {:initial-state initial-state
                            :business-fn contact-list
                            :timestamp-fn counter ; Timestamps must be controlled while testing.
                            :journal-file journal})]
 
    (fact "First run uses initial state"
      (with-open [p (prev!)]
        (:contacts @p) => []))

    (fact "Restart after no events recovers initial state"
      (with-open [p (prev!)]
        (:contacts @p) => []
        (handle! p "Ann") => {:contacts ["Ann"]
                              :last-timestamp 1598801265001}
        (:contacts @p) => ["Ann"]
        (handle! p "Bob")
        (:contacts @p) => ["Ann" "Bob"]))

    (fact "Restart after some events recovers last state"
      (with-open [p (prev!)]
        (:contacts @p) => ["Ann" "Bob"]))

    (fact "Simulated crash during restart is survived"
      (.renameTo journal (File. (str journal ".backup"))) => true
      (spit journal "#$@%@corruption&@#$@")
      (with-open [p (prev!)]
        (:contacts @p) => ["Ann" "Bob"]))
    
    (fact "Simulated crash during event handle will fall through"
      (with-open [p (prev!)]
        (handle! p "boom") => (throws RuntimeException)
        (:contacts @p) => ["Ann" "Bob"]
        (handle! p "Cid")
        (:contacts @p) => ["Ann" "Bob" "Cid"]))

    (fact "Restart after some crash during event handle recovers last state"
      (with-open [p (prev!)]
        @p => {:contacts ["Ann" "Bob" "Cid"]
               :last-timestamp 1598801265004}))))

; (do (require 'midje.repl) (midje.repl/autotest))
