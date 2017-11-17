# boot-tools-deps

A Boot task that uses `tools.deps(.alpha)` to read in `deps.edn` files in the same way that the `clj` script uses them (almost).

[![Clojars Project](https://img.shields.io/clojars/v/seancorfield/boot-tools-deps.svg)](https://clojars.org/seancorfield/boot-tools-deps)

## Usage

You can either add this to your `build.boot` file's `:dependencies`:

    [seancorfield/boot-tools-deps "0.1.4"]

and then expose the task with:

    (require '[boot-tools-deps.core :refer [deps]])

or you can just add it as command line dependency:

    boot -d seancorfield/boot-tools-deps:0.1.4 ...

The available arguments are:

* `-c` `--config-files` -- specify the `deps.edn` files to be used
* `-A` `--aliases` -- shorthand for specifying `-R` and `-C` with the same alias
* `-R` `--resolve-aliases` -- specify the aliases for resolving dependencies
* `-C` `--classpath-aliases` -- specify the aliases for classpath additions
* `-r` `--repeatable` -- ignores `~/.clojure/deps.edn`
* `-v` `--verbose` -- explain what the task is doing (this also makes `tools.deps` verbose)

Differences from how `clj` works:

* The "system" `deps.edn` file is not read but `boot-tools-deps` merges in a copy taken from the `clojure/brew-install` repository so the effect should be the same. _This means the default `deps.edn` information may lag behind the latest, distributed/installed version, or may be ahead of the version you actually have installed (if you have not updated `clojure` recently). The version of Clojure used is whatever version is running by the time this task is run -- you can only change that via `BOOT_CLOJURE_VERSION` or `~/.boot/boot.properties`, not via `deps.edn`._
* `clj` computes the full classpath and caches it in a local file. _`boot-tools-deps` does not do this (since it does not perform the final classpath computation -- it just updates the dependencies and paths so Boot itself can deal with that)._
* Whatever value of `:paths` comes back from `tools.deps` is used as the `:resource-paths` value for Boot. Similarly, whatever value of `:extra-paths` comes back is used as the `:source-paths` value. This allows you to specify `"src"` and `"test"` (or whatever your project's equivalent are) in `deps.edn`, with aliases as needed, and have `clj` and `boot deps` behave in much the same way.
* `boot-tools-deps` does not support `:local` dependencies (yet!).

## Changes

* 0.1.4 -- unreleased -- Fix #3 by updating `deps.edn` template from `brew-install` (changes Clojars repo URL); switches from `HOME` environment variable to `user.home` system property; adds `-A` option for when you need the same alias on both `-R` and `-C`; now relies on `tools.deps.alpha.makecp` loading all the specific providers (instead of loading them manually).
* 0.1.3 -- 11/15/2017 -- Fix #2 by using `deps.edn` template from `brew-install` repo as defaults.
* 0.1.2 -- 11/13/2017 -- Expose `deps` task machinery as a function, `load-deps`, for more flexibility.
* 0.1.1 -- 11/12/2017 -- First working version.

## License

Copyright © 2017 Sean Corfield, all rights reserved.

Distributed under the Eclipse Public License version 1.0.
