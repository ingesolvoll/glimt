(defproject glimt "0.0.1-SNAPSHOT"
  :description "re-frame HTTP FSM"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :url "https://github.com/ingesolvoll/glimt"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.773"]
                 [re-frame "1.1.1"]
                 [day8.re-frame/http-fx "v0.2.0"]
                 [clj-statecharts "0.0.1-SNAPSHOT"]]

  :plugins [[com.jakemccrary/lein-test-refresh "0.24.1"]]
  :deploy-repositories [["clojars" {:sign-releases false
                                    :url           "https://clojars.org/repo"
                                    :username      :env/clojars_username
                                    :password      :env/clojars_password}]]

  :source-paths ["src"]
  :aliases {"deploy!" ["do" ["test"] ["deploy" "clojars"]]})
