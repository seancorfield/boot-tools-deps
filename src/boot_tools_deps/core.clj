;; Copyright (c) 2017-2018 World Singles llc

(ns boot-tools-deps.core
  "Set up dependencies from deps.edn files using tools.deps."
  {:boot/export-tasks true}
  (:require [boot.core :as boot :refer [deftask]]
            [boot.pod :as pod]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(defn- load-default-deps
  "Read our default-deps.edn file and substitute some values.
  This comes from the brew-install repo originally and it has
  (currently) two variable substitutions:

  * ${clojure.version} -- we use the runtime Clojure version,
  * ${tools.deps.version} -- we use \"RELEASE\".

  We do not (yet) canonicalize the symbols in it (that tools.deps
  function is private)."
  []
  (some-> (io/resource "boot-tools-deps-default-deps.edn")
          (slurp)
          (str/replace "${clojure.version}" (clojure-version))
          (str/replace "${tools.deps.version}" "RELEASE")
          (edn/read-string)))

(defn- libs->boot-deps
  "Convert tools.deps dependencies into Boot dependencies.

  Only Maven dependencies are added in, with :scope and :exclusions
  preserved (if present), which will include those drawn from deps
  and from pom extensions in tools.deps."
  [deps artifact info]
  (if-let [version (:mvn/version info)]
    (conj deps
          (transduce cat conj [artifact version]
                     (select-keys info
                                  [:scope :exclusions])))
    deps))

(defn- make-pod
  []
  (let [pod-env (-> (boot/get-env)
                    ;; Pod class path needs to be bootstrapped independently of
                    ;; the core Pod (build.boot context) so that, for example,
                    ;; an older version of Clojure than 1.9 can be used in the
                    ;; core Pod.
                    (dissoc :boot-class-path :fake-class-path)
                    ;; Clojure version in the core Pod (build.boot context)
                    ;; is not guaranteed to be recent enough as Boot supports
                    ;; 1.6.0 onwards. Therefore filter out and re-add Clojure
                    ;; dependency.
                    (update :dependencies
                      (fn [dependencies]
                        (conj (filter
                                #(not (= (first %) 'org.clojure/clojure))
                                dependencies)
                          '[org.clojure/clojure "RELEASE"]
                          '[org.clojure/tools.deps.alpha "0.5.342"]))))]
   (pod/make-pod pod-env)))

(defn- tools-deps
  "Run tools.deps inside a pod to produce:
  * :resource-paths -- source code directories from :paths in deps.edn files
  * :source-paths -- additional directories from :extra-paths and classpath
  * :dependencies -- vector of Maven coordinates
  * :classpath -- JAR files to add to the classpath"
  [deps-files deps-data classpath-aliases resolve-aliases total verbose]
  (let [system-deps     (when-not total (load-default-deps))
        pod             (make-pod)
        paths           (pod/with-call-in pod
                          (boot-tools-deps.pod/build-environment-map
                            ~system-deps ~deps-files ~deps-data
                            ~classpath-aliases ~resolve-aliases
                            ~total ~verbose))]
      (future (pod/destroy-pod pod))
      paths))

(defn load-deps
  "Functional version of the deps task.

  Can be called from other Boot code as needed."
  [{:keys [config-paths config-data classpath-aliases resolve-aliases
           overwrite-boot-deps repeatable verbose]}]
  (let [home-dir   (System/getProperty "user.home")
        _          (assert home-dir "Unable to determine your home directory!")
        deps-files (if (seq config-paths)
                     config-paths ;; the complete list of deps.edn files
                     (cond->> ["deps.edn"]
                       (not repeatable)
                       (into [(str home-dir "/.clojure/deps.edn")])))
        _          (when verbose
                     (println "Looking for these deps.edn files:")
                     (pp/pprint deps-files)
                     (println))
        {:keys [resource-paths source-paths dependencies classpath]}
        (tools-deps deps-files config-data classpath-aliases resolve-aliases
                    (or repeatable (seq config-paths)) verbose)
        boot-libs  (reduce-kv libs->boot-deps [] dependencies)]
    (when verbose
      (println "\nProduced these dependencies:")
      (pp/pprint boot-libs))
    (when (seq resource-paths)
      (when verbose
        (println "\nAdding these :resource-paths"
                 (str/join " " resource-paths)))
      (boot/merge-env! :resource-paths resource-paths))
    (when (seq source-paths)
      (when verbose
        (println "Adding these :source-paths  "
                 (str/join " " source-paths)))
      (boot/merge-env! :source-paths (set source-paths)))
    (doseq [jar classpath]
      (when verbose
        (println "Adding" jar "to classpath"))
      (pod/add-classpath jar))
    (when overwrite-boot-deps
      ;; indulge in some hackery to persuade Boot these are the total
      ;; dependencies (for building uber jar etc) -- note that we have
      ;; to remove :dependencies from cli-base to prevent set-env!
      ;; adding those back in!
      (when verbose
        (println "Overwriting Boot's :dependencies"))
      (swap! @#'boot/boot-env assoc :dependencies [])
      (swap! @#'boot/cli-base dissoc :dependencies)
      (boot/set-env! :dependencies boot-libs))))

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
  [;; options that mirror tools.deps itself:
   c config-paths    PATH [str] "the list of deps.edn files to read."
   D config-data      EDN edn   "is treated as a final deps.edn file."
   C classpath-aliases KW [kw]  "the list of classpath aliases to use."
   R resolve-aliases   KW [kw]  "the list of resolve aliases to use."
   ;; options specific to boot-tools-deps
   A aliases           KW [kw]  "the list of aliases (for both -C and -R)."
   B overwrite-boot-deps  bool  "Overwrite Boot's :dependencies."
   r repeatable           bool  "Use only the specified deps.edn file for a repeatable build."
   v verbose              int   "the verbosity level."]
  (load-deps {:config-paths        config-paths
              :config-data         config-data
              :classpath-aliases   (into (vec aliases) classpath-aliases)
              :resolve-aliases     (into (vec aliases) resolve-aliases)
              :overwrite-boot-deps overwrite-boot-deps
              :repeatable          repeatable
              :verbose             verbose})
  identity)
