(ns io.github.getcolors.clickhouse.tools
  "OpenTofu and Ansible stages for the fixed v1 topology."
  (:require [babashka.process :as process]
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
            [io.github.getcolors.clickhouse.validate :as validate]))

(def network-tool "clickhouse-network")
(def access-tool "clickhouse-access")
(def firewall-tool "clickhouse-firewall")
(def dns-tool "clickhouse-dns")
(def ansible-tool "clickhouse-ansible")
(def dbt-tool "clickhouse-dbt")
(def acceptance-tool "clickhouse-acceptance")
(def server-tools {:node-1 "clickhouse-node-1" :node-2 "clickhouse-node-2"
                   :node-3 "clickhouse-node-3" :metabase "clickhouse-metabase"})
(def tofu-tools (concat [network-tool access-tool] (vals server-tools) [firewall-tool dns-tool]))

(def root "io.github.getcolors.clickhouse.tools")
(def once-root "io.github.getcolors.once.tools")
(def template-opts sc/preserve-jinja-delimiters)
(defn tool-dir [opts tool]
  (green-cli/stage-dir opts tool {:default-profile "clickhouse"}))
(defn template [path file] (keyword (str root "." path) file))
(defn once-template [provider] (keyword (str once-root ".tofu." provider) "main.tf"))
(defn spec [template target data] {:template template :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))

(defn credential-env [opts & slots]
  (provider-ops/tool-env validate/providers opts
                         (conj (vec slots) :provider-backend)))

(defn tofu-step [opts tool specs slots]
  (tofu/tofu-with-spec opts specs {:dir (tool-dir opts tool)
                                   :env (merge (apply credential-env opts slots)
                                               (:clickhouse/process-env opts))}))

(defn network-step [opts]
  (let [dir (tool-dir opts network-tool)]
    (tofu-step opts network-tool
               [(spec (template "tofu.network" "main.tf") (str dir "/main.tf") opts)]
               [:provider-compute])))

