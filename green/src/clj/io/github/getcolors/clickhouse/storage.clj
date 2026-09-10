(ns io.github.getcolors.clickhouse.storage
  "Opt-in deployment-owned S3 Neon, events/media, and backup buckets and bucket-scoped credentials."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.cli :as cli]
            [green.process :as process]
            [green.scaffold :as scaffold]
            [green.tofu :as tofu]))

(def tool "clickhouse-storage")
(defn managed? [opts] (true? (:clickhouse-storage-managed opts)))
(defn directory [opts] (cli/stage-dir opts tool {:default-profile "clickhouse"}))
(defn aws-env [opts]
  (into {} (keep (fn [[key variable]] (when-let [value (not-empty (str (get opts key)))] [variable value])))
        {:aws-access-key-id "AWS_ACCESS_KEY_ID" :aws-secret-access-key "AWS_SECRET_ACCESS_KEY"
         :aws-session-token "AWS_SESSION_TOKEN"}))
(defn specs [opts]
  [{:template :io.github.getcolors.clickhouse.tools.storage/main.tf
    :target (str (directory opts) "/main.tf") :data opts :opts scaffold/preserve-jinja-delimiters}])
(defn- checked [args options]
  (let [result (process/run args options)]
    (when-not (zero? (:exit result))
      (throw (ex-info "managed storage state operation failed" {})))
    (:out result)))
(defn ownership-preflight!
  "Refuse existing buckets unless this stage already owns their Terraform address."
  [opts]
  (let [options {:dir (directory opts) :extra-env (aws-env opts)}]
    (checked ["tofu" "init" "-input=false" "-no-color"] options)
    (let [state (process/run ["tofu" "state" "list"] options)
          empty-state? (and (= 1 (:exit state)) (str/includes? (str (:err state)) "No state file was found!"))
          _ (when-not (or (zero? (:exit state)) empty-state?)
              (throw (ex-info "managed storage state unavailable" {})))
          addresses (set (str/split-lines (if empty-state? "" (:out state))))
          recorded (if (empty? addresses) {}
                       (into {} (map (juxt :address #(get-in % [:values :bucket])))
                             (get-in (json/parse-string (checked ["tofu" "show" "-json"] options) true) [:values :root_module :resources])))]
      (doseq [[role bucket] [["backup" (:clickhouse-backup-bucket opts)]]]
        (when-not (= bucket (get recorded (str "aws_s3_bucket.application[\"" role "\"]")))
          (let [result (process/run ["aws" "s3api" "head-bucket" "--bucket" bucket "--region" (:clickhouse-backup-region opts)] options)]
            ;; 403, network failures, and a successful probe all fail closed.
            (when-not (and (pos? (:exit result)) (re-find #"\(404\)|Not Found|NoSuchBucket" (str (:err result))))
              (throw (ex-info "managed storage refuses to adopt an existing or inaccessible bucket" {})))))))))
(defn step [opts]
  (if-not (managed? opts) (assoc opts :green/exit 0)
    (try
      (let [documents (specs opts)
            event (:green/event opts)]
        (when (= :create event)
          (scaffold/scaffold opts documents)
          (ownership-preflight! opts))
        (let [result (tofu/tofu-with-spec opts documents
                       {:dir (directory opts) :env (aws-env opts) :output-key :clickhouse/storage-credentials})]
          ;; Scoped credentials remain in memory and encrypted backend state.
          ;; Never copy them into template values or print the output object.
          result))
      (catch Exception _ (assoc opts :green/exit 1 :green/err "managed S3 storage failed; inspect bucket ownership, state access, and AWS permissions")))))
(defn credential-env [opts]
  ;; green.tofu keywords output names only; JSON object values retain string keys.
  (reduce (fn [env [role prefix]]
            (let [{:keys [access_key_id secret_access_key]} (get (walk/keywordize-keys (get-in opts [:clickhouse/storage-credentials :credentials])) role)]
              (when (or (str/blank? access_key_id) (str/blank? secret_access_key))
                (throw (ex-info "managed storage credentials unavailable" {})))
              (assoc env (str "COLORS_PAR_" prefix "_ACCESS_KEY_ID") access_key_id
                         (str "COLORS_PAR_" prefix "_SECRET_ACCESS_KEY") secret_access_key)))
          {"ANSIBLE_HOST_KEY_CHECKING" "False"}
          [[:backup "CLICKHOUSE_BACKUP"]]))

(defn read-credentials! [opts]
  (if-not (managed? opts) opts
    (try
      (do
        ((tofu/conventional-backend-advice {:dir-fn directory :key-fn #(str (:profile %) "/" tool ".tfstate")}) opts)
        (scaffold/scaffold (assoc opts :green/event :build) (specs opts))
        (checked ["tofu" "init" "-input=false" "-no-color"] {:dir (directory opts) :extra-env (aws-env opts)})
        (let [result (assoc opts :clickhouse/storage-credentials (tofu/outputs (directory opts) (aws-env opts)))]
          (credential-env result)
          result))
      (catch Exception _ (throw (ex-info "managed storage credentials unavailable; converge storage before rehearsal" {}))))))
