(ns prevayler-clj.prevayler4
  (:require
    [taoensso.nippy :as nippy])
  (:import
   [java.io File FileOutputStream FileInputStream DataInputStream DataOutputStream EOFException Closeable]
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

(defn- try-to-restore! [handler state-atom data-in]
  (let [read-value! #(nippy/thaw-from-in! data-in)]
    (let [previous-state (read-value!)] ; Can throw EOFException
      (reset! state-atom previous-state))
    (while true ;Ends with EOFException
      (let [[timestamp event] (read-value!)]
        (try
          (swap! state-atom handler event timestamp)
          (catch Exception e (.printStackTrace e)))))))

(defn- restore! [handler state-atom ^File file]
  (with-open [data-in (-> file FileInputStream. DataInputStream.)]
    (try
      (try-to-restore! handler state-atom data-in)
      (catch EOFException _done)
      (catch Exception corruption
        (println "Warning - Exception thrown while reading journal (this is normally OK and can happen when the process is killed during write):" corruption)))))

(defn- write-with-flush! [data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn- archive! [^File backup]
  (rename! backup (File. (str backup "-" (System/currentTimeMillis)))))

(defprotocol Prevayler
  (handle! [_ event] "Journals the event, applies the business function to the state and the event; and returns the new state."))

(defn prevayler! [{:keys [initial-state business-fn timestamp-fn journal-file]
                   :or {initial-state {}
                        timestamp-fn #(System/currentTimeMillis)
                        journal-file (File. "journal4")}}]
  (let [state-atom (atom initial-state)
        backup (produce-backup-file! journal-file)]

    (when backup
      (restore! business-fn state-atom backup))

    (let [data-out (-> journal-file FileOutputStream. DataOutputStream.)]
      (write-with-flush! data-out @state-atom)
      (when backup
        (archive! backup))

      (reify
        Prevayler
        (handle! [this event]
          (locking this ; (I)solation: strict serializability.
            (let [timestamp (timestamp-fn)
                  new-state (business-fn @state-atom event timestamp)] ; (C)onsistency: must be guaranteed by the handler. The event won't be journalled when the handler throws an exception.)
              (write-with-flush! data-out [timestamp event]) ; (D)urability
              (reset! state-atom new-state)))) ; (A)tomicity

        IDeref (deref [_] @state-atom)
        
        Closeable (close [_] (.close data-out))))))
