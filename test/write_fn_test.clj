(ns write-fn-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str])
  (:import [java.io File FileOutputStream ObjectOutputStream FileInputStream ObjectInputStream]))

(defn tmp-file []
  (File/createTempFile "test-" ".tmp"))

(defn object-out-stream [file]
  (ObjectOutputStream. (FileOutputStream. file)))

(defn object-input-stream [file]
    (ObjectInputStream. (FileInputStream. file)))

(defn write-obj [file obj]
  (with-open [obj-output-stream (object-out-stream file)]
    (.writeObject obj-output-stream obj)))

(defn my-inc [x]
  (inc x))

(deftest write-anonymous-fn
  (testing "write a custom fn"
    (let [file (tmp-file)]

      (write-obj file #'my-inc)

      (with-open [obj-input-stream (object-input-stream file)]
        (let [inc-fn (.readObject obj-input-stream)]
          (is (= 2 (inc-fn 1))))))))
