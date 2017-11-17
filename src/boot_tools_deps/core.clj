;; Copyright (c) 2017 World Singles llc

(ns boot-tools-deps.core
  "Set up dependencies from deps.edn files using tools.deps."
  {:boot/export-tasks true}
  (:require [boot.core :as boot :refer [deftask]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.makecp :as util]
            [clojure.tools.deps.alpha.reader :as reader]))

(defn- libs->boot-deps
  "Turn tools.deps resolved dependencies (libs) into Boot-style
  dependencies, one step at a time."
  [deps artifact info]
  (if-let [version (:mvn/version info)]
    (conj deps
          (transduce cat conj [artifact version]
                     (select-keys info [:scope :exclusions])))
    deps))

(defn- load-default-deps
  "Read our default-deps.edn file, substitute some values, convert it to
  a hash map. This comes from the brew-install repo originally and it has
  (currently) two variable substitutions:

  * ${clojure.version} -- we use the runtime Clojure version,
  * ${tools.deps.version} -- we use \"RELEASE\"."
  []
  (some-> (io/resource "boot-tools-deps-default-deps.edn")
          (slurp)
          (str/replace "${clojure.version}" (clojure-version))
          (str/replace "${tools.deps.version}" "RELEASE")
          (edn/read-string)))

(defn load-deps
  "Functional version of the deps task.

  Can be called from other Boot code as needed."
  [{:keys [config-paths classpath-aliases resolve-aliases repeatable verbose]}]
  (let [home-dir     (System/getProperty "user.home")
        _            (assert home-dir "Unable to determine your home directory!")
        deps-files   (cond->> ["deps.edn"]
                         (seq config-paths)
                         (into config-paths)
                         (and home-dir (not repeatable))
                         (into [(str home-dir "/.clojure/deps.edn")]))
        _            (when verbose
                       (println "Looking for these deps.edn files:")
                       (pp/pprint deps-files)
                       (println))
        ;; read-deps has special handling of `:paths` that assumes there is
        ;; always one in the set of EDN files read in. This isn't true when
        ;; we exclude the system default so we need special logic to handle
        ;; that case:
        deps         (reader/read-deps (into [] (comp (map io/file)
                                                      (filter #(.exists %)))
                                             deps-files))
        has-paths?   (:paths deps)
        deps         (merge-with merge
                       (cond-> (load-default-deps)
                         ;; Last one wins, so remove default:
                         has-paths? (dissoc :paths))
                       (cond-> deps
                         ;; Ensure no nil :paths entry in deps:
                         (not has-paths?) (dissoc :paths)))
        resolve-args (cond-> (#'util/resolve-deps-aliases deps
                               (str/join (map str resolve-aliases)))
                       verbose (assoc :verbose true))
        cp-args      (#'util/resolve-cp-aliases deps
                       (str/join (map str classpath-aliases)))
        libs         (deps/resolve-deps deps resolve-args)
        final-deps   (reduce-kv libs->boot-deps [] libs)]
    (when verbose
      (println "\nAdding these dependencies:")
      (pp/pprint final-deps))
    (boot/merge-env! :dependencies final-deps)
    (when-let [paths (not-empty (:paths deps))]
      (when verbose
        (println "And these :resource-paths (:paths)    : " paths))
      (boot/merge-env! :resource-paths (set paths)))
    ;; Handle :extra-paths via last one wins:
    (when-let [paths (or (not-empty (:extra-paths cp-args))
                         (not-empty (:extra-paths deps)))]
      (when verbose
        (println "And these :source-paths (:extra-paths): " paths))
      (boot/merge-env! :source-paths (set paths)))))

(deftask deps
  "Use tools.deps to read and resolve the specified deps.edn files.

  The dependencies read in are added to your Boot :dependencies vector.

  With the exception of -r and -v, the arguments are intended to match
  the clj script usage (as passed to clojure.tools.deps.alpha.makecp/-main)."
  [c config-paths    PATH [str] "the list of deps.edn files to read"
   A aliases           KW [kw]  "the list of aliases (for both -C and -R)"
   C classpath-aliases KW [kw]  "the list of classpath aliases to use"
   R resolve-aliases   KW [kw]  "the list of resolve aliases to use"
   r repeatable           bool  "Exclude ~/.clojure/deps.edn for a repeatable build"
   v verbose              bool  "Be verbose (and ask tools.deps to be verbose too)"]
  (load-deps {:config-paths      config-paths
              :classpath-aliases (into (vec aliases) classpath-aliases)
              :resolve-aliases   (into (vec aliases) resolve-aliases)
              :repeatable        repeatable
              :verbose           verbose})
  identity)
