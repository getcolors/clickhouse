(ns io.github.getcolors.clickhouse.utils
  "Launcher contract and deterministic topology helpers.")

(def contract 1)

(def servers
  [{:id :node-1 :node-id "clickhouse-0" :role "clickhouse" :ordinal 1 :vpn-ip "10.21.0.1"}
   {:id :node-2 :node-id "clickhouse-1" :role "clickhouse" :ordinal 2 :vpn-ip "10.21.0.2"}
   {:id :node-3 :node-id "clickhouse-2" :role "clickhouse" :ordinal 3 :vpn-ip "10.21.0.3"}
   {:id :metabase :node-id "metabase-0" :role "metabase" :ordinal 10 :vpn-ip "10.21.0.10"}])

(defn server [id] (some #(when (= id (:id %)) %) servers))
(defn clickhouse-servers [] (filter #(= "clickhouse" (:role %)) servers))
(defn host-alias [opts id] (str (or (:profile opts) "clickhouse") "-" (name id)))
(defn fqdn [opts prefix] (str prefix "." (:domain opts)))
