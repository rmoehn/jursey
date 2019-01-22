(defproject jursey "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url  "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/core.async "0.4.490"]
                 [com.cognitect/REBL "0.9.109"]
                 [com.datomic/datomic-free "0.9.5703"]
                 [com.rpl/specter "1.1.2"]
                 [datomic-helpers "1.0.0"]
                 [instaparse "1.4.9"]
                 [marick/suchwow "6.0.0"]

                 ;; For REBL. Credits:
                 ;; - https://github.com/cognitect-labs/REBL-distro/issues/7#issuecomment-445291074
                 ;; - https://github.com/openjfx/samples/blob/master/HelloFX/Maven/hellofx/pom.xml
                 [org.openjfx/javafx-base "11"]
                 [org.openjfx/javafx-controls "11"]
                 [org.openjfx/javafx-fxml "11"]
                 [org.openjfx/javafx-swing "11"]
                 [org.openjfx/javafx-web "11"]

                 [prismatic/plumbing "0.5.5"]]
  :repl-options {:init-ns jursey.core})
