(ns prevayler-test
  (:require
   [house.jux--.prevayler-transient-- :as subject]
   [house.jux--.prevayler-- :refer [handle! timestamp]]
   [midje.sweet :refer [facts fact throws =>]]))

(def initial-state {:contacts []})

(defn- contact-list
  "Our contact list business-fn."
  [state event timestamp]
  (when (= event "boom")
    (throw (RuntimeException.))) ; Simulate a bug.
  (-> state
      (update :contacts conj event)
      (assoc :last-timestamp timestamp)))

(facts "About prevalence"
  (with-open [p (subject/prevayler! {:initial-state initial-state
                                     :business-fn contact-list})]
    (fact "A counter is used as the default timestamp-fn"
      (handle! p "Ann")
      (:last-timestamp @p) => 1
      (handle! p "Ann")
      (:last-timestamp @p) => 2))
       
  (let [prev! #(subject/prevayler! {:initial-state initial-state
                                    :business-fn contact-list})]
    (fact "The timestamp is accessible."
      (with-open [p (prev!)]
        (timestamp p) => 1))

    (fact "First run uses initial state"
      (with-open [p (prev!)]
        (:contacts @p) => []))

    (fact "Events are handled by business-fn"
      (with-open [p (prev!)]
        (:contacts @p) => []
        (handle! p "Ann") => {:contacts ["Ann"]
                              :last-timestamp 1}
        (:contacts @p) => ["Ann"]
        (handle! p "Bob")
        (:contacts @p) => ["Ann" "Bob"]

        (fact "Exception during event handle doesn't affect state"
          (handle! p "boom") => (throws RuntimeException)
          (:contacts @p) => ["Ann" "Bob"]
          (handle! p "Cid")
          (:contacts @p) => ["Ann" "Bob" "Cid"])))))

; (do (require 'midje.repl) (midje.repl/autotest))
