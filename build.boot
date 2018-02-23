(def project 'seancorfield/boot-tools-deps)
(def version "0.4.3")

(set-env! :resource-paths #{"resources" "src"}
          :dependencies   '[[org.clojure/clojure "RELEASE" :scope "provided"]
                            [boot/core "RELEASE" :scope "provided"]
                            [clj-http "RELEASE" :scope "test"]])

(task-options!
 pom {:project     project
      :version     version
      :description "A Boot task that reads deps.edn files using tools.deps"
      :url         "https://github.com/seancorfield/boot-tools-deps"
      :scm         {:url "https://github.com/seancorfield/boot-tools-deps"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}})

(def ^:private brew-install-edn
  (str "https://raw.githubusercontent.com/clojure/brew-install"
       "/master/src/main/resources/deps.edn"))

(defn- update-default-deps
  "Update our local EDN from the brew-install repo."
  []
  (require '[clj-http.client :as http])
  (let [brew-install-deps ((resolve 'http/get) brew-install-edn)]
    (spit "resources/boot-tools-deps-default-deps.edn"
          (:body brew-install-deps))))

(deftask build
  "Build and install the project locally."
  []
  (update-default-deps)
  (comp (pom) (jar) (install)))

(deftask deploy
  "Build and deploy the project."
  []
  (comp (pom) (jar) (push)))

(comment
  (require '[boot-tools-deps.core :refer [deps]])
  (boot.core/merge-env! :dependencies '[[org.clojure/tools.deps.alpha "RELEASE"]])
  (boot.core/merge-env! :dependencies '[[proto-repl "RELEASE"]]))
