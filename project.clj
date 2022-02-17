(defproject revert-backtester "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}

  :min-lein-version "2.9.0"

  ;; We need to add src/cljs too, because cljsbuild does not add its
  ;; source-paths to the project source-paths
  :source-paths ["src/clj" "src/cljs"]
  :clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]


  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/clojurescript "1.10.773"
                 :exclusions [com.google.javascript/closure-compiler-unshaded
                              org.clojure/google-closure-library
                              org.clojure/google-closure-library-third-party]]
                 [thheller/shadow-cljs "2.11.26"]
                 [reagent "1.0.0"]
                 [district0x/bignumber "1.0.3"]
                 [cljs-http "0.1.46"]
                 [metasoarous/oz "1.6.0-alpha36"]]

  :plugins [[cider/cider-nrepl "0.25.9"]
            [lein-shadow "0.3.1"]]

  ;;:hooks [leiningen.cljsbuild]


  :shadow-cljs {:nrepl {:port 8778}
                :builds {:app {:target :browser
                               :output-dir "resources/public/js/compiled"
                               :asset-path "/js/compiled"
                               :modules {:app {:init-fn revert-backtester.core/main
                                               :preloads [devtools.preload]}}
                               :compiler-options {:infer-externs :auto
                                                  :cross-chunk-method-motion false
                                                  :optimizations :simple
                                                  :output-feature-set :es8}
                               :js-options {:js-provider :shadow}
                               :devtools {:http-root "resources/public"
                                          :http-port 8280}}}}
  :profiles  {:dev
              {:dependencies [[binaryage/devtools "1.0.2"]]
               :source-paths ["dev"]}

              :prod {}}
  ;;:cljsbuild
  #_{:builds {;; This build is only used for including any cljs source
            ;; in the packaged jar when you issue lein jar command and
            ;; any other command that depends on it
            :revert-backtester
            {:source-paths ["src/cljs"]
             ;; The :jar true option is not needed to include the CLJS
             ;; sources in the packaged jar. This is because we added
             ;; the CLJS source codebase to the Leiningen
             ;; :source-paths
             ;:jar true
             ;; Compilation Options
             :compiler
             {:output-to "dev-resources/public/js/revert_backtester.js"
              :optimizations :advanced
              :pretty-print false}}}})
