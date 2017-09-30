(ns prevayler
  (:require
    [taoensso.nippy :as nippy])
  (:import
    [java.io File FileOutputStream FileInputStream DataInputStream DataOutputStream EOFException Closeable]
    [clojure.lang IDeref]))

(defprotocol Prevayler
  (handle! [_ event]
    "Handle event and return vector containing the new state and event result."))

(defn eval!
  "Handle event and return event result."
  [prevayler event]
  (second (handle! prevayler event)))

(defn step!
  "Handle event and return new state."
  [prevayler event]
  (first (handle! prevayler event)))

(defn transient-prevayler! [handler initial-state]
  (let [state-atom (atom initial-state)
        no-write (fn [_ignored])]
    (reify
      Prevayler (handle! [this event]
                  (handle-event! this handler state-atom no-write event))
      IDeref (deref [_] @state-atom)
      Closeable (close [_] (reset! state-atom ::closed)))))

(defn backup-file [file]
  (File. (str file ".backup")))

(defn- try-to-restore! [handler state-atom data-in]
  (let [read-value! #(nippy/thaw-from-in! data-in)]
    (reset! state-atom (read-value!))
    (while true           ;Ends with EOFException
      (let [[new-state _result] (handler @state-atom (read-value!))]
        (reset! state-atom new-state)))))

(defn- restore! [handler state-atom ^File file]
  (with-open [data-in (-> file FileInputStream. DataInputStream.)]
    (try
      (try-to-restore! handler state-atom data-in)

      (catch EOFException _done)
      (catch Exception corruption
        (println "Warning - Corruption at end of prevalence file (this is normally OK and can happen when the process is killed during write):" corruption)))))

(defn- produce-backup! [file]
  (let [backup (backup-file file)]
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

(defn- handle-event! [this handler state-atom write-fn event]
  (locking this
    (let [state-with-result (handler @state-atom event)]
      (write-fn event)
      (reset! state-atom (first state-with-result))
      state-with-result)))

(defn prevayler!
  ([handler initial-state]
   (prevayler! handler initial-state (File. "journal")))
  ([handler initial-state ^File file]
   (let [state-atom (atom initial-state)
         backup (produce-backup! file)]

     (when backup
       (restore! handler state-atom backup))

     (let [data-out (-> file FileOutputStream. DataOutputStream.)
           write! (partial write-with-flush! data-out)]

       (write! @state-atom)
       (when backup
         (archive! backup))

       (reify
         Prevayler
         (handle! [this event]
           (handle-event! this handler state-atom write! event))
         Closeable
         (close [_]
           (reset! state-atom ::closed)
           (.close data-out))
         IDeref
         (deref [_]
           @state-atom))))))

