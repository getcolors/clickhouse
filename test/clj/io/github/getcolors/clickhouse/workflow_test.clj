(ns io.github.getcolors.clickhouse.workflow-test
  (:require [clojure.test :refer [deftest is]]
            [io.github.getcolors.clickhouse.workflow :as workflow]))

(deftest create-order
  (is (= :clickhouse/network
         (second (workflow/wire-fn :clickhouse/start {:green/event :create}))))
  (is (= :clickhouse/firewall
         (second (workflow/wire-fn :clickhouse/metabase {:green/event :create}))))
  (is (= :clickhouse/dbt
         (second (workflow/wire-fn :clickhouse/ansible {:green/event :create})))))

(deftest delete-cleans-local-first
  (is (= :clickhouse/dbt
         (second (workflow/wire-fn :clickhouse/start {:green/event :delete}))))
  (is (= :clickhouse/ansible
         (second (workflow/wire-fn :clickhouse/dbt {:green/event :delete})))))
