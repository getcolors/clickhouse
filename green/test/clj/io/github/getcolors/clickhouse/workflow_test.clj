(ns io.github.getcolors.clickhouse.workflow-test
  (:require [clojure.test :refer [deftest is]] [clojure.java.io :as io]
            [green.workflow :as wf]
            [io.github.getcolors.clickhouse.workflow :as workflow]
            [io.github.getcolors.clickhouse.tools :as tools]
            [io.github.getcolors.compute-orchestration :as orchestration]
            [io.github.getcolors.compute-inspection :as inspection]
            [io.github.getcolors.clickhouse.validate-test :refer [base]]))
(defn nexts [step event] (vec (rest (workflow/wire-fn step {:green/event event}))))
(deftest application-configuration-remains-parallel-after-compute
  (is (= [:clickhouse/infrastructure] (nexts :clickhouse/start :create)))
  (is (= [:clickhouse/clickhouse-config :clickhouse/metabase-config] (nexts :clickhouse/wireguard :create)))
  (is (= [:clickhouse/dbt] (nexts :clickhouse/clickhouse-config :create)))
  (is (= [:clickhouse/dbt] (nexts :clickhouse/metabase-config :create))))
(deftest deletion-inspects-before-application-cleanup-and-compute-last
  (is (= [:clickhouse/load-infrastructure] (nexts :clickhouse/start :delete)))
  (is (= [:clickhouse/ansible-local] (nexts :clickhouse/ansible-cleanup :delete)))
  (is (= [:clickhouse/infrastructure] (nexts :clickhouse/dns :delete)))
  (is (= [] (nexts :clickhouse/infrastructure :delete))))
(deftest unreadable-state-refuses-cleanup
  (with-redefs [inspection/read-deployment (fn [& _] {:status "error"})]
    (is (= 1 (:green/exit (tools/load-infrastructure-step (assoc base :green/event :delete)))))))
(deftest native-build-is-offline-and-renders-four-library-node-states
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory "ch-build-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (with-redefs [orchestration/orchestrate (fn [& _] (throw (AssertionError. "build must not mutate compute")))
                    inspection/read-deployment (fn [& _] (throw (AssertionError. "build must not inspect")))]
        (let [result (wf/run workflow/workflow (assoc base :green/event :build :workdir (.getPath directory)))
              files (file-seq directory)]
          (is (= 0 (:green/exit result)) (:green/err result))
          (is (= 4 (count (filter #(= "node.tf.json" (.getName %)) files))))
          (is (some #(= "acceptance.py" (.getName %)) files))))
      (finally (doseq [file (reverse (file-seq directory))] (io/delete-file file))))))

(deftest managed-storage-order-and-retired-backend-retry
  (let [opts (assoc base :green/event :delete :clickhouse-storage-managed true :s3-bucket-mode "managed")]
    (is (= [:clickhouse/storage] (vec (rest (workflow/wire-fn :clickhouse/dns opts)))))
    (is (= [:clickhouse/infrastructure] (vec (rest (workflow/wire-fn :clickhouse/storage opts)))))
    (is (= [:clickhouse/backend-finalize] (vec (rest (workflow/wire-fn :clickhouse/infrastructure opts)))))
    (with-redefs [inspection/read-deployment (fn [& _] {:status "destroyed"})]
      (is (:clickhouse/finalize-only (tools/load-infrastructure-step opts))))))
