(ns io.github.getcolors.clickhouse.workflow-test
  (:require [clojure.test :refer [deftest is]]
            [io.github.getcolors.clickhouse.workflow :as workflow]))

(def create {:green/event :create})
(def delete {:green/event :delete})

(deftest create-fans-out-and-joins
  (is (= [:clickhouse/access]
         (vec (rest (workflow/wire-fn :clickhouse/network create)))))
  (is (= [:clickhouse/node-1 :clickhouse/node-2
          :clickhouse/node-3 :clickhouse/metabase]
         (vec (rest (workflow/wire-fn :clickhouse/access create)))))
  (doseq [step [:clickhouse/node-1 :clickhouse/node-2
                :clickhouse/node-3 :clickhouse/metabase]]
    (is (= [:clickhouse/firewall]
           (vec (rest (workflow/wire-fn step create))))))
  (is (= [:clickhouse/clickhouse-config :clickhouse/metabase-config]
         (vec (rest (workflow/wire-fn :clickhouse/wireguard create)))))
  (is (= [:clickhouse/dbt]
         (vec (rest (workflow/wire-fn :clickhouse/clickhouse-config create)))))
  (is (= [:clickhouse/dbt]
         (vec (rest (workflow/wire-fn :clickhouse/metabase-config create)))))
  (is (= :clickhouse/drift
         (second (workflow/wire-fn :clickhouse/acceptance create)))))

(deftest delete-cleans-and-destroys-in-parallel
  (is (= :clickhouse/dbt
         (second (workflow/wire-fn :clickhouse/start delete))))
  (is (= :clickhouse/ansible-cleanup
         (second (workflow/wire-fn :clickhouse/acceptance delete))))
  (is (= [:clickhouse/dns :clickhouse/firewall]
         (vec (rest (workflow/wire-fn :clickhouse/ansible-cleanup delete)))))
  (is (= [:clickhouse/node-1 :clickhouse/node-2
          :clickhouse/node-3 :clickhouse/metabase]
         (vec (rest (workflow/wire-fn :clickhouse/infrastructure-clean delete)))))
  (doseq [step [:clickhouse/node-1 :clickhouse/node-2
                :clickhouse/node-3 :clickhouse/metabase]]
    (is (= [:clickhouse/access]
           (vec (rest (workflow/wire-fn step delete))))))
  (is (= [:clickhouse/network]
         (vec (rest (workflow/wire-fn :clickhouse/access delete))))))
