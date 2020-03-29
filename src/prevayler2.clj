(ns prevayler2
  (:import
    [java.io File FileOutputStream FileInputStream EOFException ObjectOutputStream ObjectInputStream]
    [clojure.lang Atom IAtom IDeref]
    [com.sun.xml.internal.ws Closeable]))

(defprotocol Prevayler2
  (handle!
    [_ _]
    [_ _ _]
    [_ _ _ _]
    [_ _ _ _ _]
    [_ _ _ _ _ _]
    [_ _ _ _ _ _ _]
    [_ _ _ _ _ _ _ _]
    [_ _ _ _ _ _ _ _ _]))

(defn handle-event!
  [this write-fn state-atom & args]
  (locking this
    (let [fn                (first args)
          params            (rest args)
          state-with-result (apply (partial swap! state-atom fn) params)]
      (write-fn args)
      state-with-result)))

(defn transient-prevayler! [initial-state]
  (let [state-atom (atom initial-state)
        no-write   (fn [_])]
    ;reify doesn't support varargs :(
    (reify
      Prevayler2
      (handle! [this fn] (handle-event! this no-write state-atom fn))
      (handle! [this fn x] (handle-event! this no-write state-atom fn x))
      (handle! [this fn x y] (handle-event! this no-write state-atom fn x y))
      (handle! [this fn x y z] (handle-event! this no-write state-atom fn x y z))
      (handle! [this fn x y z k] (handle-event! this no-write state-atom fn x y z k))
      (handle! [this fn x y z k l] (handle-event! this no-write state-atom fn x y z k l))
      (handle! [this fn x y z k l m] (handle-event! this no-write state-atom fn x y z k l m))
      (handle! [this fn x y z k l m n] (handle-event! this no-write state-atom fn x y z k l m n))

      IDeref
      (deref [_] @state-atom)

      Closeable
      (close [_] (reset! state-atom ::closed)))))

(defn write-obj [oos o]
  (.writeObject oos o))

(defn backup-file [file]
  (File. (str file ".backup")))

(defn- produce-backup! [file]
  (let [backup (backup-file file)]
    (if (.exists backup)
      backup
      (when (.exists file)
        (assert (.renameTo file backup))
        backup))))

(defn- try-to-restore! [state-atom data-in]
  (let [read-value! #(.readObject data-in)]
    (while true
      (let [[& params] (read-value!)]
        (apply (partial swap! state-atom) params)))))

(defn- initial-value-restore! [state-atom data-in]
  (let [initial-state (.readObject data-in)]
    (reset! state-atom initial-state)))

(defn restore! [state-atom ^File file]
  (try
    (with-open [data-in (ObjectInputStream. (FileInputStream. file))]
      (do
        (initial-value-restore! state-atom data-in)
        (try-to-restore! state-atom data-in)))
    (catch EOFException _done)))

(defn- archive! [^File file]
  (let [new-file (File. (str file "-" (System/currentTimeMillis)))]
    (assert (.renameTo file new-file))))

(defn prevayler! [initial-state ^File file]
  (let [state-atom (atom initial-state)
        backup (produce-backup! file)]

    (when backup
      (restore! state-atom backup))

    (let [obj-out-stream (ObjectOutputStream. (FileOutputStream. file))
          write!   (partial write-obj obj-out-stream)]

      (write! @state-atom)

      (when backup
        (archive! backup))

      ;reify doesn't support varargs :(
      (reify
        Prevayler2
        (handle! [this fn] (handle-event! this write! state-atom fn))
        (handle! [this fn x] (handle-event! this write! state-atom fn x))
        (handle! [this fn x y] (handle-event! this write! state-atom fn x y))
        (handle! [this fn x y z] (handle-event! this write! state-atom fn x y z))
        (handle! [this fn x y z k] (handle-event! this write! state-atom fn x y z k))
        (handle! [this fn x y z k l] (handle-event! this write! state-atom fn x y z k l))
        (handle! [this fn x y z k l m] (handle-event! this write! state-atom fn x y z k l m))
        (handle! [this fn x y z k l m n] (handle-event! this write! state-atom fn x y z k l m n))

        Closeable
        (close [_] (.close obj-out-stream))
        IDeref
        (deref [_] @state-atom)))))
