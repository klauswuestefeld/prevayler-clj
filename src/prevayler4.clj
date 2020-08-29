(ns prevayler4
  (:require
    [taoensso.nippy :as nippy])
  (:import
    [java.io File FileOutputStream FileInputStream DataInputStream DataOutputStream EOFException Closeable]
    [clojure.lang IDeref]))

(defprotocol Prevayler
  (handle! [_ event]
    "Handle event and return vector containing the new state and event result."))

(defn- try-to-restore! [handler state-atom data-in]
  (let [read-value! #(nippy/thaw-from-in! data-in)]
    (let [previous-state (read-value!)] ; Can throw EOFException
      (reset! state-atom previous-state))
    (while true ;Ends with EOFException
      (let [event (read-value!)]
        (swap! state-atom handler event)))))

(defn- restore! [handler state-atom ^File file]
  (with-open [data-in (-> file FileInputStream. DataInputStream.)]
    (try
      (try-to-restore! handler state-atom data-in)

      (catch EOFException _done)
      (catch Exception corruption
        (println "Warning - Corruption at end of prevalence file (this is normally OK and can happen when the process is killed during write):" corruption)))))

(defn- produce-backup! [file]
  (let [backup (File. (str file ".backup"))]
    (if (.exists backup)
      backup
      (when (.exists file)
        (assert (.renameTo file backup))
        backup))))

(defn- archive! [^File file]
  (let [new-file (File. (str file "-" (System/currentTimeMillis)))]
    (assert (.renameTo file new-file))))

(defn- write-with-flush! [data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn prevayler!
  ([handler]
   (prevayler! handler {}))
  ([handler initial-state]
   (prevayler! handler initial-state (File. "journal4")))
  ([handler initial-state ^File file]
   (let [state-atom (atom initial-state)
         backup (produce-backup! file)]

     (when backup
       (restore! handler state-atom backup))

     (let [data-out (-> file FileOutputStream. DataOutputStream.)]
       (write-with-flush! data-out @state-atom)
       (when backup
         (archive! backup))

       (reify
         Prevayler
         (handle! [this event]
           (locking this ; (I)solation: strict serializability.
             (let [new-state (handler @state-atom event)] ; (C)onsistency: must be guaranteed by the handler. The event won't be journalled when the handler throws an exception.)
               (write-with-flush! data-out event) ; (D)urability
               (reset! state-atom new-state)))) ; (A)tomicity
         
         Closeable (close [_] (.close data-out))

         IDeref (deref [_] @state-atom))))))
