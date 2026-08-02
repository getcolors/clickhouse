(ns pin
  "Stamp the bundled launcher with the commit a standalone copy should resolve.

  This is a maintainer command for this repository, not something a user of the
  skill ever runs: it reads the HEAD of the checkout it is invoked in, so
  anywhere else it would stamp an unrelated SHA. That is why it lives in bb.edn
  rather than as a launcher subcommand — a payload copied into a stranger's
  project should not carry a command that is wrong by construction there.

  ClickHouse's launcher pins only clickhouse. `once` and `green` come transitively from
  clickhouse's deps.edn, so there is one site here rather than ONCE's three."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]))

(def ^:private site
  "Where a copied payload records which clickhouse commit to resolve.

  The pattern accepts `nil` as well as a SHA: an unpushed repository cannot be
  pinned, so the launcher ships carrying nothing rather than an invented commit,
  and this is what replaces it the first time."
  {:path "skills/package-clickhouse-green/green"
   :rx #"\(def \^:private clickhouse-sha (nil|\"[0-9a-f]{40}\")\)"})

(defn- git-out
  [dir & args]
  (let [{:keys [exit out]} (apply sh/sh "git" "-C" (str dir) args)]
    (when (zero? exit) (str/trim out))))

(defn- repo-head
  "HEAD of the repository containing `dir`, once it is clean and pushed.

  Pinning a dirty or unpushed commit produces a launcher that resolves to
  something nobody else can fetch, which fails at the worst possible moment —
  in someone else's project, on first run."
  [dir label]
  (if-let [top (git-out dir "rev-parse" "--show-toplevel")]
    (let [dirty (git-out top "status" "--porcelain")
          sha (git-out top "rev-parse" "HEAD")
          remotes (git-out top "branch" "-r" "--contains" (str sha))]
      (cond
        (seq dirty)
        [nil (str label " working tree is dirty; commit before pinning")]

        (not (str/includes? (str remotes) "origin/"))
        [nil (str label " HEAD " (subs sha 0 7) " is not on any remote branch; "
                  "push before pinning")]

        :else [sha nil]))
    [nil (str label " is not a git repository: " dir)]))

(defn- current-pin
  [text]
  (second (re-find (:rx site) text)))

(defn- replace-pin
  [text sha]
  (let [m (re-matcher (:rx site) text)]
    (when (.find m)
      (str (subs text 0 (.start m 1)) (pr-str sha) (subs text (.end m 1))))))

(defn pin
  "Returns green's Unix-style outcome map, so the task can be tested."
  []
  (let [file (io/file (:path site))]
    (cond
      (not (.exists file))
      {:green/exit 2 :green/err (str "pin site is missing: " (:path site))}

      :else
      (let [text (slurp file)
            current (current-pin text)]
        (if-not current
          {:green/exit 2
           :green/err (str "could not locate clickhouse-sha in " (:path site))}
          (let [[head err] (repo-head "." "clickhouse")]
            (cond
              err {:green/exit 2 :green/err err}

              (= (pr-str head) current)
              {:green/exit 0 :green/err (str "already pinned to " (subs head 0 7))}

              :else
              (do (spit file (replace-pin text head))
                  {:green/exit 0
                   :green/err (str "pinned " (:path site) " to " (subs head 0 7)
                                   " (was " current ")")}))))))))

(defn -main
  [& _]
  (let [{:green/keys [exit err]} (pin)]
    (when err
      (binding [*out* (if (zero? exit) *out* *err*)]
        (println err)))
    (System/exit exit)))

(-main)
