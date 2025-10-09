; TODO: Cooperative Consistency Across Multiple Instances Sharing the Same Network Dir
; ====================================================================================
;
; Init
;  Loop until successful:
;    Delete all files inside the "lock" folder, if any.
;    Delete the "lock" folder, if it exists.
;    Create "lock" folder. This is important: I must create it, it can't already exist.
;      Create owner-{uuid}.a file.
;
; Wait 1min, so the old owner, if any, can yield.
; Loop every 30s, to see if I still am the owner: (lease mechanism)
;   Rename lock/owner-{uuid}.a file to lock/owner-{uuid}.b or vice-versa.
;     If success: lastSuccessfulLeaseCheck atom = currentTimeMillis; error atom = nil.
;     If neither file (a nor b) exist: deposed atom = true
;     If some other error: error atom = error message
;    
; Check before every write: Prevayler is free to write if:
;   Not deposed
;   AND Not error
;   AND lastSuccessfulLeaseCheck newer than 40s.
;
;
; TODO: Bonus: Extra Resilience Against Zombie Journal Writes
; -----------------------------------------------------------
;
; Zombie writes are writes arriving late at the server from some old dead owner that were buffered on a temporarily disconnected network client.
; To ignore zombie journal appends:
; 
; For each journal to read:
;   Does a file called {journal-number}.journal-metadata5 exist?
;     No: read the whole journal and write this metadata file with the number of events I just read.
;     Yes: read only as many events as the amount recorded in the metadata file.
;
; To avoid late zombie journal file creation from overriding existing journals:
;   Before creating a new journal file:
;     Create a lock/{journal-number}.journal-lock directory (MKDIR the only atomic mutually-exclusive operation we can trust. Even file renames will override each other.)
;       If it already exists, but the journal file does not, treat the same as an empty journal.



(ns house.jux--.prevayler-impl5--
  (:require
   [clojure.java.io :as io]
   [house.jux--.prevayler-- :as api]
   [house.jux--.prevayler-impl5--.util :refer [check data-input-stream data-output-stream filename-number journal-ending journals part-file-ending rename! root-cause snapshot-ending snapshots]]
   [house.jux--.prevayler-impl5--.cleanup :as cleanup]
   [house.jux--.prevayler-impl5--.write-lease :as write-lease]
   [taoensso.nippy :as nippy])
  (:import
   [clojure.lang IDeref]
   [java.io Closeable DataOutputStream EOFException File]))

(set! *warn-on-reflection* true)

(def filename-number-mask "%09d")

(defn- nippy-read! [data-in]
  (try
    (nippy/thaw-from-in! data-in)
    (catch Exception e
      (throw (root-cause e)))))  ; Nippy wraps some Throwables such as OOM in ex-infos recursively. We don't want that.

(defn- read-value! [data-in]
  (try
    (nippy-read! data-in)
    (catch Exception e
      (when-not (instance? EOFException e)
        (println "Warning - Exception thrown while reading journal (this is normally OK and can happen when the previous process was killed in the middle of a write):" e))
      (throw (EOFException.)))))

(defn- restore-journal [envelope ^File journal-file handler]
  (with-open [^Closeable data-in (data-input-stream journal-file)]
    (loop [envelope envelope]
      (if-some [[timestamp event] (try (read-value! data-in)
                                       (catch EOFException _expected nil))]
        (recur
         (update envelope :state handler event timestamp))
        (update envelope :journal-index inc)))))

