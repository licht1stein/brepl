(ns brepl.lib.discovery
  "Discovers running nREPL servers by scanning listening ports."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [bencode.core :as bencode]
            [clojure.string :as str])
  (:import [java.net Socket]
           [java.io PushbackInputStream]))

(defn- ->str [x]
  (if (bytes? x) (String. x) x))

(defn get-listening-ports []
  (try
    (let [result (process/shell {:out :string :err :string :continue true :timeout 5000}
                                "sh" "-c" "lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null")]
      (when (zero? (:exit result))
        (:out result)))
    (catch java.util.concurrent.TimeoutException _ nil)
    (catch Exception _ nil)))

(defn parse-lsof-output [output]
  (if (str/blank? output)
    []
    (let [known-commands #{"java" "clojure" "babashka" "bb"}
          port-re #"TCP\s+(?:\*|[\d.]+|\[[\da-fA-F:]+\]):(\d+)\s+\(LISTEN\)"
          lines (str/split-lines output)]
      (->> lines
           (keep (fn [line]
                   (let [command (-> line str/trim (str/split #"\s+") first)]
                     (when (and command (contains? known-commands (str/lower-case command)))
                       (when-let [m (re-find port-re line)]
                         (Integer/parseInt (second m)))))))
           distinct
           vec))))

(defn validate-nrepl-port [host port]
  (let [socket (Socket. host (int port))]
    (try
      (.setSoTimeout socket 2000)
      (let [out (.getOutputStream socket)
            in (PushbackInputStream. (.getInputStream socket))]
        (bencode/write-bencode out {"op" "describe" "id" "discovery"})
        (.flush out)
        (let [response (bencode/read-bencode in)
              str-keys (into {} (map (fn [[k v]] [(->str k) (->str v)])) response)]
          (contains? str-keys "ops")))
      (catch Exception _ false)
      (finally (.close socket)))))

(defn get-nrepl-cwd [host port]
  (let [socket (Socket. host (int port))]
    (try
      (.setSoTimeout socket 2000)
      (let [out (.getOutputStream socket)
            in (PushbackInputStream. (.getInputStream socket))]
        (bencode/write-bencode out {"op" "eval"
                                    "code" "(System/getProperty \"user.dir\")"
                                    "id" "discovery-cwd"})
        (.flush out)
        (loop [value nil]
          (let [response (bencode/read-bencode in)
                status-set (some->> (get response "status") (map ->str) set)
                v (or value
                      (when-let [raw (get response "value")]
                        (let [s (->str raw)]
                          (str/replace s #"^\"|\"$" ""))))]
            (if (contains? status-set "done")
              v
              (recur v)))))
      (catch Exception _ nil)
      (finally (.close socket)))))

(defn discover-nrepl-port []
  (when-let [output (get-listening-ports)]
    (let [ports (parse-lsof-output output)
          cwd (str (fs/canonicalize (fs/cwd)))]
      (reduce (fn [_ port]
                (when (validate-nrepl-port "localhost" port)
                  (when-let [remote-cwd (get-nrepl-cwd "localhost" port)]
                    (let [remote-canonical (str (fs/canonicalize remote-cwd))]
                      (when (= cwd remote-canonical)
                        (reduced port))))))
              nil
              ports))))
