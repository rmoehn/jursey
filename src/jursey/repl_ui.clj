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

  (require '[jursey.transcriptor-tools :refer [check-diff!]])

  (transcriptor/run "test/clarification-swallows.repl")

  )

(comment

  (set-up)

  (ask-root "How many [table tennis balls] fit in a [Boeing 787]?")

  (start-working)
  (ask "Is the outer volume of $q.1 much smaller than the inner volume of $q .3?")
  (unlock "sq.0.a")
  (unlock "q.1")
  (unlock "q.3")
  (reply "Yes.")

  (ask "What is the packing density of $q.1?")

  (ask "What is the inner volume of $q.3?")
  (unlock "sq.2.a")

  (unlock "q.1")
  (unlock "r")
  (ask "Given workspace $r.parent and its ancestors, do you think ‘inner volume of a $q.1’ means the volume of the [cabin], the [whole fuselage] or [all hollow space]?")
  ;; Alternatively, one could just get an answer for each option.
  (unlock "sq.0.a")
  (unlock "q.1")
  (unlock "q.1.3")

  ;; At this point the user has to look very sharply to find out which
  ;; pointers likely have the same target.

  (unlock "q.3")
  (ask "When you ask about packing small objects into a $q.3, would you mean packing them in $q.5?")
  (ask "When you ask about packing small objects into a $q.3, would you mean packing them in $q.7?")
  (ask "When you ask about packing small objects into a $q.3, would you mean packing them in $q.9?")

  (unlock "sq.0.a")

  ;; A different root question's sub-workspace coming in…
  (reply "Yes.")
  (reply "I'm lazy.")
  (reply "Everyone refused. It's their fault.")
  (reply "No chance.")

  (unlock "q.1")
  (unlock "q.3")
  (reply "Yes.")

  (unlock "sq.1.a")
  ;; Automation!
  (reply "Yes.")

  (unlock "sq.2.a")
  (reply "No.")

  (reply "The volume of $q.5 or $q.7.")
  (ask "What is the inner volume of the $sq.0.a.1 of a $q.1?")
  (ask "What is the inner volume of the $sq.0.a.3 of a $q.1?")
  (reply (str "Depends on what is meant.\n"
              "$sq.0.a.1: $sq.1.a\n"
              "$sq.0.a.3: $sq.2.a"))




  (reset)
  (ask-root "How long does it take a swallow to cross the Channel between Calais and Dover?")
  (start-working)
  (ask "What is the air-speed velocity of an unladen swallow?")
  (unlock "sq.0.a")
  (unlock "r")
  (ask "For the purposes of $r.parent and possibly its ancestors, do you mean an African or a European swallow?")
  (ask "What is the air-speed velocity of $sq.0.a?")
  (reply "$sq.1.a")
  (unlock "sq.0.a.0")

  (unlock "q.1")

  (unlock "q.1")
  (unlock "q.1.0")
  (reply "European swallow")

  (reply "11-20 m/s, according to [Wikipedia]")



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

  (def ws1 *1)

  (reply "approx. 1.5 kg")

  (unlock "sq.1.a")
  ;; Multi-unlocks would be better for automation here.
  (unlock "q.3")
  (unlock "q.5")
  (ask "1 pound = ? kg")
  (unlock "sq.0.a")
  (ask "What is 20 * 1.5 kg?")

  (def ws2 *1)

  (reply "$sq.1.a")

  (unlock "r")
  (unlock "r.4")
  (ask "Give me a diff of the last versions of $r.4.children.0 and $r.4.children.1.")
  (unlock "sq.2.a")

  (ask "Give me the last version of $q.1.")
  (ask "Give me the last version of $q.3.")
  (ask "Give me a diff of [the question of $sq.0.a] and [the question of $sq.1.a].")

  ;; temporary
  (unlock "sq.2.a")
  (ask "Are $q.1 and $q.3 equal?")
  (unlock "sq.0.a")

  (unlock "q.1")
  (unlock "q.3")
  (ask "Give me the question of $q.1.1.")
  (ask "Give me the question of $q.3.1.")
  (ask "Are $sq.0.a and $sq.1.a equal?")
  (reply "$sq.2.a")

  (unlock "sq.0.a.0")

  (unlock "q.1")
  (unlock "q.1")
  ;; Here it would be useful if I could access q.1.max-v – the actual
  ;; version, not the version number.
  ;; Also, I often accidentally write things like (reply "q.1.5"). In order
  ;; to avoid this confusion, it might be better to change (unlock "…") to
  ;; (unlock "$…")
  (reply "$q.1.5")
  (unlock "q.1.0")
  (reply "$q.1.0.5.ws.q")

  (unlock "q.1.0")
  (unlock "q.3")
  ;; No automation here, because we can't access max-v.
  (reply "$q.1.6")
  ;; Again no automation. Here only one version is displayed, so no matter
  ;; what the version number is, it should be able to just return the
  ;; question. – (reply "$q.1.0.*.ws.q")
  (reply "$q.1.0.6.ws.q")
  (unlock "q.3.0")
  ;; Now I ran into a problem with copying pointers within reflection. – They
  ;; are getting locked, even though they should be unchanged snapshots.
  ;; Wrong. They are now outside a reflection structure – just the question
  ;; hypertexts themselves, so locking them is correct.

  ;; It might be useful if pointer paths themselves could be assembled from
  ;; pointers. $r.{$sq.a.0} where $sq.a.0 is "5", for example.

  )

;; Exercise for the reader: Come up with a more elegant diffing algorithm.
;; clojure.data/diff might be too general to be appropriate.

(defn zip-indexed-qas [qas1 qas2]
  (let [max-i (->> (merge qas1 qas2)
                   keys
                   (map #(Integer/parseInt %))
                   (apply max))]
    (reduce
      (fn [res-so-far i]
        (let [str-i (str i)]
          (assoc res-so-far
            str-i
            [(get qas1 str-i) (get qas2 str-i)])))
      {}
      (range (inc max-i)))))

(defn diff-qas [qas1 qas2]
  (let [i+qa1+qa2s (zip-indexed-qas qas1 qas2)]
    (reduce
      (fn [[in1-so-far in2-so-far in-common-so-far]
           [str-i [{q1 "q" a1 "a"} {q2 "q" a2 "a"}]]]
        [(cond-> in1-so-far
                 (and (not= q1 q2) (some? q1)) (assoc-in [str-i "q"] q1)
                 (and (not= a1 a2) (some? a1)) (assoc-in [str-i "a"] a1))
         (cond-> in2-so-far
                 (and (not= q1 q2) (some? q2)) (assoc-in [str-i "q"] q2)
                 (and (not= a1 a2) (some? a2)) (assoc-in [str-i "a"] a2))
         (cond-> in-common-so-far
                         (= q1 q2)             (assoc-in [str-i "q"] q1)
                         (= a1 a2)             (assoc-in [str-i "a"] a1))])
      [{} {} {}]
      i+qa1+qa2s)))

(defn diff [ws1 ws2]
  (let [q-diff (if (= (get ws1 "q") (get ws2 "q"))
                 [{}                  {}                  {"q" (get ws1 "q")}]
                 [{"q" (get ws1 "q")} {"q" (get ws2 "q")} {}])
        qa-diff (diff-qas (get ws1 "sq") (get ws2 "sq"))]
    (mapv #(assoc %1 "sq" %2) q-diff qa-diff)))

(comment

  (diff ws1 ws2)

  )
