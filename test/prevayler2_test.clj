(ns prevayler2-test
  (:require [clojure.test :refer :all]
            [prevayler2 :refer :all])
  (:import [java.io File]))

(defn- tmp-file []
  (doto
    (File/createTempFile "test-" ".tmp")
    (.delete)))

(deftest transient-prevayler-atom-number-test
  (testing "Testing with a transient number"
    (with-open [p (transient-prevayler! 1)]
      (is (= @p 1))
      (is (= (handle! p identity) 1))
      (is (= (handle! p inc) 2))
      (is (= (handle! p inc) 3)))))

(deftest transient-prevayler-map-test
  (testing "testing with a transient map"
    (with-open [p (transient-prevayler! {})]
      (is (= @p {}))
      (is (= (handle! p identity) {}))
      (is (= (handle! p assoc-in [:users :123] {:email "user1@gmail.com"})
            {:users {:123 {:email "user1@gmail.com"}}}))

      (is (= (handle! p assoc-in [:users :124] {:email "user2@gmail.com"})
            {:users {:123 {:email "user1@gmail.com"}
                     :124 {:email "user2@gmail.com"}}}))

      (is (= (handle! p update-in [:users :123 :email] (constantly "user1@teste123.com"))
            {:users {:123 {:email "user1@teste123.com"}
                     :124 {:email "user2@gmail.com"}}})))))

(deftest transient-prevayler-restart-test
  (let [file (tmp-file)
        prev! #(prevayler! {} file)]
    (testing "testing with a persistent map"
      (with-open [p (prev!)]

        (is (= @p {}))
        (is (= (handle! p identity) {}))
        (is (= (handle! p assoc-in [:users :123] {:email "user1@gmail.com"})
              {:users {:123 {:email "user1@gmail.com"}}}))

        (is (= (handle! p assoc-in [:users :124] {:email "user2@gmail.com"})
              {:users {:123 {:email "user1@gmail.com"}
                       :124 {:email "user2@gmail.com"}}}))

        (is (= (handle! p assoc-in [:users :123 :email] "user1@teste123.com")
              {:users {:123 {:email "user1@teste123.com"}
                       :124 {:email "user2@gmail.com"}}}))))

    (testing "Restart after some events recovers last state"
      (with-open [p (prev!)]
        (is (= @p {:users {:123 {:email "user1@teste123.com"}
                           :124 {:email "user2@gmail.com"}}}))))))

(deftest persistent-prevayler-crash
  (let [file (tmp-file)
        prev! #(prevayler! {} file)]
    (testing "testing exceptions with a persistent map"
      (with-open [p (prev!)]
        (is (= @p {}))
        (is (= (handle! p assoc :test 1) {:test 1}))
        (is (= (handle! p assoc :test2 3) {:test 1 :test2 3}))
        (is (= (handle! p assoc :test2 6) {:test 1 :test2 6}))

        (with-redefs [handle-event! (fn [this no-write state-atom fn x y] (throw (RuntimeException.)))]
          (is (thrown? RuntimeException (handle! p assoc :test2 6))))))

    (testing "Restart after some events recovers last state"
      (with-open [p (prev!)]
        (is (= (handle! p assoc :test5 60) {:test 1
                                            :test2 6
                                            :test5 60}))))

    (testing "File is released after Prevayler is closed"
      (is (= (.delete file) true)))))
