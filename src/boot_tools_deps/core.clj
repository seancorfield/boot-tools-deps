;; copyright (c) 2017-2018 sean corfield

(ns boot-tools-deps.core
  "Set up dependencies from deps.edn files using tools.deps."
  {:boot/export-tasks true}
  (:require [boot.core :as boot :refer [deftask]]
            [boot.pod :as pod]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(def ^:private tools-deps-version
  "The version of tools.deps(.alpha) that we are known to work with."
  "0.5.373")

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
                                  [:classifier
                                   :extension
                                   :exclusions
                                   :scope])))
    deps))

(defn- recent-clojure?
  "Called with a Clojure version string. Returns true if the version is
  recent enough to run tools.deps.alpha (we assume 1.9.0 because that will
  likely soon be the minimum required version)."
  [version]
  (or (= "LATEST" version)
      (= "RELEASE" version)
      (let [[v pre] (str/split version #"-")
            n-v     (map #(Long/parseLong %) (take 3 (str/split v #"\.")))]
        (and (<= 1 (first n-v))
             (<= 9 (second n-v))
             (or (< 9 (second n-v))
                 (nil? pre))))))

(defn- ensure-recent-clojure-tools-deps
  "Given a :dependencies vector, ensure it has a recent enough version of
  Clojure and our desired version of tools.deps(.alpha).

  This means that we don't force a download of post-1.9.0 Clojure if the
  user is already depending on that (or a later) version."
  [dependencies]
  (let [[deps seen-clj?]
        (reduce (fn [[deps seen-clj?] [artifact version :as dep]]
                  (cond (= 'org.clojure/clojure artifact)
                        (if (recent-clojure? version)
                          ;; keep it, record we saw it
                          [(conj deps dep) true]
                          ;; remove it, we'll re-add it below
                          [deps seen-clj?])
                        (= 'org.clojure/tools.deps.alpha artifact)
                        ;; remove it, we'll re-add it below
                        [deps seen-clj?]
                        :else
                        [(conj deps dep) seen-clj?]))
                [[] false] dependencies)]
    ;; add our desired version of tools.deps(.alpha)
    (cond-> (conj deps ['org.clojure/tools.deps.alpha tools-deps-version])
      (not seen-clj?) ; add whatever the latest release of Clojure is
      (conj ['org.clojure/clojure "RELEASE"]))))

(defn- make-pod
  "Make and return a Boot pod with tools.deps.alpha and a recent Clojure
  release (to ensure that tools.deps.alpha will run). The pod will also
  include Boot and boot-tools-deps (since they're both running), as well as
  any other 'runtime' dependencies (which is why it's best to avoid putting
  additional dependencies in build.boot when using this tool!)."
  []
  (let [pod-env
        (-> (boot/get-env)
            ;; Pod class path needs to be bootstrapped independently of
            ;; the core Pod (build.boot context) so that, for example,
            ;; an older version of Clojure than 1.9 can be used in the
            ;; core Pod.
            (dissoc :boot-class-path :fake-class-path)
            ;; Clojure version in the core Pod (build.boot context)
            ;; is not guaranteed to be recent enough as Boot supports
            ;; 1.6.0 onwards. If it isn't recent enough, we replace it.
            ;; We also force tools.deps.alpha to a fixed version.
            (update :dependencies ensure-recent-clojure-tools-deps))]
    (pod/make-pod pod-env)))

(defn- tools-deps
  "Run tools.deps inside a pod to produce:
  * :resource-paths -- source code directories from :paths in deps.edn files
  * :source-paths -- additional directories from :extra-paths and classpath
  * :dependencies -- vector of Maven coordinates
  * :classpath -- JAR files to add to the classpath
  * :main-opts -- any main-opts pulled from tools.deps.alpha"
  [{:keys [config-paths config-data
           classpath-aliases main-aliases resolve-aliases
           repeatable verbose]
    :as options}]
  (let [total       (or repeatable (seq config-paths))
        home-dir    (System/getProperty "user.home")
        _           (assert home-dir "Unable to determine your home directory!")
        deps-files  (if (seq config-paths)
                      config-paths ;; the complete list of deps.edn files
                      (cond->> ["deps.edn"]
                        (not repeatable)
                        (into [(str home-dir "/.clojure/deps.edn")])))
        _           (when verbose
                      (println "Looking for these deps.edn files:")
                      (pp/pprint deps-files)
                      (println))
        system-deps (when-not total (load-default-deps))
        pod         (make-pod)
        pod-options (-> options
                        (dissoc :config-paths)
                        (assoc :system-deps system-deps
                               :deps-files deps-files
                               :total total))
        paths       (pod/with-call-in pod
                      (boot-tools-deps.pod/build-environment-map ~pod-options))]
    (pod/destroy-pod pod)
    paths))

(defn load-deps
  "Functional version of the deps task.

  Can be called from other Boot code as needed."
  [{:keys [config-paths config-data
           classpath-aliases main-aliases resolve-aliases
           repeatable verbose
           overwrite-boot-deps quick-merge]
    :as options}]
  (assert (not (and overwrite-boot-deps quick-merge))
          "Cannot use -B and -Q together!")
  (let [{:keys [resource-paths source-paths
                dependencies classpath]
         :as paths} (tools-deps options)
        boot-libs (reduce-kv libs->boot-deps [] dependencies)]
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
    (when quick-merge
      (when verbose
        (println "Merging with Boot's :dependencies"))
      (boot/merge-env! :dependencies boot-libs))
    (when overwrite-boot-deps
      ;; indulge in some hackery to persuade Boot these are the total
      ;; dependencies (for building uber jar etc) -- note that we have
      ;; to remove :dependencies from cli-base to prevent set-env!
      ;; adding those back in!
      (when verbose
        (println "Overwriting Boot's :dependencies"))
      (swap! @#'boot/boot-env assoc :dependencies [])
      (swap! @#'boot/cli-base dissoc :dependencies)
      (boot/set-env! :dependencies boot-libs))
    paths))

(deftask deps
  "Use tools.deps to read and resolve the specified deps.edn files.

  The dependencies read in are added to your Boot classpath. There are two
  options to update Boot's :dependencies if additional tasks in your pipeline
  rely on that: -B will completely overwrite the Boot :dependencies with the
  ones produced by tools.deps and should be used when you are creating uber
  jars; -Q will perform a quick merge of the dependencies from tools.deps into
  the Boot environment and may be needed for certain testing tools.

  As much as possible, the recommended approach is to avoid using the Boot
  :dependencies vector when using boot-tools-deps so that deps.edn represents
  the total dependencies for your project.

  Most of the arguments are intended to match the clj script usage
  (as passed to clojure.tools.deps.alpha.script.make-classpath/-main).

  In particular, the -c / --config-paths option is assumed to be the COMPLETE
  list of EDN files to read (and therefore overrides the default set of
  system deps, user deps, and local deps).

  The -r option is intended to be equivalent to the -Srepro option in
  tools.deps, which will exclude both the system deps and the user deps.

  The -D option is intended to be the equivalent to the -Sdeps option, which
  allows you to specify an additional deps.edn-like map of dependencies which
  are added to the set of deps.edn-derived dependencies (even when -r is
  given).

  The -A, -C, -M, and -R options mirror the clj script usage for aliases.

  The -x option will run clojure.main with any main-opts found by deps.edn.

  The -v option makes boot-tools-deps verbose, explaining which files it looked
  for, the dependencies it got back from tools.dep, and the changes it made to
  Boot's classpath, :resource-paths, and :source-path. If you specify it twice
  (-vv) then tools.deps will also be verbose about its work."
  [;; options that mirror tools.deps itself:
   c config-paths    PATH [str] "the list of deps.edn files to read."
   r repeatable           bool  "Use only the specified deps.edn file for a repeatable build."
   D config-data      EDN edn   "is treated as a final deps.edn file."
   A aliases           KW [kw]  "the list of aliases (for -C, -M, and -R)."
   C classpath-aliases KW [kw]  "the list of classpath aliases to use."
   M main-aliases      KW [kw]  "the list of main-opt aliases to use."
   R resolve-aliases   KW [kw]  "the list of resolve aliases to use."
   ;; options specific to boot-tools-deps
   B overwrite-boot-deps  bool  "Overwrite Boot's :dependencies."
   Q quick-merge          bool  "Merge into Boot's :dependencies."
   v verbose              int   "the verbosity level."
   x execute              bool  "Execute clojure.main with any main-opts found."]
  (let [{:keys [main-opts]}
        (load-deps {:config-paths        config-paths
                    :config-data         config-data
                    :classpath-aliases   (into (vec aliases) classpath-aliases)
                    :main-aliases        (into (vec aliases) main-aliases)
                    :resolve-aliases     (into (vec aliases) resolve-aliases)
                    :overwrite-boot-deps overwrite-boot-deps
                    :quick-merge         quick-merge
                    :repeatable          repeatable
                    :verbose             verbose})]
    (boot/with-pass-thru fs
      (when execute
        (when verbose
          (println "Executing clojure.main" (str/join " " main-opts)))
        (apply clojure.main/main main-opts)))))
