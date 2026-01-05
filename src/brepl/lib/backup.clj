(ns brepl.lib.backup
  "Backup and restore functionality for Claude Code hooks."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files Paths]
           [java.nio.file.attribute PosixFilePermissions]))

(defn session-backup-dir
  "Get the backup directory for a session."
  [session-id]
  (io/file (str "/tmp/brepl-hooks-" session-id)))

(defn ensure-session-dir
  "Create session backup directory if it doesn't exist."
  [session-id]
  (let [dir (session-backup-dir session-id)]
    (.mkdirs dir)
    dir))

(defn backup-file-path
  "Get the backup file path for a given file."
  [session-id file-path]
  (let [dir (ensure-session-dir session-id)
        ;; Hash the file path to create a unique backup filename
        hash-code (str (Math/abs (.hashCode file-path)))
        backup-name (str "backup-" hash-code ".clj")]
    (io/file dir backup-name)))

(defn get-file-permissions
  "Get POSIX file permissions from a file."
  [file-path]
  (try
    (let [path (Paths/get file-path (make-array String 0))
          perms (Files/getPosixFilePermissions path nil)]
      perms)
    (catch Exception _
      nil)))

(defn set-file-permissions
  "Set POSIX file permissions on a file."
  [file-path perms]
  (when perms
    (try
      (let [path (Paths/get file-path (make-array String 0))]
        (Files/setPosixFilePermissions path perms))
      (catch Exception _
        nil))))

(defn create-backup
  "Create a backup of a file before it's edited.
   Returns the backup file path, or nil if backup fails."
  [file-path session-id]
  (try
    (if (not (.exists (io/file file-path)))
      ;; File doesn't exist yet, no backup needed
      nil
      ;; File exists, create backup
      (let [backup-path (backup-file-path session-id file-path)
            original-content (slurp file-path)
            perms (get-file-permissions file-path)]
        ;; Write backup
        (spit backup-path original-content)
        ;; Preserve permissions
        (when perms
          (set-file-permissions (str backup-path) perms))
        ;; Store metadata for restore
        (spit (io/file (str backup-path ".meta"))
              (str file-path))
        backup-path))
    (catch Exception e
      ;; Backup failed
      nil)))

(defn restore-backup
  "Restore a file from backup.
   Returns true if restore succeeded, false otherwise."
  [session-id file-path]
  (try
    (let [backup-path (backup-file-path session-id file-path)]
      (if (.exists backup-path)
        (let [backup-content (slurp backup-path)
              perms (get-file-permissions (str backup-path))]
          ;; Restore content
          (spit file-path backup-content)
          ;; Restore permissions
          (when perms
            (set-file-permissions file-path perms))
          true)
        false))
    (catch Exception e
      false)))

(defn cleanup-session
  "Delete all backup files for a session."
  [session-id]
  (let [dir (session-backup-dir session-id)]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir)
      true)))
