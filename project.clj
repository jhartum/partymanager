(defproject partymanager "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/tools.logging "1.2.4"]
                 [org.clojure/clojure "1.11.1"]
                 [metosin/malli "0.13.0"]
                 [ring/ring-core "1.9.6"]
                 [ring/ring-jetty-adapter "1.9.6"]
                 [ring/ring-json "0.5.1"]
                 [ring/ring-defaults "0.3.4"]
                 [clj-http "3.12.3"]
                 [compojure "1.7.0"]
                 [clj-time "0.15.2"]
                 [environ "1.2.0"]
                 [cheshire "5.11.0"]
                 [org.clojure/core.async "1.6.673"]
                 [org.java-websocket/Java-WebSocket "1.5.4"]
                 [http-kit "2.7.0"]
                 [org.slf4j/slf4j-api "2.0.7"]
                 [org.slf4j/slf4j-simple "2.0.7"]]
  :repl-options {:init-ns partymanager.core}
  :main partymanager.core
  :plugins [[lein-environ "1.2.0"]]
  :profiles {:test {:dependencies [[ring/ring-mock "0.4.0"]
                                   [clj-http-fake "1.0.3"]]
                    :resource-paths ["test/resources"]
                    :env {:telegram-token "test-token"
                          :app-base-url "http://localhost:3000"}}})
