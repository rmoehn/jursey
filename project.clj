(defproject jursey "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [com.datomic/datomic-free "0.9.5703"]
                 [com.rpl/specter "1.1.2"]
                 [instaparse "1.4.9"]
                 [marick/suchwow "6.0.0"]
                 [prismatic/plumbing "0.5.5"]]
  :repl-options {:init-ns jursey.core})
