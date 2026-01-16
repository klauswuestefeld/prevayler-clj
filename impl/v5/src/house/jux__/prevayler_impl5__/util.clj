(ns house.jux--.prevayler-impl5--.util
  (:import
   [java.io BufferedInputStream BufferedOutputStream DataInputStream DataOutputStream File FileInputStream FileOutputStream]))

(set! *warn-on-reflection* true)

(def journal-ending   ".journal5")
(def snapshot-ending  ".snapshot5")
(def part-file-ending ".part")

(defmacro check
  "Check if the given form is truthy, otherwise throw an exception with
  the given message. Alternative to `assert` that cannot be turned off
  and throwing RuntimeException instead of Error."
  [form & otherwise-msg-fragments]
  `(when-not ~form
       (throw (new RuntimeException ^String (str ~@otherwise-msg-fragments)))))

(defn root-cause ^Throwable [^Throwable t]
  (->> (iterate #(.getCause ^Throwable %) t)
       (take-while some?)
       last))

(defn data-output-stream [^File file] (-> file FileOutputStream. BufferedOutputStream. DataOutputStream.))
(defn data-input-stream  [^File file] (-> file FileInputStream.  BufferedInputStream.  DataInputStream.))

(defn rename! [^File file ^File new-file]
  (check (.renameTo file new-file)
         (str "Unable to rename " file " to " new-file (when (.exists new-file) " (already exists)"))))

(defn list-files [^File dir & [ending]]
  (let [result (->> (.listFiles dir)
                    (filter #(.isFile ^File %)))]
    (if ending
      (filter #(-> (.getName ^File %) (.endsWith ending))
              result)
      result)))

(defn filename-number [^File file]
  (Long/parseLong (re-find #"\d+" (.getName file))))

(defn sorted-by-number [dir ending]
  (->> (list-files dir ending)
       (sort-by filename-number)))

(defn missing-number
  "Receives a sequence of hopefully consecutive numbers. Returns any missing number in that sequence."
  [consecutive-numbers]
  (if (<= (count consecutive-numbers) 1)
    nil
    (some (fn [[a b]]
            (when (not= b (inc a))
              (inc a)))
          (partition 2 1 consecutive-numbers))))

(defn journals  [dir] (sorted-by-number dir journal-ending))
(defn snapshots [dir] (sorted-by-number dir snapshot-ending))