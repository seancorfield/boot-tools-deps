# boot-tools-deps

A Boot task that uses `tools.deps(.alpha)` to read in `deps.edn` files in the same way that the `clj` script uses them (almost).

## Usage

You can either add this to your `build.boot` file's `:dependencies`:

    [boot-tools-deps "0.1.0"]

and then expose the task with:

    (require '[boot-tools-deps.core :refer [deps]])

or you can just add it as command line dependency:

    boot -d boot-tools-deps:0.1.0 ...

The available arguments are:

* `-c` `--config-files` -- specify the `deps.edn` files to be used
* `-R` `--resolve-aliases` -- specify the aliases for resolving dependencies
* `-C` `--classpath-aliases` -- specify the aliases for classpath additions
* `-r` `--repeatable` -- ignores `~/.clojure/deps.edn`
* `-v` `--verbose` -- explain what the task is doing (this also makes `tools.deps` verbose)

Differences from how `clj` works:

* The "system" `deps.edn` file is not read. This is the `deps.edn` file that lives in the `clj` installation. It provides basic defaults for `:paths` (`["src"]`), `:deps` (the version of Clojure that is installed with `clj`), `:mvn/repos` (Clojars and Maven Central) and provides a (resolve) alias of `:deps` (which adds `tools.deps.alpha` as `:extra-deps`) and a (classpath) alias of `:test` (which adds `["test"]` as `:extra-paths`). _`boot-tools-deps` has no default for `:paths` and provides no default aliases, but it uses the same default `:mvn/repos`. It sets the default `:deps` to whatever version of Clojure is running by the time this task is run._
* `clj` computes the full classpath and caches it in a local file. _`boot-tools-deps` does not do this (since it does not perform the final classpath computation -- it just updates the dependencies and paths so Boot itself can deal with that)._
* Whatever value of `:paths` comes back from `tools.deps` is used as the `:resource-paths` value for Boot. Similarly, whatever value of `:extra-paths` comes back is used as the `:source-paths` value. This allows you to specify `"src"` and `"test"` (or whatever your project's equivalent are) in `deps.edn`, with aliases as needed, and have `clj` and `boot deps` behave in much the same way.
* `boot-tools-deps` does not support `:local` dependencies (yet!).

## License

Copyright © 2017 Sean Corfield, all rights reserved.

Distributed under the Eclipse Public License version 1.0.
