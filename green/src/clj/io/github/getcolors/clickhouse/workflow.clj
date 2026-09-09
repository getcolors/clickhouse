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
               :provider-dns "cloudflare" :provider-backend "r2"
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

(defn wire-fn [step opts]
  (if (= :delete (:green/event opts))
    (case step
      :clickhouse/start [start-step :clickhouse/load-infrastructure]
      :clickhouse/load-infrastructure [tools/load-infrastructure-step :clickhouse/dbt]
      :clickhouse/dbt [tools/dbt-step :clickhouse/acceptance]
      :clickhouse/acceptance [tools/acceptance-step :clickhouse/ansible-cleanup]
      :clickhouse/ansible-cleanup [tools/ansible-cleanup-step :clickhouse/ansible-local]
      :clickhouse/ansible-local [tools/ansible-local-step :clickhouse/dns]
      :clickhouse/dns [tools/dns-step :clickhouse/infrastructure]
      :clickhouse/infrastructure [tools/infrastructure-step])
    (case step
      :clickhouse/start [start-step :clickhouse/infrastructure]
      :clickhouse/infrastructure [tools/infrastructure-step :clickhouse/dns]
      :clickhouse/dns [tools/dns-step :clickhouse/ansible-local]
      :clickhouse/ansible-local [tools/ansible-local-step :clickhouse/ansible-render]
      :clickhouse/ansible-render [tools/ansible-render-step :clickhouse/wireguard]
      :clickhouse/wireguard [tools/wireguard-step :clickhouse/clickhouse-config :clickhouse/metabase-config]
      :clickhouse/clickhouse-config [tools/clickhouse-config-step :clickhouse/dbt]
      :clickhouse/metabase-config [tools/metabase-config-step :clickhouse/dbt]
      :clickhouse/dbt [tools/dbt-step :clickhouse/acceptance]
      :clickhouse/acceptance [tools/acceptance-step :clickhouse/drift]
      :clickhouse/drift [tools/drift-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting [:clickhouse/infrastructure :clickhouse/load-infrastructure :clickhouse/dns
 :clickhouse/wireguard :clickhouse/clickhouse-config :clickhouse/metabase-config
 :clickhouse/ansible-local :clickhouse/ansible-cleanup :clickhouse/dbt :clickhouse/acceptance :clickhouse/drift])
(def workflow
  (reduce (fn [w tool]
            (wf/advice-add w (keyword "clickhouse" (subs tool (count "clickhouse-")))
                           :before (keyword "io.github.getcolors.clickhouse.workflow" (str "backend-" tool))
                           (backend-advice tool)))
          (-> (wf/workflow {:start :clickhouse/start :wire-fn wire-fn
 :next-fn (fn [_ successors opts] (if (or (:clickhouse/already-destroyed opts) (wf/failed? opts)) [] (mapv #(vector % opts) successors)))})
              progress/advise
              (dry-run/advise side-effecting))
          tools/tofu-tools))
