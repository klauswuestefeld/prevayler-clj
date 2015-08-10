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

(defn- swap-with-result! [atom handler event]
  (let [state-and-result (handler @atom event)]
    (reset! atom (first state-and-result))
    state-and-result))

(defn backup-file [file]
  (File. (str file ".backup")))

(defn- try-to-replay! [handler state file-in]
  (let [obj-in (ObjectInputStream. file-in)
        read-obj! #(.readObject obj-in)
        stepper (comp first handler)]
    (reset! state (read-obj!))
    (while true  ; loop stops at EOFException
      (swap! state stepper (read-obj!)))))

(defn- replay! [handler state file]
  (with-open [file-in (FileInputStream. file)]
    (try
      (try-to-replay! handler state file-in)

      (catch ClassNotFoundException cnfe (throw cnfe))
      (catch EOFException _ok)
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

(defn- durable-prevayler! [handler initial-state file]
  (let [state (atom initial-state)
        backup (produce-backup! file)]

    (when backup
      (replay! handler state backup))

    (let [file-out (FileOutputStream. file)
          obj-out (ObjectOutputStream. file-out)
          write! (partial write-with-flush! file-out obj-out)]

      (write! @state)
      (when backup
        (archive! backup))

      (reify
        Prevayler
          (handle! [this event]
            (locking this
              (write! event)
              (swap-with-result! state handler event)))
        Closeable
          (close [_]
            (reset! state ::closed)
            (.close obj-out)
            (.close file-out))
        IDeref
          (deref [_]
            @state)))))

(defn- transient-prevayler! [handler initial-state]
  (let [state (atom initial-state)]
    (reify
      Prevayler (handle! [this event]
                  (locking this
                    (swap-with-result! state handler event)))
      IDeref (deref [_] @state)
      Closeable (close [_] (reset! state ::closed)))))

(defn prevayler!
  ([handler initial-state]      (transient-prevayler! handler initial-state))
  ([handler initial-state file] (durable-prevayler!   handler initial-state file)))
