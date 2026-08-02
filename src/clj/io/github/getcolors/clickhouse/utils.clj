(ns io.github.getcolors.clickhouse.utils
  "Launcher contract and deterministic topology helpers.")

(def contract 1)

(def servers
  [{:id :node-1 :role "clickhouse" :ordinal 1 :vpn-ip "10.21.0.1" :private-ip "10.20.1.11"}
   {:id :node-2 :role "clickhouse" :ordinal 2 :vpn-ip "10.21.0.2" :private-ip "10.20.1.12"}
   {:id :node-3 :role "clickhouse" :ordinal 3 :vpn-ip "10.21.0.3" :private-ip "10.20.1.13"}
   {:id :metabase :role "metabase" :ordinal 10 :vpn-ip "10.21.0.10" :private-ip "10.20.1.20"}])

(defn server [id] (some #(when (= id (:id %)) %) servers))
(defn clickhouse-servers [] (filter #(= "clickhouse" (:role %)) servers))
(defn host-alias [opts id] (str (or (:profile opts) "clickhouse") "-" (name id)))
(defn fqdn [opts prefix] (str prefix "." (:domain opts)))
