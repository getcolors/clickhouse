(ns io.github.getcolors.clickhouse.tools
  "OpenTofu and Ansible stages for the fixed v1 topology."
  (:require [io.github.getcolors.clickhouse.ssh-config :as ssh-config]
            [io.github.getcolors.compute-ssh :as compute-ssh]
            [io.github.getcolors.compute-drift :as compute-drift]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.cli :as green-cli]
            [green.providers :as provider-ops]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.clickhouse.utils :as utils]
            [io.github.getcolors.clickhouse.compute :as compute]
            [io.github.getcolors.compute-planning :as planning]
            [io.github.getcolors.compute-orchestration :as orchestration]
            [io.github.getcolors.compute-inspection :as inspection]
            [io.github.getcolors.clickhouse.validate :as validate]))

(def infrastructure-tool "clickhouse-infrastructure")
(def dns-tool "clickhouse-dns")
(def ansible-tool "clickhouse-ansible")
(def dbt-tool "clickhouse-dbt")
(def acceptance-tool "clickhouse-acceptance")
(def tofu-tools [dns-tool])

(def root "io.github.getcolors.clickhouse.tools")
(def template-opts sc/preserve-jinja-delimiters)
(defn tool-dir [opts tool]
  (green-cli/stage-dir opts tool {:default-profile "clickhouse"}))
(defn template [path file] (keyword (str root "." path) file))
(defn spec [template target data] {:template template :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))

(defn credential-env [opts & slots]
  (provider-ops/tool-env validate/providers opts
                         (conj (vec slots) :provider-backend)))

(defn tofu-step [opts tool specs slots]
  (tofu/tofu-with-spec opts specs {:dir (tool-dir opts tool)
                                   :env (merge (apply credential-env opts slots)
                                               (:clickhouse/process-env opts))}))

