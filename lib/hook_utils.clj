(ns brepl.lib.hook-utils
  "Utilities for Claude Code hook responses."
  (:require [cheshire.core :as json]))

(defn hook-response
  "Generate a Claude Code hook JSON response.

   Args:
   - decision: \"allow\" or \"block\"
   - stop-reason: (optional) reason for blocking
   - details: (optional) map of additional fields

   Returns JSON string to stdout."
  ([decision]
   (hook-response decision nil nil))
  ([decision stop-reason]
   (hook-response decision stop-reason nil))
  ([decision stop-reason details]
   (let [response (cond-> {:continue true :decision decision}
                    stop-reason (assoc :stopReason stop-reason)
                    details (merge details))]
     (println (json/generate-string response)))))

(defn hook-allow
  "Allow Claude edit with optional details (correction, warning, etc)."
  ([]
   (hook-response "allow" nil {:suppressOutput true}))
  ([details]
   (hook-response "allow" nil (assoc details :suppressOutput true))))

(defn hook-block
  "Block Claude edit with reason and details."
  [stop-reason reason-details]
  (hook-response "block" stop-reason {:reason reason-details}))

(defn hook-error
  "Return an error indicating invalid arguments or missing file."
  [error-type message]
  (hook-response "block" error-type {:reason message}))
