(def project 'boot-tools-deps)
(def version "0.1.0")

(set-env! :resource-paths #{"src"}
          :dependencies   '[[org.clojure/clojure "RELEASE"]
                            [org.clojure/tools.deps.alpha "RELEASE"]
                            [boot/core "RELEASE" :scope "test"]])

(task-options!
 pom {:project     project
      :version     version
      :description "A Boot task that reads deps.edn file using tools.deps"
      :url         "https://github.com/seancorfield/boot-deps"
      :scm         {:url "https://github.com/seancorfield/boot-deps"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}})

(deftask build
  "Build and install the project locally."
  []
  (comp (pom) (jar) (install)))

(require '[boot-tools-deps.core :refer [deps]])
