(ns prevayler-clj.prevayler5
  (:require
   [clojure.java.io :as io]
   [taoensso.nippy :as nippy])
  (:import
   [java.io BufferedInputStream BufferedOutputStream Closeable DataInputStream DataOutputStream EOFException File FileInputStream FileOutputStream]
   [clojure.lang IDeref]))

(def journal-extention "journal5")

(defn- rename! [file new-file]
  (when-not (.renameTo file new-file)
    (throw (RuntimeException. (str "Unable to rename " file " to " new-file)))))

(defn- produce-backup-file! [file]
  (let [backup (File. (str file ".backup"))]
    (if (.exists backup)
      backup
      (when (.exists file)
        (rename! file backup)
        backup))))

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

(defn- journal-file? [file]
  (.endsWith (.getName file) (str "." journal-extention)))

(defn- name->transaction-count [file]
  (Long/parseLong (re-find #"\d+" (.getName file))))

;; TODO implement
(defn- try-to-restore! [dir handler {:keys [state transaction-count]}]
  (let [journal-file (->> dir
                          list-files
                          (filter journal-file?)
                          (map (juxt identity name->transaction-count))
                          (remove #(> (second %) transaction-count))
                          (sort-by (comp - second))
                          ffirst)]
    ; WIP
    )
  (try
    (while true ;Ends with EOFException
      (let [[timestamp event] (read-value! data-in)]
        (swap! state-atom handler event timestamp)))
    (catch EOFException _expected)))

(defn last-snapshot-file [dir])

(defn- restore! [dir handler initial-state]
  (let [state-envelope (if-let [snapshot-file (last-snapshot-file dir)]
                         (let [state (try
                                       (with-open [data-in (-> snapshot-file FileInputStream. BufferedInputStream. DataInputStream.)]
                                         (read-value! data-in))
                                       (catch Exception e
                                         (throw (ex-info "cannot read snapshot" {:file snapshot-file} e))))]
                           {:state state :transaction-count (name->transaction-count snapshot-file)})
                         {:state initial-state :transaction-count 0})]
    (try-to-restore! dir handler state-envelope)))

(defn- write-with-flush! [data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn- data-output-stream [file]
  (-> file FileOutputStream. BufferedOutputStream. DataOutputStream.))

(defn- start-new-journal! [dir transaction-count]
  (let [file (io/file dir (format "%012d.journal5" transaction-count))]
    (when (.exists file)
      (.renameTo file (io/file (str file ".backup-" (System/currentTimeMillis)))))
    (-> file data-output-stream)))

(defprotocol Prevayler
  (handle! [this event] "Journals the event, applies the business function to the state and the event; and returns the new state.")
  (snapshot! [this] "Creates a snapshot of the current state.")
  (timestamp [this] "Calls the timestamp-fn"))

(defn prevayler! [{:keys [initial-state business-fn timestamp-fn dir]
                   :or {initial-state {}
                        timestamp-fn #(System/currentTimeMillis)}}]
  (let [state-envelope-atom (atom (restore! dir business-fn initial-state))]
    (let [journal-out (start-new-journal! dir (:transaction-count @state-envelope-atom))]
      (reify
        Prevayler
        (handle! [_ event]
          (locking ::journal ; (I)solation: strict serializability.
            (let [{:keys [state transaction-count]} @state-envelope-atom
                  timestamp (timestamp-fn)
                  new-state (business-fn state event timestamp)] ; (C)onsistency: must be guaranteed by the handler. The event won't be journalled when the handler throws an exception.
              (when-not (identical? new-state state)
                ; TODO: Close prevayler if writing throws Exception.
                (write-with-flush! journal-out [timestamp event (hash new-state)]) ; (D)urability
                (reset! state-envelope-atom {:state new-state :transaction-count (inc transaction-count)})) ; (A)tomicity
              new-state)))
        
        (snapshot! [_]
          (locking ::snapshot
            (let [{:keys [state transaction-count]} @state-envelope-atom
                  file-name (format "%012d.snapshot5" transaction-count)
                  snapshot-file (io/file dir (str file-name ".part"))]
              (with-open [out (-> snapshot-file data-output-stream)]
                (write-with-flush! out state))
              (.renameTo snapshot-file (io/file file-name)))))
        
        (timestamp [_] (timestamp-fn))

        IDeref (deref [_] @state-envelope-atom)

        Closeable (close [_] (.close journal-out))))))
