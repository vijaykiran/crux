(ns example-standalone-webservice.backup
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [crux.io :as crux-io]
            [crux.kv :as kv]
            [clojure.java.shell :refer [sh]])
  (:import
   [java.io File]
   [java.nio.file Files StandardCopyOption Path]
   [java.util.zip ZipEntry ZipOutputStream]))

(defmulti upload-to-backend :backend)

(defmulti download-from-backend :backend)

(defn check-and-restore
  [{:keys [event-log-dir db-dir] :as system-options}
   backup-options]
  (when-not (or (-> event-log-dir io/file .exists)
                (-> db-dir io/file .exists))
    (log/info "no existing db on disk trying to restore from backup")
    ))

(defn backup-current-version
  [{:keys [kv-store event-log-kv-store] :as crux-system}
   {:keys [event-log-dir db-dir] :as system-options}
   {:keys [backup-dir] :as backup-options}]
  (locking crux-system
    (let [backup-dir (io/file backup-dir)
          checkpoint-dir (io/file backup-dir "checkpoint")]
      (when-not (.exists backup-dir) (.mkdir backup-dir))
      (crux-io/delete-dir checkpoint-dir)
      (.mkdir checkpoint-dir)
      (log/infof "creating checkpoint for crux backup: %s" (.getPath checkpoint-dir))

      (kv/backup kv-store (io/file checkpoint-dir "kv-store"))
      (when event-log-kv-store
        (kv/backup event-log-kv-store (io/file checkpoint-dir "event-log-kv-store")))

      (upload-to-backend
        (merge backup-options {:checkpoint-dir checkpoint-dir})))))

(defmethod upload-to-backend :shell
  [{:keys [checkpoint-dir backup-dir shell/backup-script]}]
  (let [{:keys [exit] :as shell-response}
        (sh backup-script
            :env (merge {"CHECKPOINT_DIR" (.getPath checkpoint-dir)
                         "BACKUP_DIR" backup-dir}
                        (System/getenv)))]
    (when-not (= exit 0) (throw (ex-info "backup-script failed" shell-response)))))

(defmethod download-from-backend :shell
  [_ _])

(defmethod upload-to-backend :file
  [{:keys [file/path file/replace? checkpoint-file]
    :or {replace? false}}]
  (let [backup-folder (io/file path)
        new-file (io/file
                   backup-folder
                   (if replace?
                     "backup.zip"
                     (format "backup-%s.zip" (.getTime (java.util.Date.)))))]
    (when-not (.exists backup-folder) (.mkdir backup-folder))
    (when (.exists new-file) (.delete new-file))
    (io/copy checkpoint-file new-file)))

(defmethod download-from-backend :file
  [{:keys [file/path file/replace?]
    :or {replace? false}}]
  (let [f (io/file path "backup.zip")]
    (when (.exists f) f)))
