(ns house.jux--.prevayler-impl5--.util
  (:import
   [java.io BufferedInputStream BufferedOutputStream DataInputStream DataOutputStream File FileInputStream FileOutputStream]))

(set! *warn-on-reflection* true)

(defmacro check
  "Check if the given form is truthy, otherwise throw an exception with
  the given message. Alternative to `assert` that cannot be turned off
  and throwing RuntimeException instead of Error."
  [form & otherwise-msg-fragments]
  `(when-not ~form
       (throw (new RuntimeException ^String (str ~@otherwise-msg-fragments)))))


(defn rename! [^File file ^File new-file]
  (check (.renameTo file new-file)
         (str "Unable to rename " file " to " new-file (when (.exists new-file) " (already exists)"))))

(defn root-cause ^Throwable [^Throwable t]
  (->> (iterate #(.getCause ^Throwable %) t)
       (take-while some?)
       last))

(defn list-files [^File dir]
  (->> (.listFiles dir)
       (filter #(.isFile ^File %))))

(defn filename-number [^File file]
  (Long/parseLong (re-find #"\d+" (.getName file))))

(defn sorted-files [dir ending]
  (->> dir
       list-files
       (filter #(.endsWith (.getName ^File %) ending))
       (sort-by filename-number)))

(defn data-output-stream [^File file] (-> file FileOutputStream. BufferedOutputStream. DataOutputStream.))
(defn data-input-stream  [^File file] (-> file FileInputStream.  BufferedInputStream.  DataInputStream.))
