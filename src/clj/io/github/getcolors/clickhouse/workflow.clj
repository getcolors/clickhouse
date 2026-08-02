(ns io.github.getcolors.clickhouse.workflow
  "Lifecycle graph and backend advice."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.clickhouse.tools :as tools]
            [io.github.getcolors.clickhouse.validate :as validate]))

(def defaults {:compute-prevent-destroy true :provider-compute "hcloud"
               :provider-dns "cloudflare" :provider-backend "local"
               :workdir ".colors"})
(def lifecycle-events #{:create :delete})

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (let [opts (green-cli/read-pars (merge defaults opts) env)
         event (:green/event opts) real? (not (:green/dry-run opts))
         errors (vec (concat (validate/env-errors env)
                             (validate/state-errors opts)
                             (when (and real? (lifecycle-events event))
                               (validate/secret-errors opts))
                             (when (and real? (= :delete event)
                                        (:compute-prevent-destroy opts))
                               [(str "compute destruction is protected; set "
                                     (green-cli/par-name :compute-prevent-destroy)
                                     "=false to delete")])))]
     (if (seq errors)
       (assoc opts :green/exit 2 :green/err (str/join "\n" errors))
       (assoc opts :green/exit 0)))))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :clickhouse/start [start-step :clickhouse/dbt]
      :clickhouse/dbt [tools/dbt-step :clickhouse/ansible]
      :clickhouse/ansible [tools/ansible-step :clickhouse/dns]
      :clickhouse/dns [tools/dns-step :clickhouse/firewall]
      :clickhouse/firewall [tools/firewall-step :clickhouse/metabase]
      :clickhouse/metabase [tools/metabase-step :clickhouse/node-3]
      :clickhouse/node-3 [tools/node-3-step :clickhouse/node-2]
      :clickhouse/node-2 [tools/node-2-step :clickhouse/node-1]
      :clickhouse/node-1 [tools/node-1-step :clickhouse/network]
      :clickhouse/network [tools/network-step])
    (case step
      :clickhouse/start [start-step :clickhouse/network]
      :clickhouse/network [tools/network-step :clickhouse/node-1]
      :clickhouse/node-1 [tools/node-1-step :clickhouse/node-2]
      :clickhouse/node-2 [tools/node-2-step :clickhouse/node-3]
      :clickhouse/node-3 [tools/node-3-step :clickhouse/metabase]
      :clickhouse/metabase [tools/metabase-step :clickhouse/firewall]
      :clickhouse/firewall [tools/firewall-step :clickhouse/dns]
      :clickhouse/dns [tools/dns-step :clickhouse/ansible]
      :clickhouse/ansible [tools/ansible-step :clickhouse/dbt]
      :clickhouse/dbt [tools/dbt-step])))

(defn backend-advice [tool]
  (let [dir-fn #(tools/tool-dir % tool)
        state-key #(str (:profile %) "/" tool ".tfstate")]
    (tofu/backends
     #(or (:provider-backend %) "local")
     {"local" (tofu/local-backend-advice dir-fn)
      "s3" (tofu/s3-backend-advice dir-fn #(hash-map :bucket (:s3-bucket %)
                                                       :key (state-key %)
                                                       :region (:s3-region %)))
      "r2" (tofu/r2-backend-advice dir-fn #(hash-map :bucket (:r2-bucket %)
                                                       :key (state-key %)
                                                       :endpoint (:r2-endpoint %)))})))

(def side-effecting [:clickhouse/network :clickhouse/node-1 :clickhouse/node-2
                     :clickhouse/node-3 :clickhouse/metabase :clickhouse/firewall :clickhouse/dns
                     :clickhouse/ansible :clickhouse/dbt])
(def workflow
  (reduce (fn [w tool]
            (wf/advice-add w (keyword "clickhouse" (subs tool (count "clickhouse-")))
                           :before (keyword "io.github.getcolors.clickhouse.workflow" (str "backend-" tool))
                           (backend-advice tool)))
          (-> (wf/workflow {:start :clickhouse/start :wire-fn wire-fn})
              progress/advise
              (dry-run/advise side-effecting))
          tools/tofu-tools))
