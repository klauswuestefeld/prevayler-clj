(defproject prevayler-clj "3.0.1"
  :description "Simple, fast, ACID persistence in Clojure."
  :url "https://github.com/klauswuestefeld/prevayler-clj"
  :license {:name "BSD"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [com.taoensso/nippy "2.14.0"]]
  :profiles {:dev {:dependencies [[midje "1.9.9"]]
                   :plugins [[lein-cloverage "1.1.3-SNAPSHOT"]
                              [lein-midje "3.1.3"]]}}

  :repositories [["clojars" { :sign-releases false}]])
