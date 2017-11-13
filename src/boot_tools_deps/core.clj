;; Copyright (c) 2017 World Singles llc

(ns boot-tools-deps.core
  "Set up dependencies from deps.edn files using tools.deps."
  {:boot/export-tasks true}
  (:require [boot.core :as boot :refer [deftask]]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.makecp :as util]
            [clojure.tools.deps.alpha.reader :as reader]
            [clojure.tools.deps.alpha.util.maven :as mvn]))

;; Pull in the specific artifact providers
(require ; '[clojure.tools.deps.alpha.providers.git]
         '[clojure.tools.deps.alpha.providers.local]
         '[clojure.tools.deps.alpha.providers.maven])

(defn- libs->boot-deps
  "Turn tools.deps resolved dependencies (libs) into Boot-style
  dependencies, one step at a time."
  [deps artifact info]
  (if-let [version (:mvn/version info)]
    (conj deps
          (transduce cat conj [artifact version]
                     (select-keys info [:scope :exclusions])))
    deps))

(deftask deps
  "Use tools.deps to read and resolve the specified deps.edn files.

  The dependencies read in are added to your Boot :dependencies vector.

  With the exception of -r and -v, the arguments are intended to match
  the clj script usage (as passed to clojure.tools.deps.alpha.makecp/-main)."
  [c config-paths    PATH [str] "the list of deps.edn files to read"
   C classpath-aliases KW [kw]  "the list of classpath aliases to use"
   R resolve-aliases   KW [kw]  "the list of resolve aliases to use"
   r repeatable           bool  "Exclude ~/.clojure/deps.edn for a repeatable build"
   v verbose              bool  "Be verbose (and ask tools.deps to be verbose too)"]
  (let [home-dir     (get (System/getenv) "HOME")
        deps-files (cond->> ["deps.edn"]
                       (seq config-paths)
                       (into config-paths)
                       (and home-dir (not repeatable))
                       (into [(str home-dir "/.clojure/deps.edn")]))
        _            (when verbose
                       (println "Looking for these deps.edn files:")
                       (pp/pprint deps-files)
                       (println))
        deps         (reader/read-deps (into [] (comp (map io/file)
                                                      (filter #(.exists %)))
                                             deps-files))
        ;; We add in the version of Clojure we are running with so that
        ;; tools.deps doesn't let another version of Clojure get loaded.
        deps         (merge-with
                       merge {:deps {'org.clojure/clojure
                                     {:mvn/version (clojure-version)}}
                              :mvn/repos mvn/standard-repos}
                       deps)
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
    (when-let [paths (not-empty (into (set (:paths deps))
                                      (:paths cp-args)))]
      (when verbose
        (println "And these :resource-paths (:paths)    : " paths))
      (boot/merge-env! :resource-paths paths))
    (when-let [paths (not-empty (into (set (:extra-paths deps))
                                      (:extra-paths cp-args)))]
      (when verbose
        (println "And these :source-paths (:extra-paths): " paths))
      (boot/merge-env! :source-paths paths))
    identity))
