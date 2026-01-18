(ns house.jux--.prevayler-impl5--.storage.file-directory
  (:require
   [clojure.java.io :as io]
   [house.jux--.prevayler-impl5--.storage :as storage]
   [house.jux--.prevayler-impl5--.util :refer [check data-input-stream data-output-stream filename-number journal-ending journals-sorted part-file-ending rename! root-cause snapshot-ending snapshots-sorted]]
   [taoensso.nippy :as nippy])
  (:import
   [java.io Closeable DataOutputStream EOFException File]))

(set! *warn-on-reflection* true)

(def filename-number-mask "%09d")

(defn- nippy-read! [data-in]
  (try
    (nippy/thaw-from-in! data-in)
    (catch Exception e
      (throw (root-cause e)))))  ; Nippy wraps some Throwables such as OOM in ex-infos recursively. We don't want that.

(defn- restore-snapshot! [^File snapshot-file]
  (println "Reading snapshot" (.getAbsolutePath snapshot-file))
  (try
    (with-open [^Closeable data-in (data-input-stream snapshot-file)]
      (nippy-read! data-in))
    (catch Exception e
      ; TODO: "Point to readme (delete/rename corrupt snapshot)"
      (throw (ex-info "Error reading snapshot" {:file snapshot-file} e)))))

(defn- write-with-flush! [^DataOutputStream data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn- write-snaphot! [dir {:keys [state journal-index]}]
  (let [snapshot-name (format (str filename-number-mask snapshot-ending) journal-index)
        snapshot-file (io/file dir snapshot-name)
        part-file (io/file dir (str snapshot-name part-file-ending))]
    (when-not (.exists snapshot-file)
      (println "Writing snapshot" snapshot-name)
      (with-open [^Closeable out (data-output-stream part-file)] ; Overrides old .part file if any.
        (write-with-flush! out state))
      (rename! part-file snapshot-file))))

(defn- restore-snapshot-if-necessary! [^File dir default-state]
  (if-some [snapshot-file (last (snapshots-sorted dir))]

    {:state (restore-snapshot! snapshot-file)
     :journal-index (filename-number snapshot-file)}

    (let [result {:state default-state
                  :journal-index 0}]
      (write-snaphot! ^File dir result)
      result)))

(defn- read-event! [data-in]
  (try
    (nippy-read! data-in)
    (catch Exception e
      (when-not (instance? EOFException e)
        (println "Warning - Exception thrown while reading journal (this is normally OK and can happen when the previous process was killed in the middle of a write):" e))
      (throw (EOFException.)))))

(defn- journal-file [dir index]
  (io/file dir (format (str filename-number-mask journal-ending) index)))

(defn- read-events! [dir journal-index]
  (let [^File file (journal-file dir journal-index)
        _ (check (.exists file) "Unable to restore state. Missing journal file: " file " - Restore it from backup if possible.")
        ^Closeable data-in (data-input-stream file)
        step (fn step []
               (lazy-seq
                (if-some [event (try (read-event! data-in)
                                     (catch EOFException _expected nil))]
                  (cons event (step))
                  (do
                    (.close data-in)
                    nil))))]
    (step)))

(defn- restore-events! [dir initial-journal-index]
  (if-some [last-journal-index (some-> (last (journals-sorted dir)) filename-number)]
    (let [next-journal-index (max initial-journal-index (inc last-journal-index))
          journal-indexes-to-read (range initial-journal-index next-journal-index)]   ; range does not include the end value
      {:journal-index next-journal-index
       :events (mapcat #(read-events! dir %) journal-indexes-to-read)})
    {:journal-index initial-journal-index
     :events []}))

(defn- open-journal! [dir journal-atom]
  (let [{:keys [index]} @journal-atom
        ^File file (journal-file dir index)]
    (check (not (.exists file)) (str "journal file already exists: " file))
    (swap! journal-atom assoc :data-out (-> file data-output-stream))))

(defn- data-out! [dir journal-atom]
  (when (:closed @journal-atom)
    (throw (ex-info "storage is closed" {})))
  (when-not (:data-out @journal-atom)
    (open-journal! dir journal-atom))
  (:data-out @journal-atom))

; TODO: Call .getFD().sync() on the underlying FileOutputStream to minimize zombie writes (writes that arrive late at the server because they were buffered at the client during a network hiccup)
(defn- bump-journal-if-necessary! [journal-atom]
  (when-some [data-out (:data-out @journal-atom)]
    (.close ^Closeable data-out)
    (swap! journal-atom update :index inc)
    (swap! journal-atom dissoc :data-out)))

(defn open! [{:keys [^File dir #_sleep-interval]
              #_#_:or {sleep-interval 30000}}]
  (let [journal-atom (atom nil)
        check-open! (fn [] (check (not= @journal-atom :closed) "Storage is closed."))]

    (reify
      storage/Storage

      (latest-journal! [_this default-state]
        (let [{:keys [state  journal-index]} (restore-snapshot-if-necessary! dir default-state)
              {:keys [events journal-index]} (restore-events! dir journal-index)]
          (reset! journal-atom {:index journal-index})
          {:snapshot state
           :events events}))

      (append-to-journal! [this event]
        (check-open!)
        (try
          (write-with-flush! (data-out! dir journal-atom) event)
          (catch Exception e
            (.close this) ; TODO: Recover from what might have been just a network volume hiccup.
            (throw e))))

      (start-taking-snapshot! [_this state]
        (check-open!)
        (bump-journal-if-necessary! journal-atom)
        (let [{:keys [index]} @journal-atom]
          (future
            (write-snaphot! dir {:journal-index index :state state})
            :done)))

      Closeable
      (close [_]
        (bump-journal-if-necessary! journal-atom)
        (reset! journal-atom :closed)))))
