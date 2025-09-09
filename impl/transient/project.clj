(defproject house.jux/prevayler-transient "2025.09.11"

  :description "Transient implementation of Prevayler for tests"

  :license {:name "BSD 3-Clause"
            :url "https://github.com/klauswuestefeld/simple-clj/blob/master/LICENSE"}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [house.jux/prevayler "2025.09.11"]]
  
  :profiles {:dev {:dependencies [[midje "1.9.9"]]
                   :plugins [[lein-midje "3.1.3"]]}})
