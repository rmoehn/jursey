(ns jursey.repl-ui
  "Single-user runner"
  [:require [clojure.java.io :as io]
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

  ;; Don't forget to save test/scenarios.repl!
  (do (require '[cognitect.transcriptor :as transcriptor :refer [check!]])
      (transcriptor/run "test/scenarios.repl")
      (transcriptor/run "test/repl_ui.repl"))

  )

(comment

  (reset)

  (ask-root "The root question doesn't matter.")
  (start-working)
  (ask "What is the mass of [1] [three-pound loaf] in [kg]?")
  (ask "What is the mass of [20] [three-pound loaf] in [kg]?")
  (unlock "sq.0.a")

  (unlock "q.1")
  (unlock "q.3")
  (unlock "q.5")
  (ask "1 pound = ? kg")
  (unlock "sq.0.a")
  (reply "approx. 0.5")
  (reply "approx. 1.5 kg")

  (unlock "sq.1.a")
  ;; Multi-unlocks would be better for automation here.
  (unlock "q.3")
  (unlock "q.5")
  (ask "1 pound = ? kg")
  (unlock "sq.0.a")
  (ask "What is 20 * 1.5 kg?")
  (reply "$sq.1.a")

  (unlock "r")
  (unlock "r.4")
  (ask "Give me a diff of the last versions of $r.4.children.0 and $r.4.children.1.")
  (unlock "sq.2.a")

  (ask "Give me the last version of $q.1.")
  (ask "Give me the last version of $q.3.")
  (ask "Are [the question of $sq.0.a] and [the question of $sq.1.a] equal?")
  (ask "Zip [the sub-qas of $sq.0.a] and [$sq.1.a].")

  )

(defn diff [ws1 ws2]
  (let [q-diff (if (= (get ws1 "q") (get ws2 "q"))
                 [{}                  {}                  {"q" (get ws1 "q")}]
                 [{"q" (get ws1 "q")} {"q" (get ws2 "q")} {}])]))

(comment

  )
