(ns org.prevayler
  (:import
    [java.io File FileOutputStream FileInputStream ObjectInputStream ObjectOutputStream EOFException Closeable]
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

(defn backup-file [file]
  (File. (str file ".backup")))

(defn- try-to-replay! [handler state-atom file-in]
  (let [obj-in (ObjectInputStream. file-in)
        read-obj! #(.readObject obj-in)]
    (reset! state-atom (read-obj!))
    (while true           ;Ends with EOFException
      (let [[new-state _result] (handler @state-atom (read-obj!))]
        (reset! state-atom new-state)))))

(defn- replay! [handler state-atom ^File file]
  (with-open [file-in (FileInputStream. file)]
    (try
      (try-to-replay! handler state-atom file-in)

      (catch EOFException _done)
      (catch ClassNotFoundException cnfe (throw cnfe))
      (catch Exception corruption
        (println "Warning - Corruption at end of prevalence file (this is normally OK and can happen when process is killed during write):" corruption)))))

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

(defn- write-with-flush! [file-out obj-out obj]
  (.writeObject obj-out obj)
  (.reset obj-out)
  (.flush obj-out)
  (.flush file-out))

(defn- handle-event! [this handler state-atom write-fn event]
  (locking this
    (let [state-with-result (handler @state-atom event)]
      (write-fn event)
      (reset! state-atom (first state-with-result))
      state-with-result)))

(defn- durable-prevayler! [handler initial-state ^File file]
  (let [state-atom (atom initial-state)
        backup (produce-backup! file)]

    (when backup
      (replay! handler state-atom backup))

    (let [file-out (FileOutputStream. file)
          obj-out (ObjectOutputStream. file-out)
          write! (partial write-with-flush! file-out obj-out)]

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
            (.close obj-out)
            (.close file-out))
        IDeref
          (deref [_]
            @state-atom)))))

(defn- transient-prevayler! [handler initial-state]
  (let [state-atom (atom initial-state)
        no-write (fn [_ignored])]
    (reify
      Prevayler (handle! [this event]
                  (handle-event! this handler state-atom no-write event))
      IDeref (deref [_] @state-atom)
      Closeable (close [_] (reset! state-atom ::closed)))))

(defn prevayler!
  ([handler initial-state]      (transient-prevayler! handler initial-state))
  ([handler initial-state file] (durable-prevayler!   handler initial-state file)))
