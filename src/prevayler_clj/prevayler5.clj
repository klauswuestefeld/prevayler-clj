(ns prevayler-clj.prevayler5
  (:require
   [clojure.java.io :as io]
   [taoensso.nippy :as nippy])
  (:import
   [java.io BufferedInputStream BufferedOutputStream Closeable DataInputStream DataOutputStream EOFException FileInputStream FileOutputStream]
   [clojure.lang IDeref]))

(def filename-number-mask "%09d")
(def journal-extension  "journal5")
(def snapshot-extention "snapshot5")

(defn- rename! [file new-file]
  (when-not (.renameTo file new-file)
    (throw (RuntimeException. (str "Unable to rename " file " to " new-file)))))

(defn- read-value! [data-in]
  (try
    (nippy/thaw-from-in! data-in)
    (catch EOFException eof
      (throw eof))
    (catch Exception corruption
      (println "Warning - Exception thrown while reading journal (this is normally OK and can happen when the process is killed during write):" corruption)
      (throw (EOFException.)))))

(defn list-files [dir]
  (->> (clojure.java.io/file dir)
       (.listFiles)
       (filter #(.isFile %))))

(defn- filename-number [file]
  (Long/parseLong (re-find #"\d+" (.getName file))))

(defn- sorted-files [dir extension]
  (->> dir
       list-files
       (filter #(.endsWith (.getName %) (str "." extension)))
       (sort-by filename-number)))

(defn- restore-journal [envelope journal-file handler]
  (with-open [data-in (-> journal-file FileInputStream. BufferedInputStream. DataInputStream.)]
    (loop [envelope envelope]
      (if-some [[timestamp event] (try (read-value! data-in)
                                       (catch EOFException _expected))]
        (recur
         (update envelope :state handler event timestamp))
        (update envelope :journal-index inc)))))

(defn- restore-journals-if-necessary! [initial-state-envelope
                                       dir
                                       handler]
  (let [journals (sorted-files dir journal-extension)
        relevant-journals (drop-while #(< (filename-number %) (:journal-index initial-state-envelope)) journals)]
    (reduce
     (fn [envelope journal-file]
       (when-not (= (:journal-index envelope) (filename-number journal-file))
         (throw (IllegalStateException. (str "missing journal file number: " (:journal-index envelope)))))
       (restore-journal envelope journal-file handler))
     initial-state-envelope
     relevant-journals)))

(defn last-snapshot-file [dir]
  (last (sorted-files dir snapshot-extention)))

(defn- restore-snapshot! [snapshot-file]
  (try
    (with-open [data-in (-> snapshot-file FileInputStream. BufferedInputStream. DataInputStream.)]
      (read-value! data-in))
    (catch Exception e
      ; TODO: "Point to readme (delete/rename corrupt snapshot)"
      (throw (ex-info "Error reading snapshot" {:file snapshot-file} e)))))

(defn- restore-snapshot-if-necessary! [initial-state-envelope dir]
  (if-some [snapshot-file (last-snapshot-file dir)]

    {:state (restore-snapshot! snapshot-file)
     :journal-index (filename-number snapshot-file)}

    initial-state-envelope))

(defn- restore! [dir handler initial-state]
  (-> {:state initial-state, :journal-index 0}
      (restore-snapshot-if-necessary! dir)
      (restore-journals-if-necessary! dir handler)))

(defn- write-with-flush! [data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn- data-output-stream [file]
  (-> file FileOutputStream. BufferedOutputStream. DataOutputStream.))

(defn- start-new-journal! [dir journal-index]
  (let [file (io/file dir (format (str filename-number-mask ".journal5") journal-index))]
    (when (.exists file)
      (throw (IllegalStateException. (str "journal file already exists, index: " journal-index))))
    (-> file data-output-stream)))

(defprotocol Prevayler
  (handle! [this event] "Journals the event, applies the business function to the state and the event; and returns the new state.")
  (snapshot! [this] "Creates a snapshot of the current state.")
  (timestamp [this] "Calls the timestamp-fn"))

(defn prevayler! [{:keys [initial-state business-fn timestamp-fn dir]
                   :or {initial-state {}
                        timestamp-fn #(System/currentTimeMillis)}}]
  (let [state-envelope-atom (atom (restore! dir business-fn initial-state))
        journal-out-atom (atom (start-new-journal! dir (:journal-index @state-envelope-atom)))
        snapshot-monitor (Object.)]
    (reify
      Prevayler
      (handle! [_ event]
        (locking journal-out-atom ; (I)solation: strict serializability.
          (let [{:keys [state]} @state-envelope-atom
                timestamp (timestamp-fn)
                new-state (business-fn state event timestamp)] ; (C)onsistency: must be guaranteed by the handler. The event won't be journalled when the handler throws an exception.
            (when-not (identical? new-state state)
                ; TODO: Close prevayler if writing throws Exception.
              (write-with-flush! @journal-out-atom [timestamp event]) ; (D)urability
              (swap! state-envelope-atom assoc :state new-state)) ; (A)tomicity
            new-state)))

      (snapshot! [_]
        (locking snapshot-monitor
          (let [{:keys [state journal-index]} (locking journal-out-atom
                                                (let [envelope (swap! state-envelope-atom update :journal-index inc)]
                                                  (.close @journal-out-atom)
                                                  ; TODO: Close prevayler if writing throws Exception.
                                                  (reset! journal-out-atom (start-new-journal! dir (:journal-index envelope)))
                                                  envelope))
                file-name (format (str filename-number-mask ".snapshot5") journal-index)
                snapshot-file (io/file dir (str file-name ".part"))]
            (with-open [out (data-output-stream snapshot-file)]
              (write-with-flush! out state))
            (rename! snapshot-file (io/file dir file-name)))))

      (timestamp [_] (timestamp-fn))

      IDeref (deref [_] (:state @state-envelope-atom))

      Closeable (close [_] (.close @journal-out-atom)))))
