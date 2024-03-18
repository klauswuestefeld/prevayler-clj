(defproject prevayler-clj/prevayler4 "2024.03.18"
  :description "Simple, fast, 100% transparent, ACID persistence in Clojure."
  :url "https://github.com/klauswuestefeld/prevayler-clj"
  :license {:name "BSD"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [com.taoensso/nippy "2.15.1"]]
  :profiles {:dev {:dependencies [[midje "1.9.9"]]
                   :plugins [[lein-midje "3.1.3"]]}})
