(ns io.github.getcolors.clickhouse.tools
  "OpenTofu and Ansible stages for the fixed v1 topology."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.clickhouse.utils :as utils]
            [io.github.getcolors.clickhouse.validate :as validate]))

(def network-tool "clickhouse-network")
(def firewall-tool "clickhouse-firewall")
(def dns-tool "clickhouse-dns")
(def ansible-tool "clickhouse-ansible")
(def dbt-tool "clickhouse-dbt")
(def server-tools {:node-1 "clickhouse-node-1" :node-2 "clickhouse-node-2"
                   :node-3 "clickhouse-node-3" :metabase "clickhouse-metabase"})
(def tofu-tools (concat [network-tool] (vals server-tools) [firewall-tool dns-tool]))

(def root "io.github.getcolors.clickhouse.tools")
(def once-root "io.github.getcolors.once.tools")
(def raw-template :io.github.getcolors.clickhouse/raw)
(def template-opts {:tag-open \< :tag-close \> :filter-open \{ :filter-close \}})
(defn tool-dir [opts tool]
  (let [workdir (io/file (or (:workdir opts) ".colors"))
        state-dir (when-not (.isAbsolute workdir)
                    (some-> (:green/state-file opts) io/file .getAbsoluteFile .getParent))
        root-dir (if state-dir (io/file state-dir workdir) workdir)]
    (str (io/file root-dir (or (:profile opts) "clickhouse") tool))))
(defn template [path file] (keyword (str root "." path) file))
(defn once-template [provider] (keyword (str once-root ".tofu." provider) "main.tf"))
(defn spec [template target data] {:template template :target target :data data :opts template-opts})
(defn raw-spec [target content] (spec raw-template target {:content content}))

(defn credential-env [opts & slots]
  (not-empty
   (into {} (keep (fn [[k env-var]]
                    (when-let [v (not-empty (str (get opts k)))] [env-var v])))
         (apply merge (map #(validate/tofu-env opts %)
                           (conj (vec slots) :provider-backend))))))

(defn tofu-step [opts tool specs slots]
  (tofu/tofu-with-spec opts specs {:dir (tool-dir opts tool)
                                   :env (apply credential-env opts slots)}))

(defn network-step [opts]
  (let [dir (tool-dir opts network-tool)]
    (tofu-step opts network-tool
               [(spec (template "tofu.network" "main.tf") (str dir "/main.tf") opts)]
               [:provider-compute])))

(defn server-data [opts id]
  (let [{:keys [role ordinal vpn-ip private-ip]} (utils/server id)
        base (:hcloud-name opts)]
    (assoc opts
           :server-id (name id) :server-role role :server-ordinal ordinal
           :vpn-ip vpn-ip :private-ip private-ip
           :network-name (str base "-network")
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

(defn firewall-step [opts]
  (let [dir (tool-dir opts firewall-tool)]
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
                             :server_role (:role s) :server_ordinal (:ordinal s)}]))
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

(defn ansible-step [opts]
  (let [dir (tool-dir opts ansible-tool) data (ansible-data opts)
        specs [(spec (template "ansible" "ansible.cfg") (str dir "/ansible.cfg") data)
               (spec (template "ansible" "main.yml") (str dir "/main.yml") data)
               (spec (template "ansible" "cleanup.yml") (str dir "/cleanup.yml") data)
               (spec (template "ansible" "clickhouse-config.xml") (str dir "/clickhouse-config.xml") data)
               (spec (template "ansible" "clickhouse-users.xml") (str dir "/clickhouse-users.xml") data)
               (spec (template "ansible" "docker-compose.yml") (str dir "/docker-compose.yml") data)
               (raw-spec (str dir "/inventory.json") (inventory opts))]]
    (ansible/ansible-with-spec
     opts {:dir dir :inventory "inventory.json"
           :playbooks {:create "main.yml" :delete "cleanup.yml"}
           :host-key-checking false}
     specs)))

(defn dbt-step [opts]
  (let [dir (tool-dir opts dbt-tool) data (ansible-data opts)
        specs [(spec (template "dbt" "pyproject.toml") (str dir "/pyproject.toml") data)
               (spec (template "dbt" "dbt_project.yml") (str dir "/dbt_project.yml") data)
               (spec (template "dbt" "profiles.yml") (str dir "/profiles.yml") data)
               (spec (template "dbt" "register-metabase.py") (str dir "/register-metabase.py") data)
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
                    (let [register (process/shell {:dir dir :env env :continue true}
                                                  "uv" "run" "python" "register-metabase.py")]
                      (if (zero? (:exit register)) rendered
                          (assoc rendered :green/exit (:exit register) :green/err (:err register))))
                    (assoc rendered :green/exit (:exit test) :green/err (:err test))))
                (assoc rendered :green/exit (:exit run) :green/err (:err run))))
            (assoc rendered :green/exit (:exit result) :green/err (:err result)))))))
