;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.tools.deps.alpha.script.make-classpath2
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.deps.alpha.reader :as reader]
    [clojure.tools.deps.alpha.util.io :as io :refer [printerrln]]
    [clojure.tools.deps.alpha.util.session :as session]
    [clojure.tools.deps.alpha.script.parse :as parse])
  (:import
    [clojure.lang IExceptionInfo]))

(def ^:private opts
  [;; deps.edn inputs
   [nil "--config-user PATH" "User deps.edn location"]
   [nil "--config-project PATH" "Project deps.edn location"]
   [nil "--config-data EDN" "Final deps.edn data to treat as the last deps.edn file" :parse-fn parse/parse-config]
   ;; output files
   [nil "--libs-file PATH" "Libs cache file to write"]
   [nil "--cp-file PATH" "Classpatch cache file to write"]
   [nil "--jvm-file PATH" "JVM options file"]
   [nil "--main-file PATH" "Main options file"]
   [nil "--skip-cp" "Skip writing .cp and .libs files"]
   ;; aliases
   ["-R" "--resolve-aliases ALIASES" "Concatenated resolve-deps alias names" :parse-fn parse/parse-kws]
   ["-C" "--makecp-aliases ALIASES" "Concatenated make-classpath alias names" :parse-fn parse/parse-kws]
   ["-J" "--jvmopt-aliases ALIASES" "Concatenated jvm option alias names" :parse-fn parse/parse-kws]
   ["-M" "--main-aliases ALIASES" "Concatenated main option alias names" :parse-fn parse/parse-kws]
   ["-T" "--tool-aliases ALIASES" "Concatenated tool alias names" :parse-fn parse/parse-kws]
   ["-A" "--aliases ALIASES" "Concatenated generic alias names" :parse-fn parse/parse-kws]
   ;; options
   [nil "--trace" "Emit trace log to trace.edn"]
   [nil "--threads THREADS" "Threads for concurrent downloads"]])

(defn parse-opts
  "Parse the command line opts to make-classpath"
  [args]
  (cli/parse-opts args opts))

(defn create-classpath
  "Given parsed-opts describing the input config files, and aliases to use,
  return the output lib map and classpath."
  [deps-map
   {:keys [resolve-aliases makecp-aliases aliases threads trace] :as _opts}]
  (session/with-session
    (let [resolve-args (deps/combine-aliases deps-map (concat aliases resolve-aliases))
          cp-args (deps/combine-aliases deps-map (concat aliases makecp-aliases))
          libs (deps/resolve-deps deps-map resolve-args {:threads threads, :trace trace})
          trace-log (-> libs meta :trace)
          effective-paths (or (:paths (deps/combine-aliases deps-map aliases))
                           (:paths deps-map))
          cp (deps/make-classpath libs effective-paths cp-args)]
      (cond->
        {:paths (vec (concat (:extra-paths cp-args) effective-paths))
         :libs libs
         :cp cp}
        trace (assoc :trace trace-log)))))