(def placeholder-ssh-public-key
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 colors-build-placeholder")

(defn managed-ssh-data [opts]
  (let [dir (tool-dir opts access-tool)
        private-file (.getAbsolutePath (io/file dir ".private" "id_ed25519"))
        public-file (str private-file ".pub")]
    (assoc opts
           :managed-ssh-key-name (str (:hcloud-name opts) "-managed")
           :managed-ssh-private-key private-file
           :managed-ssh-inventory-key "../clickhouse-access/.private/id_ed25519"
           :managed-ssh-public-key (if (.exists (io/file public-file))
                                     (str/trim (slurp public-file))
                                     placeholder-ssh-public-key))))

(defn process-env [extra] (merge (into {} (System/getenv)) extra))

(defn ensure-ssh-agent [opts]
  (let [private-file (:managed-ssh-private-key opts)
        socket (str "/tmp/colors-" (:profile opts) "-ssh-agent.sock")
        env {"SSH_AUTH_SOCK" socket}
        listed #(process/shell {:env (process-env env) :continue true} "ssh-add" "-l")]
    (when-not (zero? (:exit (listed)))
      (io/delete-file socket true)
      (let [started (process/shell {:continue true} "ssh-agent" "-a" socket)]
        (when-not (zero? (:exit started))
          (throw (ex-info "failed to start managed SSH agent" {:green/exit (:exit started)})))))
    (let [added (process/shell {:env (process-env env) :continue true}
                               "ssh-add" private-file)]
      (if (zero? (:exit added))
        (assoc opts :clickhouse/process-env env)
        (assoc opts :green/exit (:exit added) :green/err "failed to load managed SSH key")))))

(defn access-step [opts]
  (let [data (managed-ssh-data opts)
        private-file (:managed-ssh-private-key data)
        key-result (when (and (= :create (:green/event opts))
                              (not (.exists (io/file private-file))))
                     (io/make-parents private-file)
                     (process/shell {:continue true}
                                    "ssh-keygen" "-q" "-t" "ed25519" "-N" ""
                                    "-C" (str (:profile opts) " managed by Colors")
                                    "-f" private-file))
        data (managed-ssh-data opts)
        data (if (and (= :create (:green/event opts))
                      (or (nil? key-result) (zero? (:exit key-result))))
               (ensure-ssh-agent data)
               data)]
    (cond
      (and key-result (not (zero? (:exit key-result))))
      (assoc opts :green/exit (:exit key-result) :green/err (:err key-result))

      (wf/failed? data) data

      :else
      (let [dir (tool-dir opts access-tool)]
        (tofu-step data access-tool
                   [(spec (template "tofu.access" "main.tf") (str dir "/main.tf") data)]
                   [:provider-compute])))))

(defn server-data [opts id]
  (let [{:keys [role ordinal vpn-ip private-ip]} (utils/server id)
        base (:hcloud-name opts)]
    (assoc opts
           :server-id (name id) :server-role role :server-ordinal ordinal
           :vpn-ip vpn-ip :private-ip private-ip
           :network-name (str base "-network")
           :hcloud-ssh-keys (str base "-managed")
           :hcloud-name (str base "-" (name id))
           :hcloud-server-type (if (= id :metabase)
                                 (:metabase-hcloud-server-type opts)
                                 (:hcloud-server-type opts)))))

(defn server-fallback [opts id]
  (merge (utils/server id)
         {:ip (str "192.0.2." (+ 10 (:ordinal (utils/server id))))
          :user "root" :sudoer "root"
          :name (str (:profile opts) "-" (name id))}))

(defn server-step [opts id]
  (let [tool (server-tools id) dir (tool-dir opts tool) data (server-data opts id)
        result (tofu-step opts tool
                          [(spec (once-template "hcloud") (str dir "/main.tf") data)
                           (spec (template "tofu.server" "attach.tf")
                                 (str dir "/attach.tf") data)]
                          [:provider-compute])
        output (some-> (:tofu/outputs result) walk/keywordize-keys)
        params (merge (server-fallback opts id) (:params output)
                      (select-keys output [:private-ip]))]
    (if (wf/failed? result) result
        (assoc-in result [:clickhouse/servers id] params))))

(defn node-1-step [opts] (server-step opts :node-1))
(defn node-2-step [opts] (server-step opts :node-2))
(defn node-3-step [opts] (server-step opts :node-3))
(defn metabase-step [opts] (server-step opts :metabase))

(declare join-server-branches)

(defn firewall-step [opts]
  (let [opts (join-server-branches opts)
        dir (tool-dir opts firewall-tool)]
    (tofu-step opts firewall-tool
               [(spec (template "tofu.firewall" "main.tf") (str dir "/main.tf") opts)]
               [:provider-compute])))

(defn dns-data [opts]
  (assoc opts
         :metabase-host (utils/fqdn opts "metabase")
         :clickhouse-host (utils/fqdn opts "clickhouse")))
(defn dns-step [opts]
  (let [dir (tool-dir opts dns-tool)]
    (tofu-step opts dns-tool
               [(spec (template "tofu.dns" "main.tf") (str dir "/main.tf") (dns-data opts))]
               [:provider-dns])))

(defn join-server-branches
  "Merge independently provisioned server outputs at Green's fan-in boundary."
  [opts]
  (let [servers (apply merge (keep :clickhouse/servers (:green/branches opts)))]
    (cond-> opts
      (seq servers) (update :clickhouse/servers #(merge (or % {}) servers)))))

(defn all-servers [opts]
  (reduce (fn [m {:keys [id] :as server}]
            (assoc m id (merge (server-fallback opts id)
                               server
                               (get-in opts [:clickhouse/servers id]))))
          {} utils/servers))

(defn inventory [opts]
  (let [servers (all-servers opts)
        hosts (into {}
                    (map (fn [[id s]]
                           [(utils/host-alias opts id)
                            {:ansible_host (:ip s) :ansible_user "root"
                             :private_ip (:private-ip s) :vpn_ip (:vpn-ip s)
                             :server_role (:role s) :server_ordinal (:ordinal s)
                             :ansible_ssh_private_key_file
                             (:managed-ssh-inventory-key (managed-ssh-data opts))}]))
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

(defn drift-step [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [env (merge (into {} (System/getenv))
                     (credential-env opts :provider-compute :provider-dns))
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
