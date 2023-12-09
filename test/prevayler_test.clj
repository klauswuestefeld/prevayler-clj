(ns prevayler-test
  (:require
    [prevayler-clj.prevayler5 :refer [prevayler! handle! timestamp]]
    [midje.sweet :refer [facts fact => throws]])
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
        options {:initial-state initial-state
                 :business-fn contact-list
                 :timestamp-fn counter ; Timestamps must be controlled while testing.
                 :journal-file journal}
        prev! #(prevayler! options)]
        
    (fact "The timestamp is accessible."
      (with-open [p (prev!)]
        (timestamp p) => 1598800000001))
    
    (fact "First run uses initial state"
      (with-open [p (prev!)]
        (:contacts @p) => []))

    (fact "Restart after no events recovers initial state"
      (with-open [p (prev!)]
        (:contacts @p) => []
        (handle! p "Ann") => {:contacts ["Ann"]
                              :last-timestamp 1598800000002}
        (:contacts @p) => ["Ann"]
        (handle! p "Bob")
        (:contacts @p) => ["Ann" "Bob"]))

    (fact "Restart after some events recovers last state"
      (with-open [p (prev!)]
        (:contacts @p) => ["Ann" "Bob"]))
    
    (fact "Events that don't change the state are not journalled"
      (with-open [p (prev!)]
        (let [previous-length (.length journal)]
          (:contacts (handle! p "do-nothing")) => ["Ann" "Bob"]
          (.length journal) => previous-length)))
    
    (fact "Simulated crash during restart is survived"
      (.renameTo journal (File. (str journal ".backup"))) => true
      (spit journal "#$@%@corruption&@#$@")
      (with-open [p (prev!)]
        (:contacts @p) => ["Ann" "Bob"]))
    
    (fact "Exception during event handle doesn't affect state"
      (with-open [p (prev!)]
        (handle! p "simulate-a-bug") => (throws RuntimeException)
        (:contacts @p) => ["Ann" "Bob"]
        (handle! p "Cid")
        (:contacts @p) => ["Ann" "Bob" "Cid"]))

    (fact "Restart after some crash during event handle recovers last state"
      (with-open [p (prev!)]
        @p => {:contacts ["Ann" "Bob" "Cid"]
               :last-timestamp 1598800000006}))
    
    (fact "Restart with inconsistent business-fn throws exception"
          (with-open [p (prev!)]
            (handle! p "Dan"))
          (with-out-str
            (prevayler! (assoc options :business-fn (constantly "rubbish"))) => (throws IllegalStateException)))))

; (do (require 'midje.repl) (midje.repl/autotest))
