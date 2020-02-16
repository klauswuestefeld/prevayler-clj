(ns prevayler2
  (:require [taoensso.nippy :as nippy]
            [clojure.main :refer [demunge]])
  (:import
    [java.io File FileOutputStream FileInputStream DataInputStream DataOutputStream EOFException]
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

(defn fn-name [f]
  (as-> (str f) $
    (demunge $)
    (or (re-find #"(.+)--\d+@" $)
      (re-find #"(.+)@" $))
    (last $)))

(defn handle-event!
  [this write-fn state-atom fn & args]
  (locking this
    (let [params            (remove nil? args)
          state-with-result (apply (partial swap! state-atom fn) params)]
      (write-fn [(fn-name fn) params])
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

(defn- write-with-flush! [data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

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
  (let [read-value! #(nippy/thaw-from-in! data-in)]
    (while true                                             ;Ends with EOFException
      (let [[fn params] (read-value!)]
        (apply (partial swap! state-atom (ns-resolve *ns* (symbol fn))) params)))))

(defn restore! [state-atom ^File file]
  (with-open [data-in (-> file FileInputStream. DataInputStream.)]
    (try
      (try-to-restore! state-atom data-in)
      (catch EOFException _done)
      (catch Exception corruption
        (println "Warning - Corruption at end of prevalence file (this is normally OK and can happen when the process is killed during write):" corruption)))))

(defn prevayler! [initial-state ^File file]
  (let [state-atom (atom initial-state)
        backup (produce-backup! file)]

    (when backup
      (restore! state-atom backup))

    (let [data-out (-> file FileOutputStream. DataOutputStream.)
          write!   (partial write-with-flush! data-out)]
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
        (close [_] (.close data-out))
        IDeref
        (deref [_] @state-atom)))))
