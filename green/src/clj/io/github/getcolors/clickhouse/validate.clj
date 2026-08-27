(ns io.github.getcolors.clickhouse.validate
  "Desired-state and credential validation over ONCE's provider registry."
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.once.validate :as once-validate]))

(def providers once-validate/providers)
(def slots [:provider-compute :provider-dns :provider-backend])
(def own-required
  [:profile :workdir :domain :clickhouse-cluster-name :clickhouse-version
   :clickhouse-shards :clickhouse-replicas :clickhouse-keeper-nodes
   :clickhouse-http-port :clickhouse-native-port
   :clickhouse-metabase-user :clickhouse-dbt-user
   :metabase-image :metabase-postgres-image :metabase-port
   :dbt-core-version :dbt-clickhouse-version :dbt-project-dir
   :metabase-hcloud-server-type
   :hcloud-network-zone :hcloud-network-cidr :hcloud-subnet-cidr
   :wireguard-port :wireguard-network-cidr :wireguard-client-address])
(def own-secrets
  [:clickhouse-admin-password :clickhouse-metabase-password
   :clickhouse-dbt-password :clickhouse-interserver-secret
   :metabase-admin-email :metabase-admin-password :metabase-db-password
   :metabase-encryption-secret-key])

(defn placeholder? [x]
  (or (nil? x) (and (string? x) (or (str/blank? x)
                                     (= "REPLACE_ME" (str/upper-case x))))))
(defn- entry [opts slot] (get-in providers [slot (get opts slot)]))
(defn tofu-env [opts slot] (:tofu-env (entry opts slot) {}))
(defn- slot-keys [opts field] (mapcat #(get (entry opts %) field []) slots))
(defn- missing [opts ks] (keep #(when (placeholder? (get opts %)) %) ks))
(def profile-par (green-cli/par-name :profile))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set. ClickHouse takes profile from colors.yml only.")]))

(def domain-re #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def version-re #"^[0-9]+(?:\.[0-9]+){3}$")
(defn positive-int? [x] (and (integer? x) (pos? x)))

(defn state-errors [opts]
  (vec
   (concat
    (map #(str % " is required")
         (missing opts (concat own-required (slot-keys opts :required))))
    (for [slot slots :let [p (get opts slot)]
          :when (not (contains? (get providers slot) p))]
      (str "unsupported " slot " " (pr-str p)))
    (when-not (= "hcloud" (:provider-compute opts))
      [":provider-compute must be hcloud"])
    (when-not (= "cloudflare" (:provider-dns opts))
      [":provider-dns must be cloudflare"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (when-not (or (placeholder? (:domain opts))
                  (re-matches domain-re (str (:domain opts))))
      [":domain must be a fully qualified Cloudflare zone"])
    (when-not (or (placeholder? (:clickhouse-version opts))
                  (re-matches version-re (str (:clickhouse-version opts))))
      [":clickhouse-version must be an exact four-part package version"])
    (for [k [:clickhouse-shards :clickhouse-replicas :clickhouse-keeper-nodes
             :clickhouse-http-port :clickhouse-native-port :metabase-port
             :wireguard-port]
          :when (not (positive-int? (get opts k)))]
      (str k " must be a positive integer"))
    (when-not (and (= 1 (:clickhouse-shards opts))
                   (= 3 (:clickhouse-replicas opts))
                   (= 3 (:clickhouse-keeper-nodes opts)))
      ["v1 requires one shard, three replicas, and three Keeper nodes"]))))

(defn secret-errors [opts]
  (concat
   (map #(str "required credential is not set: " (green-cli/par-name %))
        (distinct (missing opts (concat own-secrets (slot-keys opts :secrets)))))
   (when (and (not (placeholder? (:metabase-encryption-secret-key opts)))
              (< (count (str (:metabase-encryption-secret-key opts))) 16))
     ["COLORS_PAR_METABASE_ENCRYPTION_SECRET_KEY must contain at least 16 characters"])))
