(ns io.github.getcolors.clickhouse.ssh-config
  "The deployment's `~/.ssh/config` block, per the workspace SSH Config Standard.

  The block itself is written by the `ansible-local` stage, because that is the
  one place the address is known and because `blockinfile` already handles the
  idempotent replace. What lives here is everything that must happen before the
  stage renders: the alias, the identity file, and the refusal to adopt a
  stanza this package did not write.

  Unlike the keypair, this play is the package's own copy rather than ONCE's
  (standard §7). The file is shared with every other host the operator reaches,
  so an unrelated change upstream must not be able to rewrite it at pin-bump
  time. The alias list, though, is the Compute Cluster Standard's (§6) and
  comes from the shared compute topology."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [io.github.getcolors.clickhouse.validate :as validate]
            [io.github.getcolors.compute :as library]
            [io.github.getcolors.clickhouse.compute :as compute]))

(defn host-alias
  "The profile, unchanged. Standard §2: the profile already keys remote state,
  which is what makes it unique enough to name a host by."
  [opts]
  (or (:profile opts) "clickhouse"))

(defn identity-file
  "`~/.ssh/<profile>`, written with a literal tilde rather than an expanded
  home directory. OpenSSH expands it, and leaving it unexpanded is what keeps
  the rendered block identical on every workstation."
  [opts]
  (str "~/.ssh/" (host-alias opts)))

(defn aliases
  "Every alias this deployment owns: the bare profile, which reaches node 0 and
  is what the standard promises an operator can type, plus `<profile>-<i>` per
  node, matching the machine label. The compute library derives the node IDs
  (Compute Cluster Standard §6).

  A single-node package needs only the first. Here the per-node aliases are
  what make the cluster operable at all — half of running a three-node quorum
  is reaching one specific member — and the bare profile keeps `ssh <profile>`
  meaning what it means in every other deployment."
  [opts]
  (into [(:profile opts)] (map #(str (:profile opts) "-" (:node_id %)) (library/expand compute/topology))))

(defn config-path []
  (io/file (or (not-empty (System/getenv "HOME")) (System/getProperty "user.home")) ".ssh" "config"))

;; The alias alone. A profile is `<package>-<suffix>`, so it already names the
;; package, and two packages sharing one profile would be fighting over
;; `~/.ssh/<profile>` long before they reached this file.
(defn begin-marker [alias] (str "# BEGIN " alias " ANSIBLE MANAGED BLOCK"))
(defn end-marker [alias] (str "# END " alias " ANSIBLE MANAGED BLOCK"))

(defn owned-markers
  "Every begin/end pair this package recognises as its own.

  A set rather than a pair because a marker change is a migration: while one is
  in flight this holds the superseded marker too, so the ownership check below
  does not read the package's own block as a hand-written stanza and refuse the
  migration meant to clean it up. Nothing is in flight now."
  [alias]
  {:begin #{(begin-marker alias)}
   :end #{(end-marker alias)}})

(defn host-patterns
  "The patterns a `Host` line declares, or nil when the line is not one."
  [line]
  (when-let [[_ rest] (re-matches #"(?i)\s*Host\s+(.*?)\s*" line)]
    (remove str/blank? (str/split rest #"\s+"))))

(defn foreign-stanza-line
  "The 1-based line number of a `Host <alias>` stanza that this package did not
  write, or nil. Lines between our own markers are ours and are skipped.

  `alias` is the stanza being searched for; `marker-alias` names the managed
  block, and the two are not the same thing. This deployment writes ONE block,
  marked with the profile, containing a `Host` stanza for the profile and for
  every node. Deriving the marker from the stanza being searched — the obvious
  reading, and the one a single-node package can get away with — makes the
  check look for `# BEGIN clickhouse-digitalocean-0 …`, never find it, conclude it is
  outside a managed block, and refuse to converge because of a block this
  package wrote itself."
  ([lines alias] (foreign-stanza-line lines alias alias))
  ([lines alias marker-alias]
   (let [{:keys [begin end]} (owned-markers marker-alias)]
     (loop [[line & more] lines n 1 inside? false]
       (cond
         (nil? line) nil
         (contains? begin (str/trim line)) (recur more (inc n) true)
         (contains? end (str/trim line)) (recur more (inc n) false)
         (and (not inside?) (some #{alias} (host-patterns line))) n
         :else (recur more (inc n) inside?))))))

(defn leading-option-line
  "The 1-based line number of an option standing above the first `Host` or
  `Match` line, or nil.

  Such an option is global: it applies to every host the operator reaches. The
  block is written with `insertbefore: BOF`, so it would land above that option
  and capture it into this deployment's stanza, silently narrowing a global
  setting to one host. Blank lines and comments are not options."
  [lines]
  (loop [[line & more] lines n 1]
    (let [trimmed (str/trim (str line))]
      (cond
        (nil? line) nil
        (or (str/blank? trimmed) (str/starts-with? trimmed "#")) (recur more (inc n))
        (re-matches #"(?i)\s*(Host|Match)\s+.*" line) nil
        :else n))))

(defn adopt-error
  "The standard's never-adopt rule (§5). A hand-written `Host <alias>` stanza
  may be the operator's only record of how to reach something, so it stops the
  run rather than being overwritten.

  Every alias is checked, not just the bare profile: this package claims
  `<profile>` and `<profile>-<i>` for each node, and adopting any one of them
  silently would lose exactly as much as adopting the first."
  [opts]
  (let [f (config-path)]
    (when (.isFile f)
      (let [lines (str/split-lines (slurp f))
            marker (host-alias opts)]
        (when-let [[alias n] (some (fn [a]
                                     (when-let [n (foreign-stanza-line lines a marker)]
                                       [a n]))
                                   (aliases opts))]
        (str "refusing to manage " (.getPath f) ": it already declares "
             "`Host " alias "` at line " n
             " outside this package's managed block. Remove or rename that "
             "stanza if it is stale, or change `profile` if it belongs to "
             "something else; this package will not overwrite it."))))))

(defn placement-error
  "The standard's placement rule (§5), in the one shape that cannot be honoured
  without changing the meaning of the operator's file."
  [_opts]
  (let [f (config-path)]
    (when (.isFile f)
      (when-let [n (leading-option-line (str/split-lines (slurp f)))]
        (str "refusing to manage " (.getPath f) ": line " n
             " sets an option above the first `Host` line, so it applies to "
             "every host. This package inserts its block at the top of the "
             "file, which would capture that option into one stanza. Move "
             "those global options below the managed block, or into an "
             "explicit `Host *` stanza at the end of the file, and retry.")))))

(defn preflight!
  "Run the local checks. Real create only: build and dry-run must not read
  `~/.ssh/config` at all (§6)."
  [opts]
  (if-let [err (or (adopt-error opts) (placement-error opts))]
    (assoc opts :green/exit 1 :green/err err)
    opts))
