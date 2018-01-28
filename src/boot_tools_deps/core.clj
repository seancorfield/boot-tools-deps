;; Copyright (c) 2017-2018 World Singles llc

(ns boot-tools-deps.core
  "Set up dependencies from deps.edn files using tools.deps."
  {:boot/export-tasks true}
  (:require [boot.core :as boot :refer [deftask]]
            [boot.pod :as pod]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :as reader]
            ;; load the various extension points
            [clojure.tools.deps.alpha.extensions]
            [clojure.tools.deps.alpha.extensions.deps]
            [clojure.tools.deps.alpha.extensions.git]
            [clojure.tools.deps.alpha.extensions.local]
            [clojure.tools.deps.alpha.extensions.maven]
            [clojure.tools.deps.alpha.extensions.pom]))

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
        deps-files   (if (seq config-paths)
                       config-paths ;; the complete list of deps.edn files
                       (cond->> ["deps.edn"]
                         (not repeatable)
                         (into [(str home-dir "/.clojure/deps.edn")])))
        _            (when verbose
                       (println "Looking for these deps.edn files:")
                       (pp/pprint deps-files)
                       (println))
        deps         (reader/read-deps
                      (into [] (comp (map io/file)
                                     (filter #(.exists %)))
                            deps-files))
        deps         (if (or repeatable (seq config-paths))
                       deps
                       (reader/merge-deps [(load-default-deps) deps]))
        paths        (set (or (seq (:paths deps)) []))
        resolve-args (cond-> (deps/combine-aliases deps resolve-aliases)
                       ;; handle both legacy boolean and new counter
                       (and verbose (or (boolean? verbose) (< 1 verbose)))
                       (assoc :verbose true))
        libs         (deps/resolve-deps deps resolve-args)
        final-deps   (reduce-kv libs->boot-deps [] libs)
        cp-args      (deps/combine-aliases deps classpath-aliases)
        cp           (deps/make-classpath libs (:paths deps) cp-args)
        cp-seq       (str/split cp (re-pattern java.io.File/pathSeparator))
        [jars dirs]  (reduce (fn [[jars dirs] item]
                               (let [f (java.io.File. item)]
                                 (if (and (.exists f) (not (paths item)))
                                   (cond (.isFile f)
                                         [(conj jars item) dirs]
                                         (.isDirectory f)
                                         [jars (conj dirs item)]
                                         :else
                                         [jars dirs])
                                   [jars dirs])))
                             [[] []]
                             cp-seq)]
    (when verbose
      (println "\nProduced these dependencies:")
      (pp/pprint final-deps))
    (when (seq paths)
      (when verbose
        (println "\nAdding these :resource-paths"
                 (str/join " " paths)))
      (boot/merge-env! :resource-paths paths))
    (when (seq dirs)
      (when verbose
        (println "Adding these :source-paths  "
                 (str/join " " dirs)))
      (boot/merge-env! :source-paths (set dirs)))
    (doseq [jar jars]
      (pod/add-classpath jar))))

(deftask deps
  "Use tools.deps to read and resolve the specified deps.edn files.

  The dependencies read in are added to your Boot :dependencies vector.

  With the exception of -A, -r, and -v, the arguments are intended to match
  the clj script usage (as passed to clojure.tools.deps.alpha.makecp/-main).
  Note, in particular, that -c / --config-paths is assumed to be the COMPLETE
  list of EDN files to read (and therefore overrides the default set of
  system deps, user deps, and local deps).

  The -r option is equivalent to the -Srepro option in tools.deps, which will
  exclude both the system deps and the user deps."
  [c config-paths    PATH [str] "the list of deps.edn files to read."
   A aliases           KW [kw]  "the list of aliases (for both -C and -R)."
   C classpath-aliases KW [kw]  "the list of classpath aliases to use."
   R resolve-aliases   KW [kw]  "the list of resolve aliases to use."
   r repeatable           bool  "Use only the specified deps.edn file for a repeatable build."
   v verbose              int   "the verbosity level."]
  (load-deps {:config-paths      config-paths
              :classpath-aliases (into (vec aliases) classpath-aliases)
              :resolve-aliases   (into (vec aliases) resolve-aliases)
              :repeatable        repeatable
              :verbose           verbose})
  identity)