(defn- restore-journals-if-necessary! [initial-state-envelope
                                       dir
                                       handler]
  (let [relevant-journals (->> (journals dir)
                               (drop-while #(< (filename-number %) (:journal-index initial-state-envelope))))]
    (reduce
     (fn [envelope journal-file]
       (check (= (:journal-index envelope) (filename-number journal-file)) (str "missing journal file number: " (:journal-index envelope)))
       (restore-journal envelope journal-file handler))
     initial-state-envelope
     relevant-journals)))

(defn- restore-snapshot! [snapshot-file]
  (println "Reading snapshot" (.getName ^File snapshot-file))
  (try
    (with-open [^Closeable data-in (data-input-stream snapshot-file)]
      (read-value! data-in))
    (catch Exception e
      ; TODO: "Point to readme (delete/rename corrupt snapshot)"
      (throw (ex-info "Error reading snapshot" {:file snapshot-file} e)))))

(defn- write-with-flush! [^DataOutputStream data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn- write-snaphot! [{:keys [state journal-index]} dir my-lease]
  (let [snapshot-name (format (str filename-number-mask snapshot-ending) journal-index)
        part-file (io/file dir (str snapshot-name part-file-ending))]
    (println "Writing snapshot" snapshot-name)
    (with-open [^Closeable out (data-output-stream part-file)] ; Overrides old .part file if any.
      (write-with-flush! out state))
    (write-lease/check! my-lease)
    (rename! part-file (io/file dir snapshot-name))))

(defn last-snapshot-file [dir]
  (last (snapshots dir)))

(defn- restore-snapshot-if-necessary! [initial-state-envelope dir my-lease]
  (if-some [snapshot-file (last-snapshot-file dir)]

    {:state (restore-snapshot! snapshot-file)
     :journal-index (filename-number snapshot-file)}

    (do
      (write-snaphot! initial-state-envelope dir my-lease)
      initial-state-envelope)))

(defn- restore! [dir handler initial-state my-lease]
  (-> {:state initial-state, :journal-index 0}
      (restore-snapshot-if-necessary! dir my-lease)
      (restore-journals-if-necessary! dir handler)))

(defn- start-new-journal! [dir journal-index]
  (let [file (io/file dir (format (str filename-number-mask journal-ending) journal-index))]
    (check (not (.exists file)) (str "journal file already exists, index: " journal-index))
    (-> file data-output-stream)))

(def delete-old-snapshots! cleanup/delete-old-snapshots!)

(defn prevayler! [{:keys [dir initial-state business-fn timestamp-fn]
                   :or {initial-state {}
                        timestamp-fn #(System/currentTimeMillis)}}]

  (let [^File dir (io/file dir)
        journal-out-atom (atom nil)
        close-journal! #(when-let [journal-out @journal-out-atom]
                          (.close ^Closeable journal-out)) ; TODO: Call .getFD().sync() on the underlying FileOutputStream to minimize zombie writes (writes that arrive late at the server because they were buffered at the client during a network hiccup)
        my-lease (write-lease/acquire-for! dir close-journal!)
        state-envelope-atom (atom (restore! dir business-fn initial-state my-lease))
        snapshot-monitor (Object.)]

    (reset! journal-out-atom (start-new-journal! dir (:journal-index @state-envelope-atom)))

    (reify
      api/Prevayler

      (handle! [this event]
        (locking journal-out-atom ; (I)solation: strict serializability.
          (write-lease/check! my-lease)
          (let [{:keys [state]} @state-envelope-atom
                timestamp (timestamp-fn)
                new-state (business-fn state event timestamp)] ; (C)onsistency: must be guaranteed by the handler. The event won't be journalled when the handler throws an exception.
            (when-not (identical? new-state state)
              (try
                (write-with-flush! @journal-out-atom [timestamp event]) ; (D)urability
                (swap! state-envelope-atom assoc :state new-state)  ; (A)tomicity
                (catch Exception e
                  (.close this)  ; TODO: Recover from what might have been just a network volume hiccup.
                  (throw e))))
            new-state)))

      (snapshot! [_]
        (locking snapshot-monitor
          (let [envelope (locking journal-out-atom
                           (write-lease/check! my-lease)
                           (close-journal!)
                           (let [envelope (swap! state-envelope-atom update :journal-index inc)
                                 new-journal (start-new-journal! dir (:journal-index envelope))] ; Prevayler remains closed if this throws Exception. TODO: Recover from what might have been just a network volume hiccup.
                             (reset! journal-out-atom new-journal)
                             envelope))]
            (write-snaphot! envelope dir my-lease))))

      (timestamp [_] (timestamp-fn))

      IDeref (deref [_] (:state @state-envelope-atom))

      Closeable (close [_] (close-journal!)))))
