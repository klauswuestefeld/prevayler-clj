(ns prevayler-clj.prevayler4
  (:require
    [taoensso.nippy :as nippy])
  (:import
   [java.io BufferedInputStream BufferedOutputStream Closeable DataInputStream DataOutputStream EOFException File FileInputStream FileOutputStream]
   [clojure.lang IDeref]))

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

(defn- try-to-restore! [handler state-atom data-in]
  (let [previous-state (read-value! data-in)] ; Can throw EOFException
    (reset! state-atom previous-state))
  (while true ;Ends with EOFException
    (let [[timestamp event expected-state-hash] (read-value! data-in)
          state (swap! state-atom handler event timestamp)]
      (when (and expected-state-hash ; Old prevayler4 journals don't have this state hash saved (2023-11-01)
                 (not= (hash state) expected-state-hash))
        (println "Inconsistent state detected after restoring event:\n" event)
        (throw (IllegalStateException. "Inconsistent state detected during event journal replay. https://github.com/klauswuestefeld/prevayler-clj/blob/master/reference.md#inconsistent-state-detected"))))))

(defn- restore! [handler state-atom ^File file]
  (with-open [data-in (-> file FileInputStream. BufferedInputStream. DataInputStream.)]
    (try
      (try-to-restore! handler state-atom data-in)
      (catch EOFException _done))))

(defn- write-with-flush! [data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn- archive! [^File backup]
  (rename! backup (File. (str backup "-" (System/currentTimeMillis)))))

(defn- start-new-journal! [journal-file data-out-atom state backup-file]
  (reset! data-out-atom (-> journal-file FileOutputStream. BufferedOutputStream. DataOutputStream.))
  (write-with-flush! @data-out-atom state)
  (when backup-file
    (archive! backup-file)))

(defprotocol Prevayler
  (handle! [this event] "Journals the event, applies the business function to the state and the event; and returns the new state.")
  (snapshot! [this] "Creates a snapshot of the current state.")
  (timestamp [this] "Calls the timestamp-fn"))

(defn prevayler! [{:keys [initial-state business-fn timestamp-fn journal-file]
                   :or {initial-state {}
                        timestamp-fn #(System/currentTimeMillis)
                        journal-file (File. "journal4")}}]
  (let [state-atom (atom initial-state)
        backup (produce-backup-file! journal-file)]

    (when backup
      (restore! business-fn state-atom backup))

    (let [data-out-atom (atom nil)]
      (start-new-journal! journal-file data-out-atom @state-atom backup)

      (reify
        Prevayler
        (handle! [this event]
          (locking this ; (I)solation: strict serializability.
            (let [current-state @state-atom
                  timestamp (timestamp-fn)
                  new-state (business-fn current-state event timestamp)] ; (C)onsistency: must be guaranteed by the handler. The event won't be journalled when the handler throws an exception.
              (when-not (= new-state current-state)
                (write-with-flush! @data-out-atom [timestamp event (hash new-state)]) ; (D)urability
                (reset! state-atom new-state)) ; (A)tomicity
              new-state)))
        
        (snapshot! [this]
          (locking this
            (.close @data-out-atom)
            (let [backup (produce-backup-file! journal-file)]
              (start-new-journal! journal-file data-out-atom @state-atom backup))))
        
        (timestamp [_] (timestamp-fn))

        IDeref (deref [_] @state-atom)

        Closeable (close [_] (.close @data-out-atom))))))
