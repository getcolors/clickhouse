(ns io.github.getcolors.clickhouse.validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [green.cli :as cli]
            [io.github.getcolors.clickhouse.validate :as validate]))

(def base
  {:profile "p" :workdir ".colors" :provider-compute "hcloud"
   :provider-dns "cloudflare" :provider-backend "local"
   :compute-prevent-destroy true :domain "example.com"
   :clickhouse-cluster-name "p" :clickhouse-version "26.3.17.56"
   :clickhouse-shards 1 :clickhouse-replicas 3 :clickhouse-keeper-nodes 3
   :clickhouse-http-port 8123 :clickhouse-native-port 9000
   :clickhouse-metabase-user "metabase" :clickhouse-dbt-user "dbt"
   :metabase-image "metabase/metabase:v0.63.2"
   :metabase-postgres-image "postgres:16.14" :metabase-port 3000
   :dbt-core-version "1.11.12" :dbt-clickhouse-version "1.10.1"
   :dbt-project-dir "dbt" :metabase-hcloud-server-type "cx23"
   :hcloud-name "p" :hcloud-image "ubuntu-24.04" :hcloud-server-type "cx33"
   :hcloud-location "nbg1" :hcloud-ssh-keys "key"
   :hcloud-network-zone "eu-central" :hcloud-network-cidr "10.20.0.0/16"
   :hcloud-subnet-cidr "10.20.1.0/24" :wireguard-port 51820
   :wireguard-network-cidr "10.21.0.0/24" :wireguard-client-address "10.21.0.254/32"})

(deftest valid-state (is (= [] (validate/state-errors base))))
(deftest topology-is-fixed
  (is (some #(re-find #"v1 requires" %) (validate/state-errors (assoc base :clickhouse-replicas 2)))))
(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {(cli/par-name :profile) "other"}))))
(deftest all-secrets-are-required
  (is (some #(re-find #"CLICKHOUSE_ADMIN_PASSWORD" %) (validate/secret-errors base))))

(deftest metabase-encryption-key-has-a-minimum-length
  (let [opts (merge base (zipmap validate/own-secrets (repeat "long-enough-secret")))]
    (is (some #(re-find #"at least 16" %)
              (validate/secret-errors (assoc opts :metabase-encryption-secret-key "short"))))))