(defn check-aliases
  "Check that all aliases are known and warn if aliases are undeclared"
  [deps aliases]
  (when-let [unknown (seq (remove #(contains? (:aliases deps) %) (distinct aliases)))]
    (printerrln "WARNING: Specified aliases are undeclared:" (vec unknown))))

(defn run-core
  "Run make-classpath script from/to data (no file stuff). Returns:
    {;; Main outputs:
     :libs lib-map          ;; from resolve-deps, .libs file
     :cp classpath          ;; from make-classpath, .cp file
     :main main-opts        ;; effective main opts, .main file
     :jvm jvm-opts          ;; effective jvm opts, .jvm file
     :trace trace-log       ;; from resolve-deps, if requested, trace.edn file

     ;; Intermediate/source data:
     :deps merged-deps      ;; effective merged :deps
     :paths local-paths     ;; from make-classpath, just effective local paths
     ;; and any other qualified keys from top level merged deps
    }"
  [{:keys [install-deps user-deps project-deps config-data ;; all deps.edn maps
           resolve-aliases makecp-aliases jvmopt-aliases main-aliases aliases
           skip-cp] :as opts}]
  (let [deps-map (reader/merge-deps (remove nil? [install-deps user-deps project-deps config-data]))]
    (check-aliases deps-map (concat resolve-aliases makecp-aliases jvmopt-aliases main-aliases aliases))
    (let [deps-map' (if-let [replace-deps (get (deps/combine-aliases deps-map aliases) :deps)]
                      (reader/merge-deps (remove nil? [install-deps user-deps (merge project-deps {:deps replace-deps}) config-data]))
                      deps-map)
          cp-data (when-not skip-cp (create-classpath deps-map' opts))
          jvm (seq (get (deps/combine-aliases deps-map (concat aliases jvmopt-aliases)) :jvm-opts))
          main (seq (get (deps/combine-aliases deps-map (concat aliases main-aliases)) :main-opts))
          repo-config (reduce-kv (fn [m k v] (if (qualified-keyword? k) (assoc m k v) m)) {} deps-map)]
      (cond-> (merge repo-config cp-data {:deps (:deps deps-map')})
        jvm (assoc :jvm jvm)
        main (assoc :main main)))))

(defn run-core2
  "Run make-classpath script from/to data (no file stuff). Returns:
    {;; Main outputs:
     :libs lib-map          ;; from resolve-deps, .libs file
     :cp classpath          ;; from make-classpath, .cp file
     :main main-opts        ;; effective main opts, .main file
     :jvm jvm-opts          ;; effective jvm opts, .jvm file
     :trace trace-log       ;; from resolve-deps, if requested, trace.edn file

     ;; Intermediate/source data:
     :deps merged-deps      ;; effective merged :deps
     :paths local-paths     ;; from make-classpath, just effective local paths
     ;; and any other qualified keys from top level merged deps
    }"
  [{:keys [install-deps user-deps project-deps config-data ;; all deps.edn maps
           resolve-aliases makecp-aliases jvmopt-aliases main-aliases tool-aliases aliases
           skip-cp threads trace] :as opts}]
  (let [;; tool use - replace :deps / :paths if needed
        tool-args (deps/combine-aliases
                    (deps/merge-edns [install-deps user-deps project-deps config-data]) ;; merge just to get all aliases
                    (concat tool-aliases aliases))
        project-deps (deps/tool project-deps tool-args)

        ;; calc basis
        merge-edn (deps/merge-edns [install-deps user-deps project-deps config-data])
        _ (check-aliases merge-edn (concat resolve-aliases makecp-aliases jvmopt-aliases main-aliases tool-aliases aliases))
        resolve-args (cond-> (deps/combine-aliases merge-edn (concat resolve-aliases aliases))
                       threads (assoc :threads threads)
                       trace (assoc :trace trace))
        cp-args (deps/combine-aliases merge-edn (concat makecp-aliases aliases))
        basis (when-not skip-cp (deps/calc-basis merge-edn :resolve-args resolve-args :classpath-args cp-args))
        trace-log (-> basis :libs meta :trace)

        ;; handle jvm and main opts
        jvm (seq (get (deps/combine-aliases merge-edn (concat jvmopt-aliases aliases)) :jvm-opts))
        main (seq (get (deps/combine-aliases merge-edn (concat main-aliases aliases)) :main-opts))]
    ;; TODO this is just adapting back to the old contract for now, should just return the basis
    (cond-> (merge basis
              {:paths (reduce-kv (fn [ps p why] (cond-> ps (:path-key why) (conj p))) [] (:classpath basis))})
      (not skip-cp) (assoc :cp (-> (:classpath basis) keys deps/join-classpath))
      trace-log (assoc :trace trace-log)
      jvm (assoc :jvm jvm)
      main (assoc :main main))))

(defn read-deps
  [name]
  (when (not (str/blank? name))
    (let [f (jio/file name)]
      (when (.exists f)
        (reader/slurp-deps f)))))

(defn run
  "Run make-classpath script. See -main for details."
  [{:keys [config-user config-project libs-file cp-file jvm-file main-file skip-cp] :as opts}]
  (let [opts' (merge opts {:install-deps (reader/install-deps)
                           :user-deps (read-deps config-user)
                           :project-deps (read-deps config-project)})
        {:keys [libs cp jvm main trace] :as o} (run-core opts')]
    (when trace
      (spit "trace.edn" (binding [*print-namespace-maps* false] (with-out-str (clojure.pprint/pprint trace)))))
    (when-not skip-cp
      (io/write-file libs-file (pr-str libs))
      (io/write-file cp-file cp))
    (if jvm
      (io/write-file jvm-file (str/join " " jvm))
      (let [jf (jio/file jvm-file)]
        (when (.exists jf)
          (.delete jf))))
    (if main
      (io/write-file main-file (str/join " " main))
      (let [mf (jio/file main-file)]
        (when (.exists mf)
          (.delete mf))))))

(defn -main
  "Main entry point for make-classpath script.

  Options:
    --config-user=path - user deps.edn file (usually ~/.clojure/deps.edn)
    --config-project=path - project deps.edn file (usually ./deps.edn)
    --config-data={...} - deps.edn as data (from -Sdeps)
    --libs-file=path - libs cache file to write
    --cp-file=path - cp cache file to write
    --jvm-file=path - jvm opts file to write
    --main-file=path - main opts file to write
    -Rresolve-aliases - concatenated resolve-deps alias names
    -Cmakecp-aliases - concatenated make-classpath alias names
    -Jjvmopt-aliases - concatenated jvm-opt alias names
    -Mmain-aliases - concatenated main-opt alias names
    -Ttool-aliases - concatenated tool alias names
    -Aaliases - concatenated generic alias names

  Resolves the dependencies and updates the lib, classpath, etc files.
  The libs file is at <cachedir>/<hash>.libs
  The cp file is at <cachedir>/<hash>.cp
  The main opts file is at <cachedir>/<hash>.main (if needed)
  The jvm opts file is at <cachedir>/<hash>.jvm (if needed)"
  [& args]
  (try
    (let [{:keys [options errors]} (parse-opts args)]
      (when (seq errors)
        (run! println errors)
        (System/exit 1))
      (run options))
    (catch Throwable t
      (printerrln "Error building classpath." (.getMessage t))
      (when-not (instance? IExceptionInfo t)
        (.printStackTrace t))
      (System/exit 1))))
