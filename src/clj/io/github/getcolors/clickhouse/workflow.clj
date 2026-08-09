(ns io.github.getcolors.clickhouse.workflow
  "Lifecycle graph and backend advice."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
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
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (lifecycle-events event)) (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]}
    env)))

(defn pass-step [opts] (assoc opts :green/exit 0))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :clickhouse/start [start-step :clickhouse/dbt]
      :clickhouse/dbt [tools/dbt-step :clickhouse/acceptance]
      :clickhouse/acceptance [tools/acceptance-step :clickhouse/ansible-cleanup]
      :clickhouse/ansible-cleanup [tools/ansible-cleanup-step
                                   :clickhouse/dns :clickhouse/firewall]
      :clickhouse/dns [tools/dns-step :clickhouse/infrastructure-clean]
      :clickhouse/firewall [tools/firewall-step :clickhouse/infrastructure-clean]
      :clickhouse/infrastructure-clean [pass-step
                                        :clickhouse/node-1 :clickhouse/node-2
                                        :clickhouse/node-3 :clickhouse/metabase]
      :clickhouse/node-1 [tools/node-1-step :clickhouse/access]
      :clickhouse/node-2 [tools/node-2-step :clickhouse/access]
      :clickhouse/node-3 [tools/node-3-step :clickhouse/access]
      :clickhouse/metabase [tools/metabase-step :clickhouse/access]
      :clickhouse/access [tools/access-step :clickhouse/network]
      :clickhouse/network [tools/network-step])
    (case step
      :clickhouse/start [start-step :clickhouse/network]
      :clickhouse/network [tools/network-step :clickhouse/access]
      :clickhouse/access [tools/access-step
                          :clickhouse/node-1 :clickhouse/node-2
                          :clickhouse/node-3 :clickhouse/metabase]
      :clickhouse/node-1 [tools/node-1-step :clickhouse/firewall]
      :clickhouse/node-2 [tools/node-2-step :clickhouse/firewall]
      :clickhouse/node-3 [tools/node-3-step :clickhouse/firewall]
      :clickhouse/metabase [tools/metabase-step :clickhouse/firewall]
      :clickhouse/firewall [tools/firewall-step :clickhouse/dns]
      :clickhouse/dns [tools/dns-step :clickhouse/ansible-render]
      :clickhouse/ansible-render [tools/ansible-render-step :clickhouse/wireguard]
      :clickhouse/wireguard [tools/wireguard-step
                             :clickhouse/clickhouse-config :clickhouse/metabase-config]
      :clickhouse/clickhouse-config [tools/clickhouse-config-step :clickhouse/dbt]
      :clickhouse/metabase-config [tools/metabase-config-step :clickhouse/dbt]
      :clickhouse/dbt [tools/dbt-step :clickhouse/acceptance]
      :clickhouse/acceptance [tools/acceptance-step :clickhouse/drift]
      :clickhouse/drift [tools/drift-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting [:clickhouse/network :clickhouse/access :clickhouse/node-1 :clickhouse/node-2
                     :clickhouse/node-3 :clickhouse/metabase :clickhouse/firewall
                     :clickhouse/dns :clickhouse/wireguard :clickhouse/clickhouse-config
                     :clickhouse/metabase-config :clickhouse/ansible-cleanup :clickhouse/dbt
                     :clickhouse/acceptance :clickhouse/drift])
(def workflow
  (reduce (fn [w tool]
            (wf/advice-add w (keyword "clickhouse" (subs tool (count "clickhouse-")))
                           :before (keyword "io.github.getcolors.clickhouse.workflow" (str "backend-" tool))
                           (backend-advice tool)))
          (-> (wf/workflow {:start :clickhouse/start :wire-fn wire-fn})
              progress/advise
              (dry-run/advise side-effecting))
          tools/tofu-tools))
