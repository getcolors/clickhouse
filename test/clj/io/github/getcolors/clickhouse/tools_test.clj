(ns io.github.getcolors.clickhouse.tools-test
  (:require [clojure.test :refer [deftest is]]
            [io.github.getcolors.clickhouse.tools :as tools]
            [io.github.getcolors.clickhouse.utils :as utils]))

(deftest topology-addresses-are-stable
  (is (= ["10.21.0.1" "10.21.0.2" "10.21.0.3" "10.21.0.10"]
         (mapv :vpn-ip utils/servers))))

(deftest each-server-reuses-once-hcloud
  (is (= :io.github.getcolors.once.tools.tofu.hcloud/main.tf
         (tools/once-template "hcloud"))))

(deftest inventory-has-four-remote-hosts
  (let [text (tools/inventory {:profile "p"})]
    (is (re-find #"p-node-1" text))
    (is (re-find #"p-metabase" text))
    (is (re-find #"10.20.1.13" text))))
