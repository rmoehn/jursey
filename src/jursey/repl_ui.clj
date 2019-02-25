(ns jursey.repl-ui
  "Single-user runner"
  [:require [clojure.java.io :as io]
            [cognitect.transcriptor :as transcriptor :refer [check!]]
            [datomic.api :as d]
            [jursey.core :as j]])

;; Make these available for auto-completion.
(declare test-agent)
(declare conn)

(defn- init [{:keys [reset?]}]
  (def test-agent "test")

  (let [base-uri "datomic:free://localhost:4334/"
        db-name "jursey"
        db-uri (str base-uri db-name)]
    (if-not reset?
      (def conn (d/connect db-uri))

      (do (when (some #{db-name} (d/get-database-names (str base-uri "*")))
            (d/delete-database db-uri))

          (d/create-database db-uri)
          (def conn (d/connect db-uri))

          (with-open [rdr (io/reader "src/jursey/schema.edn")]
            @(d/transact conn (datomic.Util/readAll rdr)))
          @(d/transact conn [{:agent/handle test-agent}])))))

(def ^:private last-shown-wsid (atom nil))

(defn set-up []
  (init {:reset? false}))

(defn reset []
  (init {:reset? true}))

(defn ask-root [q]
  (j/run-ask-root-question conn test-agent q))

(defn start-working []
  (let [[wsid wsstr] (j/automate-where-possible conn)]
    (reset! last-shown-wsid wsid)
    wsstr))

(defn run [action]
  (let [[wsid wsstr] (j/run conn @last-shown-wsid action)]
    (reset! last-shown-wsid wsid)
    wsstr))

(defn ask [q]
  (run [:ask q]))

(defn unlock [p]
  (run [:unlock p]))

(defn reply [a]
  (run [:reply a]))

(defn get-root-qas []
  (j/get-root-qas conn test-agent))

(comment

  ;; Don't forget to save the REPL file!
  (do (transcriptor/run "test/scenarios.repl")
      (transcriptor/run "test/repl_ui.repl")
      (transcriptor/run "test/clarification-swallows.repl")
      (transcriptor/run "test/clarification-airplane.repl"))

  )
