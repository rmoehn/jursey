(ns jursey.core
  [:refer-clojure :rename {get !get get-in !get-in}]
  [:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stacktrace]
            [clojure.string :as string]
            [com.rpl.specter :as s]
            [datomic.api :as d]
            datomic-helpers
            [instaparse.core :as insta]
            [such.imperfection :refer [-pprint-]]]
  [:use [plumbing.core
         :only [safe-get safe-get-in]
         :rename {safe-get get safe-get-in get-in}]])
;; Also uses: datomic.Util

;; ht   … hypertext
;; pid  … pointer entity ID
;; pmap … pointer map


;;;; Setup
(def --Setup)  ; Workaround for a clearer project structure in IntelliJ.

(declare test-agent)
(declare conn)

(defn set-up [{:keys [reset?]}]
  (def test-agent "test")

  (let [base-uri "datomic:free://localhost:4334/"
        db-name  "jursey"
        db-uri   (str base-uri db-name)]

    (if-not reset?
      (def conn (d/connect db-uri))

      (do (when (some #{db-name} (d/get-database-names (str base-uri "*")))
            (d/delete-database db-uri))

          (d/create-database db-uri)
          (def conn (d/connect db-uri))

          (with-open [rdr (io/reader "src/jursey/schema.edn")]
            (d/transact conn (datomic.Util/readAll rdr)))

          (d/transact conn [{:agent/handle test-agent}])))))

(comment

  (set-up {:reset? true})

  (set-up {:reset? false})

  )


;;;; Hypertext string → transaction map
(def --Hypertext-parsing)

(def pointer-re #"(?xms)
                  \$
                  (
                    \w+
                    (?: [.] \w+ )*
                  )")

;; TODO: Support escaped brackets and dollar signs.
(def parse-ht
  (insta/parser
    (format
      "S        = chunks
       <chunks> = chunk*
       <chunk>  = text | pointer | embedded
       text     = #'[^\\[\\]$]+'
       embedded = <'['> chunks <']'>
       pointer  = #'%s'"
      pointer-re)))

(defn ->path [pointer & relpath]
  (into (string/split pointer #"\.") relpath))

(defn process-pointer [bg-data dollar-pointer]
  (let [pointer (apply str (rest dollar-pointer))
        path (->path pointer)]
    {:repr    dollar-pointer
     :pointer {:pointer/name    pointer
               :pointer/locked? (get-in bg-data (conj path :locked?))
               :pointer/target  (get-in bg-data (conj path :target))}}))

(declare ht-tree->tx-data)

(defn tmp-htid
  "Return a temporary :db/id for the transaction map of a piece of hypertext."
  [loc]
  (str "htid" (string/join \. loc)))

;; TODO: Document this.
;; TODO: Find a better name for bg-data and children-data.
(defn process-embedded [bg-data loc children]
  (let [children-data (map-indexed (fn [i c]
                                     (ht-tree->tx-data bg-data
                                                       (conj loc i)
                                                       c))
                                   children)]
    {:repr    (str \$ (last loc))
     :pointer {:pointer/name    (str (last loc))
               :pointer/target  (tmp-htid loc)
               :pointer/locked? false}
     :tx-data (conj
                (apply concat (filter some? (map :tx-data children-data)))
                {:db/id             (tmp-htid loc)
                 :hypertext/content (apply str (map :repr children-data))
                 :hypertext/pointer (filter some? (map :pointer children-data))})}))

(defn ht-tree->tx-data [bg-data loc [tag & children]]
  "
  `loc` is the location/path of the current element in the syntax tree."
  (case tag
    ;; :repr is the string that will be included in the parent hypertext.
    :text     {:repr (first children)}
    :pointer  (process-pointer bg-data (first children))
    :embedded (process-embedded bg-data loc children)))

;; TODO: Fix the grammar so we don't have to turn :S into a fake :embedded.
(defn ht->tx-data [bg-data ht]
  (get (ht-tree->tx-data bg-data [] (assoc (parse-ht ht) 0 :embedded))
       :tx-data))


;;;; Rendering a workspace as a string
(def --Hypertext-rendering)

;; TODO: Think about whether this can produce wrong substitutions.
;; (RM 2018-12-27)
;; Note: This has (count m) passes. Turn it into a one-pass algorithm if necessary.
(defn replace-occurrences
  "Replace occurrences of the keys of `m` in `s` with the corresponding vals. "
  [s m]
  (reduce-kv string/replace s m))

(defn str-idx-map
  "[x y z] → {“0” (f x) “1” (f y) “2” (f z)}
  Curved quotation marks substitute for straight ones in this docstring."
  [f s]
  (into {} (map-indexed (fn [i v] [(str i) (f v)]) s)))

(defn render-ht-data [ht-data]
  (let [name->ht-data (apply dissoc ht-data (filter keyword? (keys ht-data)))
        pointer->text (into {} (map (fn [[name embedded-ht-data]]
                                      [(str \$ name)
                                       (if (get embedded-ht-data :locked?)
                                         (str \$ name)
                                         (format "[%s: %s]" name (render-ht-data embedded-ht-data)))])
                                    name->ht-data))]
    (replace-occurrences (get ht-data :text) pointer->text)))

;; Note: I wanted to name this get-ht-tree, but I already used ht-tree for the
;; syntax tree of a parsed hypertext.
;; TODO: Turn get-in into sget-in and !get-in into get-in. (RM 2019-01-04)
(defn get-ht-data [db id]
  (let [ht (d/pull db '[*] id)]
    (into {:text    (get ht :hypertext/content)
           :target  id
           :locked? false}
          (map (fn [p]
                 [(get p :pointer/name)
                  (if (get p :pointer/locked?)
                    {:id (get p :db/id)
                     :locked? true
                     :target  (!get-in p [:pointer/target :db/id])}
                    (get-ht-data db (get-in p [:pointer/target :db/id])))])
               (!get ht :hypertext/pointer [])))))

(defn get-sub-qa-data [db
                       {{q-htid :db/id} :qa/question
                        {apid :db/id}   :qa/answer}]
  {"q" (get-ht-data db q-htid)
   "a" (let [{pid :db/id
              locked? :pointer/locked?
              {target :db/id} :pointer/target} (d/pull db '[*] apid)]
         (if locked?
           {:id pid
            :locked? true
            :target target}
           (get-ht-data db target)))})

(defn render-sub-qa-data [qa-data]
  {"q" (render-ht-data (get qa-data "q"))
   "a" (if (get-in qa-data ["a" :locked?])
         :locked
         (render-ht-data (get qa-data "a")))})

(defn get-ws-data [db id]
  (let [pull-res  (d/pull db '[*] id)
        ws-data {"q" (some->> (!get-in pull-res [:ws/question :db/id])
                              (get-ht-data db))}]
    (if-some [sq (!get pull-res :ws/sub-qa)]
      (assoc ws-data "sq" (str-idx-map #(get-sub-qa-data db %) sq))
      ws-data)))

;; TODO: Add sq only if there is a sub-question.
(defn render-ws-data [ws-data]
  {"q"  (render-ht-data (get ws-data "q"))
   ;; TODO: Use map-vals here. (RM 2018-12-28)
   "sq" (into {} (map (fn [[k v]] [k (render-sub-qa-data v)])
                      (!get ws-data "sq")))})


;;;; Copying hypertext
(def --Hypertext-copying)

(declare pull-cp-hypertext-data)

;; Note: This locks all the pointer copies, because that's what I need when I
;; copy a hypertext to another workspace. Adapt if you need faithfully copied
;; locked status somewhere.
(defn pull-cp-pointer-data [db
                            {{target-id :db/id} :pointer/target}
                            new-name]
  ;; TODO: Test whether it actually retains the target. (RM 2019-01-07)
  (let [pull-res
        (d/pull db '[*] target-id)

        new-target
        (cond
          (some? (!get pull-res :hypertext/content))
          (pull-cp-hypertext-data db target-id)

          (some? (!get pull-res :ws/question))
          target-id

          :else (throw (ex-info "Don't know how to handle this pointer target."
                                {:target pull-res})))]
    {:pointer/name new-name
     :pointer/target new-target
     :pointer/locked? true}))

(defn map-keys-vals [f m]
  (into {} (map (fn [[k v]] [(f k) (f v)]) m)))

;; TODO: Change to Derek's semantics where each occurrence of the same
;; pointer gets its own copy. Implementing that would take half an hour that
;; I don't want to take now. (RM 2019-01-07)
;; TODO: Find some sensible semantics/way to deal with get and friends.
;; – The current semantics is like Python with get = __getitem__ and !get =
;; get. This is quite okay. I just have to find better names, I guess. (RM
;; 2019-01-04)
;; Note: For now I don't worry about tail recursion and things like that.
(defn pull-cp-hypertext-data [db id]
  (let [pull-res
        (d/pull db '[*] id)

        old->new-pointer
        (into {} (map-indexed #(vector (get %2 :pointer/name) (str %1))
                              (!get pull-res :hypertext/pointer)))

        sub-ress
        (mapv #(pull-cp-pointer-data db % (get old->new-pointer
                                               (get % :pointer/name)))
              (!get pull-res :hypertext/pointer []))]
    (-> pull-res
        (dissoc :db/id)
        (assoc :hypertext/pointer sub-ress)
        (assoc :hypertext/content
               (replace-occurrences (get pull-res :hypertext/content)
                                    (map-keys-vals #(str \$ %) old->new-pointer))))))

;; TODO: Pull in the code for datomic-helpers/translate-value, so that I
;; have control over it (RM 2019-01-04).
(defn cp-hypertext-tx-data [db id]
  (@#'datomic-helpers/translate-value (pull-cp-hypertext-data db id)))


;;;; Core API
(def --Core-API)

;; Invariants/rules:
;; - Never show a waiting workspace to the user (in fact, never call
;;   get-ws-data on a waiting workspace).
;; - Only show a workspaces to the user if it is waited for.
;; - Workspaces that :agent/root-ws refers to have no :ws/question. All other
;;   workspaces have a :ws/question.

;; MAYBE TODO: Check before executing a command that the target
;; workspace is not waiting for another workspace. (RM 2019-01-08)

(defn wss-to-show
  "Return IDs of workspaces that are waited for, but not waiting for.
  Ie. they should and can be worked on. A workspace can be waited for by another
  workspace, or by an agent if it would answer one of that agent's root
  questions."
  [db]
  (d/q '[:find [?ws ...]
       :where
       [_ :ws/waiting-for ?ws]
       (not [_ :agent/root-ws ?ws])
       (not [?ws :ws/waiting-for _])]
     db))

;; TODO: Add a check that all pointers in input hypertext point at things that
;; exist. (RM 2018-12-28)
;; Note: The answer pointer in a QA has no :pointer/name. Not sure if this is
;; alright.
(defn ask [db wsid bg-data question]
  (let [qhtdata (ht->tx-data bg-data question)

        {:keys [db-after tempids]}
        (d/with db qhtdata)

        [ht-copy-tempid ht-copy-tx-data]
        (cp-hypertext-tx-data db-after
                              (d/resolve-tempid db-after tempids "htid"))

        final-tx-data
        (concat
          [{:db/id     wsid
            :ws/sub-qa "qaid"}]

          qhtdata
          [{:db/id       "qaid"
            :qa/question "htid"
            :qa/answer   "apid"}
           {:db/id           "apid"
            :pointer/locked? true
            :pointer/target  "sub-wsid"}]

          ht-copy-tx-data
          [{:db/id       "sub-wsid"
            :ws/question ht-copy-tempid}

           {:db/id       "actid"
            :act/command :act.command/ask
            :act/content question}
           {:db/id  "datomic.tx"
            :tx/ws  wsid
            :tx/act "actid"}])]
    final-tx-data))

(defn- unlock-by-pointer-map [db wsid {target-id :target pid :id} pointer]
  (let [target (d/pull db '[*] target-id)

        set-waiting-data
        (if (!get target :ws/question) ; Must be a pointer to an ungiven answer.
          [{:db/id          wsid
            :ws/waiting-for (!get target :db/id)}]
          [])

        tx-data
        (concat
          set-waiting-data

          ;; Note: I can set the pointer to unlocked even if it's pointing to an
          ;; ungiven answer, because this workspace won't be rendered again
          ;; until the waiting-for is cleared. At that point the target will be
          ;; renderable. This way I don't have to find all the pointers with
          ;; pending unlock after the reply is given.
          [{:db/id           pid
            :pointer/locked? false}

           {:db/id       "actid"
            :act/command :act.command/unlock
            :act/content pointer}
           {:db/id  "datomic.tx"
            :tx/ws  wsid
            :tx/act "actid"}])]
    tx-data))

;; TODO: Check that the pointer is actually locked.
(defn unlock [db wsid wsdata pointer]
  (unlock-by-pointer-map db wsid (get-in wsdata (->path pointer)) pointer))

;; MAYBE TODO: When a reply is given, it makes sense to retract the workspace
;; in which it happens. Because we don't need it anymore. Nobody will look at
;; it. Even reflection will only look at it in an earlier version of the
;; database. (Not sure about this.) But :pointer/target can refer to a
;; workspace, so :pointer/target cannot be a component attribute, so we'd have
;; to manually traverse the tree rooted in the workspace and retract all
;; hypertexts. This would take at least an hour to implement. Retracting
;; finished workspaces is not crucial, so don't do it for now. Do it later. (RM
;; 2019-01-08)
(defn reply [db wsid wsdata answer]
    (let [ahtdata (ht->tx-data wsdata answer)

          {:keys [db-after tempids]} (d/with db ahtdata)
          aht-tempid (d/resolve-tempid db-after tempids "htid")

          targeting-pids
          (d/q '[:find [?p ...]
                 :in $ ?wsid
                 :where
                 [?p :pointer/target ?wsid]]
               db wsid)

          aht-copy-data
          (mapcat (fn [pid]
                    (let [[aht-copy-tempid aht-copy-tx-data]
                          (cp-hypertext-tx-data db-after aht-tempid)]
                      (conj
                        aht-copy-tx-data
                        {:db/id          pid
                         :pointer/target aht-copy-tempid})))
                  targeting-pids)

          ;; TODO: Make sure that if it's waiting for multiple wss, only the
          ;; current one is removed. I'm not sure about the semantics of
          ;; retract. (RM 2019-01-08)
          unwait-data
          (map (fn [waiting-wsid]
                 [:db/retract waiting-wsid :ws/waiting-for wsid])
               (d/q '[:find [?waiting-ws ...]
                      :in $ ?this-ws
                      :where [?waiting-ws :ws/waiting-for ?this-ws]]
                    db wsid))

          final-tx-data
          (concat
            [{:db/id     wsid
              :ws/answer "htid"}]
            ahtdata

            aht-copy-data
            unwait-data

            [{:db/id       "actid"
              :act/command :act.command/ask
              :act/content answer}
             {:db/id  "datomic.tx"
              :tx/ws  wsid
              :tx/act "actid"}])]
      final-tx-data))


;;;; Single-user runner
(def --Runner)

;; Note: This is becoming ugly with transacts at all levels. But I think I can
;; tidy it up when I'm working on the third milestone. Also, maybe I should
;; abstract all the query stuff.

(def last-shown-wsid (atom nil))

;; TODO: Turn this into a function that returns data to be transacted by
;; someone else, just like the rest of the core API. (RM 2019-01-10)
(defn run-ask-root-question [conn agent question]
  (let [ask-data
        (concat
          (ask (d/db conn) "wsid" {} question)

          [{:db/id "wsid"} ; Empty ws that just gets a qa from `ask`.
           {:db/id         [:agent/handle agent]
            :agent/root-ws "wsid"}])

        {:keys [db-after tempids]}
        @(d/transact conn ask-data)

        wsid (d/resolve-tempid db-after tempids "wsid")

        unlock-data
        (unlock db-after wsid (get-ws-data db-after wsid) "sq.0.a")]
    (d/transact conn unlock-data)))

;; MAYBE TODO: Change the unlock, so that it goes through the same route as
;; all other unlocks. Ie. it uses sq.0.a.* instead of the pointer map
;; directly. For this I'd have to find or write a walker that gives me not
;; just nodes, but also their paths. (RM 2019-01-10)
(defn- pull-root-qa [conn wsid]
  (let [db-at-start (d/db conn)
        question (-> (get-ws-data db-at-start wsid)
                     (get-in ["sq" "0" "q"])
                     render-ht-data)]
    (loop [db db-at-start]
      (if (!get (d/entity db wsid) :ws/waiting-for)
        [question :waiting]
        (let [wsdata      (get-ws-data db wsid)
              locked-pmap (s/select-first (s/walker :locked?) wsdata)]
          (if (nil? locked-pmap) ; No locked pointers left.
            [question (render-ht-data (get-in wsdata ["sq" "0" "a"]))]
            (do @(d/transact conn
                             (unlock-by-pointer-map
                               db wsid locked-pmap (str locked-pmap)))
                (recur (d/db conn)))))))))

(defn pull-root-qas [conn agent]
  (let [db (d/db conn)
        finished-wsids
           (d/q '[:find [?ws ...]
                  :in $ ?handle
                  :where
                  [?a :agent/handle ?handle]
                  [?a :agent/root-ws ?ws]
                  (not [?ws :ws/waiting-for _])]
                db agent)]
    (map #(pull-root-qa conn %) finished-wsids)))

(defn start-working [conn]
  (let [db   (d/db conn)
        wsid (first (wss-to-show db))]
    (swap! last-shown-wsid (constantly wsid))
    (render-ws-data (get-ws-data db wsid))))

(defn run [[cmd arg :as command] & [{:keys [trace?]}]]
  (when trace?
    (pprint/pprint command))
  (let [cmd-fn (get {:ask ask :unlock unlock :reply reply} cmd)
        wsid @last-shown-wsid
        db (d/db conn)
        tx-data (cmd-fn db wsid (get-ws-data db wsid) arg)

        _ (d/transact conn tx-data)
        db (d/db conn)
        new-wsid (first (wss-to-show db))

        _ (swap! last-shown-wsid (constantly new-wsid))

        res (when new-wsid (render-ws-data (get-ws-data db new-wsid)))]
    (when trace?
      (pprint/pprint res))
    res))


(def --Comment)

(comment

  ;;;; Scenario: Pointer 1

  ;; Challenges: Replying in a root workspace with a pointer to a yet ungiven
  ;; sub-answer.

  (set-up {:reset? true})
  (run-ask-root-question conn test-agent "What is the capital of [Texas]?")

  (start-working conn)
  (run [:ask "What is the capital city of $q.0?"])
  (run [:reply "Just $sq.0.a."])

  (pull-root-qas conn test-agent)

  (start-working conn)
  (run [:reply "Austin. Keep it [weird]."])

  (pull-root-qas conn test-agent)


  ;;;; Scenario: Pointer 2

  ;; Challenges: Asking a sub-question that contains a pointer to a yet ungiven
  ;; answer to another sub-question.

  (set-up {:reset? true})
  (run-ask-root-question conn test-agent "What is the capital of [Texas]?")

  (start-working conn)
  (doseq [command
          [[:ask "What is the capital city of $q.0?"]
           [:ask "What do you think about $sq.0.a?"]
           [:unlock "sq.1.a"]
           [:unlock "q.0"]
           [:reply "Austin"]
           [:reply "It's a nice city. Once I went to [Clojure/conj] there."]
           [:unlock "sq.1.a.0"]
           [:reply "It is Austin. $sq.1.a.0 happened there once."]]]
    (run command {:trace? true})
    (println))

  (pull-root-qas conn test-agent)


  ;;;; Reflection – gas phase

  ;; Find out what the user wants to do with reflection. Derive a small set of
  ;; operations/available pointers etc. to enable that.


  ;;;; Archive

  ;; Example of what I'm not going to support. One can't refer to input/path
  ;; pointers, only to output/number pointers. This is not a limitation, because
  ;; input pointers can only refer to something that is already in the workspace.
  ;; So one can just refer to that directly.
  {"q"  "What is the capital of $0?"
   "sq" {"0" {"q" "What is the capital city of &q.0?"
              "a" :locked}
         "1" {"q" "What is the population of &sq.0.q.&(q.0)"}}}

)

(def --Tools)

;; Sometimes I make the mistake to refer to a tempid that is not defined
;; anywhere in the transaction. Datomic's error message is uninformative,
;; so this can help to find what I forgot or misspelled.
(comment

  (let [present-tempids (set (s/transform (s/walker :db/id) :db/id trx-data))]
    (pprint/pprint present-tempids)
    (pprint/pprint (set (s/transform (s/walker #(contains? present-tempids %))
                                     (constantly nil)
                                     trx-data))))

  )
