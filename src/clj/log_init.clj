(ns log-init
  ;; logback.xml's rolling appenders read ${LOGS_DIR:-logs}, but only at the
  ;; moment logback first initialises -- which opens BOTH file appenders and so
  ;; creates the directory immediately. config's apply-logs-dir set the property
  ;; too late: logback had already initialised against the default ./logs,
  ;; creating that folder and stranding any lines logged before the reconfigure.
  ;; Setting the property here, in the first namespace `server` requires (and
  ;; one that pulls in no logging deps), guarantees it is in place before the
  ;; first logger is ever created. Dev always logs to ./logs (hardcoded, like
  ;; the media folders), so we only override in prod when :folders :logs is set.
  ;; config.edn is read directly (not via the `config` ns) to stay dependency-free.
  (:require [aero.core :as aero]))

(let [c   (try (aero/read-config "./config.edn") (catch Exception _ nil))
      dir (when (and c (not (:dev? c))) (get-in c [:folders :logs]))]
  (when dir
    (System/setProperty "LOGS_DIR" dir)))
