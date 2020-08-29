(ns prevayler-test
  (:require
    [prevayler4 :refer [prevayler! handle!]]
    [midje.sweet :refer [facts fact => throws]])
  (:import
    [java.io File]))

; (do (require 'midje.repl) (midje.repl/autotest))

(defn- handler [state event]
  (when (= event "boom")
    (throw (RuntimeException.)))
  (str state event))

(def initial-state "A")

(defn- tmp-file []
  (doto
    (File/createTempFile "test-" ".tmp")
    (.delete)))

(facts "About prevalence"

  (fact "journal4 is the default file name"
    (->    
      (prevayler! handler initial-state)
      (.close)) 
    (.delete (File. "journal4")) => true)
        
  (let [file (tmp-file)
        prev! #(prevayler! handler initial-state file)]

    (fact "First run uses initial state"
      (with-open [p (prev!)]
        @p => "A"))

    (fact "Restart after no events recovers initial state"
      (with-open [p (prev!)]
        @p => "A"
        (handle! p "B") => "AB"
        @p => "AB"
        (handle! p "C") => "ABC"
        @p => "ABC"))

    (fact "Restart after some events recovers last state"
      (with-open [p (prev!)]
        @p => "ABC"))

    (fact "Simulated crash during restart is survived"
      (.renameTo file (File. (str file ".backup"))) => true
      (spit file "#$@%@corruption&@#$@")
      (with-open [p (prev!)]
        @p => "ABC"))
    
    (fact "Simulated crash during event handle will fall through"
      (with-open [p (prev!)]
        (handle! p "boom") => (throws RuntimeException)
        @p => "ABC"
        (handle! p "D") => "ABCD"))

    (fact "Restart after some crash during event handle recovers last state"
      (with-open [p (prev!)]
        @p => "ABCD"))

    (fact "File is released after Prevayler is closed"
      (.delete file) => true)))
