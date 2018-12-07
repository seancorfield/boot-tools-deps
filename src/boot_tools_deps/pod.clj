;; copyright (c) 2017-2018 sean corfield

(ns boot-tools-deps.pod
  "For running tools.deps inside a pod."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.deps.alpha.reader :as reader])
  (:import [java.io File]))

(defn build-environment-map
  "Run tools.deps to produce:
  * :resource-paths  -- source code directories from :paths in deps.edn files
  * :source-paths -- additional directories from :extra-paths and classpath
  * :repositories -- any entries from :mvn/repos
  * :dependencies -- vector of Maven coordinates
  * :classpath -- JAR files to add to the classpath
  * :main-opts -- any main-opts pulled from tools.deps.alpha"
  [{:keys [config-data ; no config-paths
           classpath-aliases main-aliases resolve-aliases
           verbose ; no repeatable
           system-deps deps-files total]
    :as options}]
  (let [deps         (reader/read-deps
                       (into [] (comp (map io/file)
                                      (filter #(.exists %)))
                             deps-files))
        deps         (if total
                       (if config-data
                         (reader/merge-deps [deps config-data])
                         deps)
                       (reader/merge-deps
                         (cond-> [system-deps deps]
                           config-data (conj config-data))))
        paths        (set (or (seq (:paths deps)) []))
        resolve-args (cond->
                       (deps/combine-aliases deps resolve-aliases)
                       ;; handle both legacy boolean and new counter
                       (and verbose
                            (or (boolean? verbose)
                                (< 1 verbose)))
                       (assoc :verbose true))
        libs         (deps/resolve-deps deps resolve-args)
        cp-args      (deps/combine-aliases deps classpath-aliases)
        cp           (deps/make-classpath libs (:paths deps) cp-args)
        main-opts    (:main-opts (deps/combine-aliases deps main-aliases))
        cp-separator (re-pattern java.io.File/pathSeparator)
        [jars dirs]  (reduce (fn [[jars dirs] item]
                               (let [f (java.io.File. item)]
                                 (if (and (.exists f)
                                          (not (paths item)))
                                   (cond (.isFile f)
                                         [(conj jars item) dirs]
                                         (.isDirectory f)
                                         [jars (conj dirs item)]
                                         :else
                                         [jars dirs])
                                   [jars dirs])))
                             [[] []]
                             (str/split cp cp-separator))]
    {:resource-paths paths
     :source-paths   (set dirs)
     :repositories   (:mvn/repos deps)
     :dependencies   libs
     :classpath      jars
     :main-opts      main-opts}))
