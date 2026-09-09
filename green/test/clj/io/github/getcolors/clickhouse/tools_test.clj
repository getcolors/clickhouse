(ns io.github.getcolors.clickhouse.tools-test
  (:require [io.github.getcolors.compute-drift :as drift] [babashka.process :as process]
            [clojure.test :refer [deftest is]] [cheshire.core :as json]
            [io.github.getcolors.clickhouse.tools :as tools]
            [io.github.getcolors.clickhouse.compute :as compute]
            [io.github.getcolors.clickhouse.utils :as utils]
            [io.github.getcolors.compute-planning :as planning]
            [io.github.getcolors.clickhouse.validate-test :refer [base]]))
(def recorded {:nodes (mapv (fn [i app] {:node_id (:node-id app) :role (:role app) :index (if (= i 3) 0 i)
                                       :provider "hcloud" :name (str "server-" i) :ip (str "203.0.113." (+ 20 i))
                                       :vpc_ip (str "10.20.1." (+ 20 i)) :user "ubuntu" :sudoer "ubuntu"}) (range) utils/servers)})
(deftest vpn-addresses-and-application-identities-stay-stable
  (is (= ["10.21.0.1" "10.21.0.2" "10.21.0.3" "10.21.0.10"] (mapv :vpn-ip utils/servers)))
  (is (= [1 2 3 10] (mapv :ordinal utils/servers)))
  (let [servers (tools/all-servers (assoc base :colors-compute/cluster recorded))]
    (is (= "10.20.1.23" (:private-ip (:metabase servers))))
    (is (= "203.0.113.20" (:ip (:node-1 servers))))))
(deftest missing-inventory-is-never-a-real-fallback
  (is (thrown? Exception (tools/all-servers base)))
  (is (thrown? Exception (tools/all-servers (assoc base :colors-compute/cluster {:nodes (vec (butlast (:nodes recorded)))})))))
(deftest inventory-uses-observed-user-private-ip-and-key
  (let [inventory (json/parse-string (tools/inventory (assoc base :colors-compute/cluster recorded)) true)
        hosts (get-in inventory [:all :children :managed :hosts])]
    (is (= 4 (count hosts)))
    (is (= "ubuntu" (:ansible_user (:p-node-1 hosts))))
    (is (= "10.20.1.23" (:private_ip (:p-metabase hosts))))
    (is (= "/tmp/external" (:ansible_ssh_private_key_file (:p-node-1 hosts))))))
(deftest library-preserves-distinct-role-size-with-no-public-database-ingress
  (let [plan (planning/plan-deployment base compute/topology (compute/requirements base))
        nodes (get-in plan [:documents :nodes])
        size (fn [id] (get-in nodes [id "node.tf.json" "resource" "hcloud_server" "node" "server_type"]))]
    (is (= "cx33" (size "clickhouse-0")))
    (is (= "cx23" (size "metabase-0")))
    (is (= [22 51820 nil] (mapv :from_port (get-in (compute/requirements base) [:security :ingress]))))))

(deftest compute-drift-gates-dns-plan
  (let [calls (atom []) opts (assoc base :green/event :create)]
    (with-redefs [drift/check-deployment-drift (fn [& _] (swap! calls conj :compute) {:status "error"})
                  process/shell (fn [& _] (swap! calls conj :dns) {:exit 0})]
      (is (= 1 (:green/exit (tools/drift-step opts))))
      (is (= [:compute] @calls)))
    (reset! calls [])
    (with-redefs [drift/check-deployment-drift (fn [& _] (swap! calls conj :compute) {:status "clean"})
                  process/shell (fn [& _] (swap! calls conj :dns) {:exit 0})]
      (is (= 0 (:green/exit (tools/drift-step opts))))
      (is (= [:compute :dns] @calls)))))