(defn- refuse [opts errors] (assoc opts :green/exit 1 :green/err (str/join "\n" errors)))
(defn- compute-json [value indent]
  (let [padding #(apply str (repeat % " "))]
    (cond
      (map? value) (if (empty? value) "{}"
                      (str "{\n" (str/join ",\n" (for [[key item] (sort-by key value)]
                                                       (str (padding (+ indent 2)) (json/generate-string key) ": " (compute-json item (+ indent 2)))))
                           "\n" (padding indent) "}"))
      (sequential? value) (if (empty? value) "[]"
                              (str "[\n" (str/join ",\n" (map #(str (padding (+ indent 2)) (compute-json % (+ indent 2))) value)) "\n" (padding indent) "]"))
      :else (json/generate-string value))))

(defn infrastructure-step [opts]
  (try
    (let [planning? (or (= :build (:green/event opts)) (:green/dry-run opts))
          result (if planning?
                   (planning/plan-deployment opts compute/topology (compute/requirements opts))
                   (orchestration/orchestrate opts compute/topology (compute/requirements opts)))]
      (when planning?
        (doseq [[stage documents] (cons ["shared" (get-in result [:documents :shared])]
                                      (map (fn [[id documents]] [(str "nodes/" id) documents]) (get-in result [:documents :nodes])))
                [filename document] documents]
          (let [target (io/file (tool-dir opts infrastructure-tool) stage filename)]
            (io/make-parents target)
            (spit target (str (compute-json document 0) "\n")))))
      (if-not (contains? #{"ready" "planned" "destroyed"} (:status result))
        (assoc opts :green/exit 1 :green/err (if (seq (:errors result)) (str/join "\n" (:errors result)) "compute lifecycle refused; inspect deployment state before retrying"))
        (cond-> (assoc opts :green/exit 0)
          (:cluster result) (assoc :colors-compute/cluster (:cluster result) :colors-compute/shared (:shared result))
          (get-in result [:key :private_key_path])
          (assoc :ssh-private-key-path (if planning? (str/replace (get-in result [:key :private_key_path]) "$HOME/.ssh" "/home/build-placeholder/.ssh") (get-in result [:key :private_key_path]))))))
    (catch Exception _ (assoc opts :green/exit 1 :green/err "compute lifecycle refused; inspect deployment state before retrying"))))

(defn load-infrastructure-step [opts]
  (if (or (= :build (:green/event opts)) (:green/dry-run opts)) (infrastructure-step opts)
      (let [result (inspection/read-deployment opts)]
        (case (:status result)
          "destroyed" (if (= :delete (:green/event opts)) (assoc opts :clickhouse/already-destroyed true :green/exit 0) (refuse opts ["compute inventory unavailable"]))
          "present" (cond-> (assoc opts :colors-compute/cluster (:cluster result) :colors-compute/shared (:shared result) :clickhouse/infrastructure-present? true :green/exit 0)
                      (get-in result [:key :private_key_path]) (assoc :ssh-private-key-path (get-in result [:key :private_key_path])))
          (refuse opts ["compute state unavailable; legacy monolithic state requires explicit migration"])))))

(defn dns-data [opts]
  (assoc opts
         :metabase-host (utils/fqdn opts "metabase")
         :clickhouse-host (utils/fqdn opts "clickhouse")))
(defn dns-step [opts]
  (let [dir (tool-dir opts dns-tool)]
    (tofu-step opts dns-tool
               [(spec (template "tofu.dns" "main.tf") (str dir "/main.tf") (dns-data opts))]
               [:provider-dns])))

(defn all-servers [opts]
  (let [nodes (into {} (map (juxt :node_id identity) (compute/resolved opts)))]
    (into {} (map (fn [{:keys [id node-id] :as app}]
                   (let [node (get nodes node-id)]
                     (when-not node (throw (ex-info "compute inventory unavailable" {})))
                     [id (merge app node {:private-ip (:vpc_ip node)})])) utils/servers))))

(defn inventory [opts]
  (let [servers (all-servers opts)
        hosts (into {}
                    (map (fn [[id s]]
                           [(utils/host-alias opts id)
                            (cond-> {:ansible_host (:ip s) :ansible_user (:user s)
                             :private_ip (:private-ip s) :vpn_ip (:vpn-ip s)
                             :server_role (:role s) :server_ordinal (:ordinal s)}
                              (:ssh-private-key-path opts)
                              (assoc :ansible_ssh_private_key_file (:ssh-private-key-path opts)))]))
                    servers)]
    (json/generate-string
     {:all {:children {:managed {:hosts hosts}
                       :clickhouse {:hosts (select-keys hosts
                                             (map #(utils/host-alias opts (:id %))
                                                  (utils/clickhouse-servers)))}
                       :metabase {:hosts (select-keys hosts
                                           [(utils/host-alias opts :metabase)])}
                       :local {:hosts {:localhost {:ansible_connection "local"}}}}}}
     {:pretty true})))

(defn ansible-data [opts]
  (assoc opts
         :metabase-host (utils/fqdn opts "metabase")
         :clickhouse-host (utils/fqdn opts "clickhouse")
         :local-wg-address (first (str/split (str (:wireguard-client-address opts)) #"/"))))

(defn ansible-specs [opts]
  (let [dir (tool-dir opts ansible-tool) data (ansible-data opts)]
    [(spec (template "ansible" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible" "main.yml") (str dir "/main.yml") data)
     (spec (template "ansible" "wireguard.yml") (str dir "/wireguard.yml") data)
     (spec (template "ansible" "clickhouse.yml") (str dir "/clickhouse.yml") data)
     (spec (template "ansible" "metabase.yml") (str dir "/metabase.yml") data)
     (spec (template "ansible" "cleanup.yml") (str dir "/cleanup.yml") data)
     (spec (template "ansible" "clickhouse-config.xml") (str dir "/clickhouse-config.xml") data)
     (spec (template "ansible" "clickhouse-users.xml") (str dir "/clickhouse-users.xml") data)
     (spec (template "ansible" "docker-compose.yml") (str dir "/docker-compose.yml") data)
     (raw-spec (str dir "/inventory.json") (inventory opts))]))

(defn ansible-render-step [opts]
  (sc/scaffold opts (ansible-specs opts)))

(defn ansible-playbook-step [opts playbook recap-key]
  (if (= :build (:green/event opts))
    (assoc opts :green/exit 0)
    (ansible/ansible-step opts {:dir (tool-dir opts ansible-tool)
                                :inventory "inventory.json"
                                :playbooks {:create playbook}
                                :host-key-checking false
                                :recap-key recap-key})))

(defn wireguard-step [opts]
  (ansible-playbook-step opts "wireguard.yml" :clickhouse/wireguard-recap))
(defn clickhouse-config-step [opts]
  (ansible-playbook-step opts "clickhouse.yml" :clickhouse/clickhouse-recap))
(defn metabase-config-step [opts]
  (ansible-playbook-step opts "metabase.yml" :clickhouse/metabase-recap))

(defn ansible-cleanup-step [opts]
  (ansible/ansible-with-spec
   opts {:dir (tool-dir opts ansible-tool) :inventory "inventory.json"
         :playbooks {:delete "cleanup.yml"} :host-key-checking false
         :recap-key :clickhouse/cleanup-recap}
   (ansible-specs opts)))

(defn dbt-step [opts]
  (let [dir (tool-dir opts dbt-tool) data (ansible-data opts)
        specs [(spec (template "dbt" "pyproject.toml") (str dir "/pyproject.toml") data)
               (spec (template "dbt" "dbt_project.yml") (str dir "/dbt_project.yml") data)
               (spec (template "dbt" "profiles.yml") (str dir "/profiles.yml") data)
               (spec (template "dbt" "seeds/events.csv") (str dir "/seeds/events.csv") data)
               (spec (template "dbt" "models/events_summary.sql") (str dir "/models/events_summary.sql") data)
               (spec (template "dbt" "models/schema.yml") (str dir "/models/schema.yml") data)]
        rendered (sc/scaffold opts specs)]
    (if (or (= :build (:green/event opts)) (= :delete (:green/event opts))) rendered
        (let [env (merge (into {} (System/getenv))
                         {"DBT_PROFILES_DIR" dir
                          "COLORS_DBT_PASSWORD" (str (:clickhouse-dbt-password opts))})
              result (process/shell {:dir dir :env env :continue true}
                                    "uv" "run" "dbt" "seed")]
          (if (zero? (:exit result))
            (let [run (process/shell {:dir dir :env env :continue true}
                                     "uv" "run" "dbt" "run" "--fail-fast")]
              (if (zero? (:exit run))
                (let [test (process/shell {:dir dir :env env :continue true}
                                          "uv" "run" "dbt" "test")]
                  (if (zero? (:exit test))
                    rendered
                    (assoc rendered :green/exit (:exit test) :green/err (:err test))))
                (assoc rendered :green/exit (:exit run) :green/err (:err run))))
            (assoc rendered :green/exit (:exit result) :green/err (:err result)))))))

(defn acceptance-step [opts]
  (let [dir (tool-dir opts acceptance-tool)
        dbt-dir (tool-dir opts dbt-tool)
        data (ansible-data opts)
        script (str dir "/acceptance.py")
        inventory-file (str (io/file (tool-dir opts ansible-tool) "inventory.json"))
        specs [(spec (template "acceptance" "acceptance.py") script data)]
        rendered (sc/scaffold opts specs)]
    (if (or (= :build (:green/event opts)) (= :delete (:green/event opts)))
      rendered
      (let [env (merge (into {} (System/getenv))
                       {"COLORS_PAR_CLICKHOUSE_ADMIN_PASSWORD" (str (:clickhouse-admin-password opts))
                        "COLORS_PAR_CLICKHOUSE_METABASE_PASSWORD" (str (:clickhouse-metabase-password opts))
                        "COLORS_PAR_METABASE_ADMIN_EMAIL" (str (:metabase-admin-email opts))
                        "COLORS_PAR_METABASE_ADMIN_PASSWORD" (str (:metabase-admin-password opts))})
            result (process/shell {:dir dbt-dir :env env :continue true}
                                  "uv" "run" "python" script inventory-file)]
        (if (zero? (:exit result))
          rendered
          (assoc rendered :green/exit (:exit result) :green/err (:err result)))))))

(defn- dns-drift-step [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [env (merge (into {} (System/getenv))
                     (credential-env opts :provider-dns))
          results (doall
                   (pmap (fn [tool]
                           [tool (process/shell {:env env :continue true}
                                                "tofu" (str "-chdir=" (tool-dir opts tool))
                                                "plan" "-detailed-exitcode" "-input=false" "-no-color")])
                         tofu-tools))
          failed (first (filter (fn [[_ result]] (not (zero? (:exit result)))) results))]
      (if failed
        (let [[tool result] failed]
          (assoc opts :green/exit (:exit result)
                      :green/err (str "OpenTofu drift remains in " tool "\n"
                                      (:out result) (:err result))))
        (assoc opts :green/exit 0)))))

(defn drift-step [opts]
  (if (or (not= :create (:green/event opts)) (:green/dry-run opts))
    (assoc opts :green/exit 0)
    (if (= {:status "clean"} (compute-drift/check-deployment-drift opts compute/topology (compute/requirements opts)))
      (dns-drift-step opts)
      (assoc opts :green/exit 1 :green/err "compute drift check refused or detected changes"))))

(def ansible-local-tool "clickhouse-ansible-local")
(defn ssh-config-hosts [opts]
  (let [nodes (compute/resolved opts) entry (first (filter #(= "clickhouse-0" (:node_id %)) nodes))]
    (into [(assoc entry :name (:profile opts))] (map #(assoc % :name (str (:profile opts) "-" (:node_id %))) nodes))))
(defn ansible-local-specs [opts]
  (let [data (assoc opts :ssh-keygen (= "managed" (:mode (compute-ssh/mode opts)))) dir (tool-dir opts ansible-local-tool)]
    (mapv #(spec (template "ansible-local" %) (str dir "/" %) data) ["ansible.cfg" "inventory.ini" "main.yml"])))
(defn ansible-local-step [opts]
  (let [opts (if (and (= :create (:green/event opts)) (not (:green/dry-run opts))) (ssh-config/preflight! opts) opts)]
    (if (wf/failed? opts) opts
      (ansible/ansible-with-spec opts
        {:dir (tool-dir opts ansible-local-tool) :inventory "inventory.ini" :playbooks {:create "main.yml" :delete "main.yml"}
         :extra-vars {:host_alias (:profile opts) :ssh_hosts (ssh-config-hosts opts) :block_state (if (= :delete (:green/event opts)) "absent" "present")}} (ansible-local-specs opts)))))
