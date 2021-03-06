;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns cook.mesos.util
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d :refer (q)]
            [metatransaction.core :refer (db)]
            [metrics.timers :as timers]))

(defn get-all-resource-types
  "Return a list of all supported resources types. Example, :cpus :mem ..."
  [db]
  (->> (q '[:find ?ident
            :where
            [?e :resource.type/mesos-name ?ident]]
          db)
       (map first)))

(defn fixup-param-keywords
  "helper function, handles docker parameters"
  [m]
  (if (seq m)
    { :parameters (mapv (fn [{kname :docker.param/key
                              value :docker.param/value}]
                          {:key kname :value value})m)}
    {}))

(defn fixup-port-mapping-keywords
  "helper function, handles docker container port mappings"
  [m]
  (if (seq m)
    { :port-mapping
     (mapv
      (fn [{container-port :docker.portmap/container-port
            host-port :docker.portmap/host-port
            protocol :docker.portmap/protocol}]
        (into {:container-port container-port
               :host-port host-port}
              (when protocol {:protocol protocol}))) m) }
    {}))

(defn fixup-docker-keywords
  "Helper function for fixup-keywords below, handles docker map"
  [{image :docker/image
    params :docker/parameters
    port-mapping :docker/port-mapping
    network :docker/network}]
  (into {:image image :network network}
        (concat (fixup-param-keywords params)
                (fixup-port-mapping-keywords port-mapping))))

(defn fixup-volume-keywords
  "Helper funtion for fixup-keywords below, handles container volumes"
  [m]
  (mapv (fn [{container-path :container.volume/container-path
              host-path :container.volume/host-path
              mode :container.volume/mode}]
          (into {:host-path host-path}
                (concat (when container-path
                          {:container-path
                           container-path})
                        (when mode {:mode mode}))))m))

(defn fixup-keywords
  "Walk through a container from datomic, substituting keyords as needed"
  [{ctype :container/type
    volumes :container/volumes
    docker :container/docker}]
  (into {} (concat
            (when ctype {:type ctype})
            (when docker {:docker (fixup-docker-keywords docker)})
            (when volumes {:volumes (fixup-volume-keywords volumes)}))))

(defn job-ent->container
  "Take a job entity and return its container"
  [db job job-ent]
  (log/info "---- db -----")
  (log/info (clojure.pprint/write db :stream nil))
  (log/info "---- job ----")
  (log/info (clojure.pprint/write job :stream nil))
  (log/info "-- job-ent --")
  (log/info (clojure.pprint/write job-ent :stream nil))
  (log/info "-------------")
  ;(if (contains? job-ent :job/container)
  (if-let [ceid (:db/id (:job/container job-ent))]
    (let [
          rm-dbids (fn rm-dbids [m]
                     (cond
                       (map? m)
                       (let [sm (filter (fn [p]
                                          (not(= :db/id (first p)))) m)]
                         (into {} (map (fn [p]
                                         [(first p) (rm-dbids (second p))])
                                       sm)))
                       (vector? m)
                       (mapv rm-dbids m)
                       :else
                       m))
          cmap (d/pull db "[*]" ceid)]
      (log/info "---- ceid ----")
      (log/info (clojure.pprint/write ceid :stream nil))
      (log/info "---- cmap ----")
      (log/info (clojure.pprint/write cmap :stream nil))
      (log/info "---- final ----")
      (log/info (clojure.pprint/write (-> cmap rm-dbids fixup-keywords) :stream nil))
      (log/info "--------------")

      (-> cmap rm-dbids fixup-keywords))
    {}))

(defn job-ent->env
  "Take a job entity and return the environment variable map"
  [job-ent]
  (reduce (fn [m env-var]
            (assoc m
                   (:environment/name env-var)
                   (:environment/value env-var)))
          {}
          (:job/environment job-ent)))

(defn job-ent->resources
  "Take a job entity and return a resource map. NOTE: the keys must be same as mesos resource keys"
  [job-ent]
  (reduce (fn [m r]
            (let [resource (keyword (name (:resource/type r)))]
              (condp contains? resource
                #{:cpus :mem} (assoc m resource (:resource/amount r))
                #{:uri} (update-in m [:uris] (fnil conj [])
                                   {:cache (:resource.uri/cache? r false)
                                    :executable (:resource.uri/executable? r false)
                                    :value (:resource.uri/value r)
                                    :extract (:resource.uri/extract? r false)}))))
          {:ports (:job/port job-ent)}
          (:job/resource job-ent)))

(defn sum-resources-of-jobs
  "Given a collections of job entities, returns the total resources they use
   {:cpus cpu :mem mem}"
  [job-ents]
  (loop [total-cpus 0.0
         total-mem 0.0
         [job-ent & job-ents] job-ents]
    (if job-ent
      (let [{:keys [cpus mem]} (job-ent->resources job-ent)]
        (recur (+ total-cpus cpus)
               (+ total-mem mem)
               job-ents))
      {:cpus total-cpus :mem total-mem})))

(timers/deftimer [cook-mesos scheduler get-pending-jobs-duration])

(defn get-pending-job-ents
  [db]
  (timers/time!
    get-pending-jobs-duration
    (->> (q '[:find ?j
              :in $ [?state ...]
              :where
              [?j :job/state ?state]]
            db [:job.state/waiting])
         (map (fn [[x]] (d/entity db x))))))

(timers/deftimer [cook-mesos scheduler get-running-tasks-duration])

(defn get-running-task-ents
  "Returns all running task entities"
  [db]
  (timers/time!
    get-running-tasks-duration
    (->> (q '[:find ?i
              :in $ [?status ...]
              :where
              [?i :instance/status ?status]]
            db [:instance.status/running :instance.status/unknown])
         (map (fn [[x]] (d/entity db x))))))

(defn create-task-ent
  "Takes a pending job entity and returns a synthetic running task entity for that job"
  [pending-job-ent & {:keys [hostname] :or {hostname nil}}]
  {:job/_instance pending-job-ent
   :instance/status :instance.status/running
   :instance/hostname hostname})

(defn task-ent->user
  [task-ent]
  (get-in task-ent [:job/_instance :job/user]))

(def ^:const default-job-priority 50)

(defn same-user-task-comparator
  "Comparator to order same user's tasks"
  [task1 task2]
  (letfn [(task->feature-vector [task]
            ;; Last two elements are aribitary tie breakers.
            ;; Use :db/id because they guarantee uniqueness for different entities
            ;; (:db/id task) is not sufficient because synthetic task entities don't have :db/id
            ;; This assumes there are at most one synthetic task for a job, otherwise uniqueness invariant will break
            [(- (:job/priority (:job/_instance task) default-job-priority))
             (:instance/start-time task (java.util.Date. Long/MAX_VALUE))
             (:db/id task)
             (:db/id (:job/_instance task))])]
    (compare (task->feature-vector task1) (task->feature-vector task2))))
