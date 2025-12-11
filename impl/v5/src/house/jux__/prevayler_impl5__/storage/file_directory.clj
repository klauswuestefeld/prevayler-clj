(ns house.jux--.prevayler-impl5--.storage.file-directory
  (:require
   [clojure.java.io :as io]
   [house.jux--.prevayler-impl5--.storage :as storage]
   [house.jux--.prevayler-impl5--.util :refer [check data-input-stream data-output-stream filename-number journal-ending journals part-file-ending rename! root-cause snapshot-ending snapshots]]
   [house.jux--.prevayler-impl5--.cleanup :as cleanup]
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
        part-file (io/file dir (str snapshot-name part-file-ending))]
    (println "Writing snapshot" snapshot-name)
    (with-open [^Closeable out (data-output-stream part-file)] ; Overrides old .part file if any.
      (write-with-flush! out state))
    ; (write-lease/check! dir-lease)
    (rename! part-file (io/file dir snapshot-name))))

(defn- restore-snapshot-if-necessary! [^File dir default-state]
  (if-some [snapshot-file (last (snapshots dir))]

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

(defn- journaled-events [^File journal-file]
  (let [^Closeable data-in (data-input-stream journal-file)
        step (fn step []
               (lazy-seq
                (if-some [event (try (read-event! data-in)
                                     (catch EOFException _expected nil))]
                  (cons event (step))
                  (do
                    (.close data-in)
                    nil))))]
    (step)))


(defn- restore-events! [journal-files initial-journal-index]
  (->> journal-files
       (drop-while #(< (filename-number %) initial-journal-index))
       (mapcat journaled-events)))

; TODO close old data-out
(defn- start-new-journal! [dir journal-atom]
  (let [next-index (if-some [index (:index @journal-atom)]
                     (inc index)
                     0)
        file (io/file dir (format (str filename-number-mask journal-ending) next-index))]
    (check (not (.exists file)) (str "journal file already exists: " file))
    (prn [:journal-file file])
    (reset! journal-atom {:data-out (-> file data-output-stream)
                          :index next-index})))


(defn open! [{:keys [^File dir #_sleep-interval]
              #_#_:or {sleep-interval 30000}}]
  (let [journal-atom (atom nil)
        close-journal! #(when-some [data-out (:data-out @journal-atom)]
                          (.close ^Closeable data-out))]  ; TODO: Call .getFD().sync() on the underlying FileOutputStream to minimize zombie writes (writes that arrive late at the server because they were buffered at the client during a network hiccup)

    (reify
      storage/Storage

      (latest-journal! [_this default-state]
        (let [{:keys [state journal-index]} (restore-snapshot-if-necessary! dir default-state)
              journal-files (journals dir)
              events (restore-events! journal-files journal-index)]
          (reset! journal-atom {:index (some-> journal-files last filename-number)})
          (start-new-journal! dir journal-atom)
          {:snapshot state
           :events events}))

      (append-to-journal! [this event]
        (try
          (write-with-flush! (:data-out @journal-atom) event)
          (catch Exception e
            (.close this)  ; TODO: Recover from what might have been just a network volume hiccup.
            (throw e))))

      #_"Serializes state (opaque value) and stores it as a snapshot asynchronously.
         Returns an IDeref that resolves to :done or throws on error.
         Must be called after the latest-journal! events have been consumed.
         Must not be called concurrently with append-to-journal! (caller must synchronize externally) because this Storage does not have information to properly sequence them."

      (start-taking-snapshot! [_this state]
        (let [{:keys [index]} (start-new-journal! dir journal-atom)]
          (future
            (write-snaphot! dir {:journal-index index :state state})
            :done)))

      Closeable (close [_] (close-journal!)))))













;       (check (= (:journal-index envelope) (filename-number journal-file)) (str "missing journal file number: " (:journal-index envelope)))

(def delete-old-snapshots! cleanup/delete-old-snapshots!)



#_
(defn prevayler! [...]

  (let [
        dir-lease (write-lease/acquire-for! dir sleep-interval close-journal!)
        ]

    

    (reset! journal-out-atom (start-new-journal! dir-lease (:journal-index @state-envelope-atom)))

    (reify
      api/Prevayler

      (handle! [this event]
        (locking journal-out-atom ; (I)solation: strict serializability.
          (write-lease/check! dir-lease)
          (let [{:keys [state]} @state-envelope-atom
                timestamp (timestamp-fn)
                new-state (business-fn state event timestamp)] ; (C)onsistency: must be guaranteed by the handler. The event won't be journalled when the handler throws an exception.
            (when-not (identical? new-state state)
              ...
              )
            new-state)))

      (snapshot! [_]
        (locking snapshot-monitor
          (let [envelope (locking journal-out-atom
                           (write-lease/check! dir-lease)
                           (close-journal!)
                           (let [envelope (swap! state-envelope-atom update :journal-index inc)
                                 new-journal (start-new-journal! dir-lease (:journal-index envelope))] ; Prevayler remains closed if this throws Exception. TODO: Recover from what might have been just a network volume hiccup.
                             (reset! journal-out-atom new-journal)
                             envelope))]
            (write-snaphot! envelope dir-lease))))

      (timestamp [_] (timestamp-fn))

      IDeref (deref [_] (:state @state-envelope-atom)))))
