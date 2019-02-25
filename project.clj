(defproject jursey "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [com.cognitect/transcriptor "0.1.5"]
                 [com.datomic/datomic-free "0.9.5703"]
                 [datomic-helpers "1.0.0"]
                 [instaparse "1.4.9"]
                 [prismatic/plumbing "0.5.5"]]

  :plugins [[mvxcvi/whidbey "2.0.0"]]
  :middleware [whidbey.plugin/repl-pprint]
  :repl-options {:init-ns jursey.repl-ui})
