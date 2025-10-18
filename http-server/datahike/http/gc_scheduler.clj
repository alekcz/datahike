(ns datahike.http.gc-scheduler
  "Automatic garbage collection scheduler for Datahike server.
   Uses overtone/at-at for scheduling periodic GC runs."
  (:require
   [datahike.gc :as gc]
   [datahike.store :as ds]
   [overtone.at-at :as at]
   [taoensso.timbre :as log]
   [clojure.core.async :refer [<!!]])
  (:import [java.util Date]))

(defonce ^:private scheduler-pool (at/mk-pool))
(defonce ^:private scheduled-jobs (atom {}))
(defonce ^:private scheduling-lock (Object.))

(defn- calculate-gc-cutoff-date
  "Calculate the cutoff date for GC based on retention days.
   Anything older than this date will be garbage collected."
  [retention-days]
  (let [cutoff-ms (- (System/currentTimeMillis)
                     (* retention-days 24 60 60 1000))]
    (Date. cutoff-ms)))

(defn- run-gc-task
  "Execute garbage collection for a specific database connection.
   Retains commits from the last retention-days days."
  [conn-config retention-days]
  (try
    (let [store-id (ds/store-identity (:store conn-config))
          branch (or (:branch conn-config) "main")
          conn-id [store-id branch]]
      (log/info "Starting scheduled GC for database:" conn-id)
      (let [conn (datahike.api/connect conn-config)
            cutoff-date (calculate-gc-cutoff-date retention-days)
            result (<!! (gc/gc-storage! conn cutoff-date))]
        (log/info "GC completed successfully for database:" conn-id
                  "Cutoff date:" cutoff-date)
        result))
    (catch Exception e
      (log/error e "Error during scheduled GC for database:"
                 (select-keys conn-config [:name :store :branch])))))

(defn schedule-gc
  "Schedule periodic garbage collection for a database connection.

   Parameters:
   - conn-config: The database configuration map
   - interval-hours: How often to run GC (default: 24 hours)
   - retention-days: How many days of history to retain (default: 7 days)

   Returns a job ID that can be used to cancel the scheduled GC."
  ([conn-config]
   (schedule-gc conn-config 24 7))
  ([conn-config interval-hours retention-days]
   ;; Thread-safe scheduling to prevent race conditions
   (locking scheduling-lock
     (let [;; Use same identification as Datahike's connection management
           store-id (ds/store-identity (:store conn-config))
           branch (or (:branch conn-config) "main")
           conn-id [store-id branch]
           interval-ms (* interval-hours 60 60 1000)
           job-fn #(run-gc-task conn-config retention-days)
           existing-job (get @scheduled-jobs conn-id)]

       ;; If job already exists for this exact database+branch, do nothing
       (if existing-job
         (do
           (log/info "GC already scheduled for database:" conn-id "- skipping duplicate scheduling")
           conn-id)
         (do
           ;; First time scheduling for this database+branch
           (log/info "Scheduling GC for database:" conn-id
                     "Interval:" interval-hours "hours"
                     "Retention:" retention-days "days")

           ;; Run GC immediately on first schedule
           (job-fn)

           ;; Schedule recurring GC
           (let [job (at/every interval-ms
                               job-fn
                               scheduler-pool
                               :desc (str "GC for " conn-id))]
             (swap! scheduled-jobs assoc conn-id job)
             conn-id)))))))

(defn unschedule-gc
  "Cancel scheduled garbage collection for a database connection.

   Parameters:
   - conn-id: The connection identifier returned by schedule-gc"
  [conn-id]
  (when-let [job (get @scheduled-jobs conn-id)]
    (log/info "Unscheduling GC for database:" conn-id)
    (at/stop job)
    (swap! scheduled-jobs dissoc conn-id)
    true))

(defn unschedule-all
  "Cancel all scheduled garbage collection jobs."
  []
  (log/info "Unscheduling all GC jobs")
  (doseq [[conn-id job] @scheduled-jobs]
    (at/stop job))
  (reset! scheduled-jobs {}))

(defn list-scheduled-jobs
  "List all currently scheduled GC jobs."
  []
  (keys @scheduled-jobs))

(defn shutdown
  "Shutdown the GC scheduler and cleanup resources."
  []
  (log/info "Shutting down GC scheduler")
  (unschedule-all)
  (at/stop-and-reset-pool! scheduler-pool :strategy :kill))