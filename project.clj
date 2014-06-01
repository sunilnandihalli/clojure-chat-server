(defproject chat-server "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [org.clojure/tools.cli "0.3.1"]
                 [clj-time "0.7.0"]
                 [potemkin "0.3.4"]
                 [throttler "1.0.0"]
                 [beckon "0.1.1"]
                 [aleph "0.3.2"]]
                                        ;:main ^:skip-aot chat-server.core
  :main chat-server.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
