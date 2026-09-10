(ns io.github.getcolors.clickhouse.compute
  (:require [io.github.getcolors.compute :as library]
            [io.github.getcolors.compute-planning :as planning]))
(def topology [{:role "clickhouse" :count 3} {:role "metabase" :count 1}])
(def legacy-tools ["clickhouse-network" "clickhouse-access" "clickhouse-node-1" "clickhouse-node-2" "clickhouse-node-3" "clickhouse-metabase" "clickhouse-firewall"])
(defn requirements [opts]
  (let [base {:security {:ingress [{:id "ssh" :protocol "tcp" :from_port 22 :to_port 22 :sources ["0.0.0.0/0"]}
                        {:id "wireguard" :protocol "udp" :from_port (:wireguard-port opts) :to_port (:wireguard-port opts) :sources ["0.0.0.0/0"]}
                        {:id "ping" :protocol "icmp" :from_port nil :to_port nil :sources ["0.0.0.0/0"]}]
              :egress "all" :private_filter false}
   :private true :legacy_state_keys (mapv #(str (:profile opts) "/" % ".tfstate") legacy-tools)}
        policy (fn [ports] (assoc (:security base) :private_filter true :ingress
                         (into (get-in base [:security :ingress])
                           (map (fn [[port roles]] {:id (str "peer-" port) :protocol "tcp" :from_port port :to_port port :peer_roles roles}) ports))))]
    (if (= "aws" (:provider-compute opts))
      (assoc base :security (policy []) :roles
        {:clickhouse {:security (policy [[(:clickhouse-http-port opts) ["metabase"]] [(:clickhouse-native-port opts) ["clickhouse" "metabase"]] [9009 ["clickhouse"]] [9181 ["clickhouse"]] [9234 ["clickhouse"]]])}
         :metabase {:security (policy [])}})
      base)))
(defn resolved [opts]
  (if-let [cluster (:colors-compute/cluster opts)]
    (:nodes (library/collect (mapv #(assoc % :private true :provider (:provider-compute opts)) (library/expand topology)) (:nodes cluster) "clickhouse-0"))
    (if (or (= :build (:green/event opts)) (:green/dry-run opts))
      (get-in (planning/plan-deployment opts topology (requirements opts)) [:cluster :nodes])
      (throw (ex-info "compute inventory unavailable" {})))))
