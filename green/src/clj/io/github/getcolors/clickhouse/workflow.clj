(ns io.github.getcolors.clickhouse.workflow
  "Lifecycle graph and backend advice."
  (:require [io.github.getcolors.clickhouse.storage :as storage]
            [io.github.getcolors.compute-managed-backend :as managed-backend]
            [clojure.string :as str]
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

(defn backend-finalize-step [opts]
  (try
    (let [result (managed-backend/finalize-backend! opts (merge (into {} (System/getenv)) (storage/aws-env opts)))]
      (if (contains? #{"destroyed" "absent" "skipped"} (:status result))
        (assoc opts :green/exit 0)
        (assoc opts :green/exit 1 :green/err "managed backend finalization refused; live or unowned state remains")))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "managed backend finalization refused; live or unowned state remains"))))

(defn wire-fn [step opts]
  (if (= :delete (:green/event opts))
    (case step
      :clickhouse/start [start-step :clickhouse/load-infrastructure]
      :clickhouse/load-infrastructure [tools/load-infrastructure-step :clickhouse/dbt]
      :clickhouse/dbt [tools/dbt-step :clickhouse/acceptance]
      :clickhouse/acceptance [tools/acceptance-step :clickhouse/ansible-cleanup]
      :clickhouse/ansible-cleanup [tools/ansible-cleanup-step :clickhouse/ansible-local]
      :clickhouse/ansible-local [tools/ansible-local-step :clickhouse/dns]
      :clickhouse/dns [tools/dns-step (if (storage/managed? opts) :clickhouse/storage :clickhouse/infrastructure)]
      :clickhouse/storage [storage/step :clickhouse/infrastructure]
      :clickhouse/infrastructure (cond-> [tools/infrastructure-step] (= "managed" (:s3-bucket-mode opts)) (conj :clickhouse/backend-finalize))
      :clickhouse/backend-finalize [backend-finalize-step])
    (case step
      :clickhouse/start [start-step :clickhouse/infrastructure]
      :clickhouse/infrastructure [tools/infrastructure-step (if (storage/managed? opts) :clickhouse/storage :clickhouse/dns)]
      :clickhouse/storage [storage/step :clickhouse/dns]
      :clickhouse/dns [tools/dns-step :clickhouse/ansible-local]
      :clickhouse/ansible-local [tools/ansible-local-step :clickhouse/ansible-render]
      :clickhouse/ansible-render [tools/ansible-render-step :clickhouse/wireguard]
      :clickhouse/wireguard [tools/wireguard-step :clickhouse/clickhouse-config :clickhouse/metabase-config]
      :clickhouse/clickhouse-config [tools/clickhouse-config-step :clickhouse/dbt]
      :clickhouse/metabase-config [tools/metabase-config-step :clickhouse/dbt]
      :clickhouse/dbt [tools/dbt-step :clickhouse/acceptance]
      :clickhouse/acceptance [tools/acceptance-step (if (:clickhouse-backup-bucket opts) :clickhouse/rehearsal :clickhouse/drift)]
      :clickhouse/rehearsal [tools/rehearsal-step :clickhouse/drift]
      :clickhouse/drift [tools/drift-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting [:clickhouse/rehearsal :clickhouse/storage :clickhouse/backend-finalize :clickhouse/infrastructure :clickhouse/load-infrastructure :clickhouse/dns
 :clickhouse/wireguard :clickhouse/clickhouse-config :clickhouse/metabase-config
 :clickhouse/ansible-local :clickhouse/ansible-cleanup :clickhouse/dbt :clickhouse/acceptance :clickhouse/drift])
(def workflow
  (reduce (fn [w tool]
            (wf/advice-add w (keyword "clickhouse" (subs tool (count "clickhouse-")))
                           :before (keyword "io.github.getcolors.clickhouse.workflow" (str "backend-" tool))
                           (backend-advice tool)))
          (-> (wf/workflow {:start :clickhouse/start :wire-fn wire-fn
 :next-fn (fn [_ successors opts] (if (or (:clickhouse/already-destroyed opts) (wf/failed? opts)) [] (if (:clickhouse/finalize-only opts) [[:clickhouse/backend-finalize (dissoc opts :clickhouse/finalize-only)]] (mapv #(vector % opts) successors))))})
              progress/advise
              (dry-run/advise side-effecting))
          tools/tofu-tools))
