(ns io.github.getcolors.clickhouse.validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [green.cli :as cli]
            [io.github.getcolors.clickhouse.validate :as validate]))

(def base
  {:profile "p" :workdir ".colors" :provider-compute "hcloud"
   :provider-dns "cloudflare" :provider-backend "s3" :s3-bucket "test-state" :s3-region "us-east-1"
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
   :hcloud-location "nbg1" :hcloud-ssh-keys "key" :ssh-private-key-path "/tmp/external"
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

(deftest external-key-allows-agent-and-managed-mode-needs-no-path
  (is (= [] (validate/state-errors (dissoc base :ssh-private-key-path))))
  (is (= [] (validate/state-errors (dissoc base :ssh-private-key-path :hcloud-ssh-keys)))))

(deftest backup-never-shares-remote-state-or-unsafe-prefix
  (let [opts (assoc base :clickhouse-storage-managed true :clickhouse-backup-region "us-east-1" :clickhouse-backup-bucket "test-state")]
    (is (some #{":clickhouse-backup-bucket must not be the OpenTofu state bucket"} (validate/state-errors opts)))
    (is (some #{":clickhouse-backup-prefix must contain safe nonempty path segments"} (validate/state-errors (assoc opts :clickhouse-backup-bucket "separate-backup" :clickhouse-backup-prefix "../bad"))))
    (is (= [] (validate/state-errors (assoc opts :clickhouse-backup-bucket "separate-backup"))))))
