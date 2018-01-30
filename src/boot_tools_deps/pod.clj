(ns boot-tools-deps.pod
  "For running tools.deps inside a pod."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.deps.alpha.reader :as reader]
    ;; load the various extension points
    [clojure.tools.deps.alpha.extensions]
    [clojure.tools.deps.alpha.extensions.deps]
    [clojure.tools.deps.alpha.extensions.git]
    [clojure.tools.deps.alpha.extensions.local]
    [clojure.tools.deps.alpha.extensions.maven]
    [clojure.tools.deps.alpha.extensions.pom])
  (:import [java.io File]))

(defn build-environment-map
  "Run tools.deps to produce:
  * :resource-paths  -- source code directories from :paths in deps.edn files
  * :source-paths -- additional directories from :extra-paths and classpath
  * :dependencies -- vector of Maven coordinates
  * :classpath -- JAR files to add to the classpath"
  [system-deps deps-files deps-data classpath-aliases resolve-aliases total verbose]
  (let [deps         (reader/read-deps
                       (into [] (comp (map io/file)
                                      (filter #(.exists %)))
                             deps-files))
        deps         (if total
                       (if deps-data
                         (reader/merge-deps [deps deps-data])
                         deps)
                       (reader/merge-deps
                         (cond-> [system-deps deps]
                                 deps-data (conj deps-data))))
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
        cp-separator (re-pattern java.io.File/pathSeparator)
        [jars dirs] (reduce (fn [[jars dirs] item]
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
     :dependencies   libs
     :classpath      jars}))
