(ns jursey.core
  [:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stacktrace]
            [clojure.string :as string]
            [com.rpl.specter :as s]
            [datomic.api :as d]
            datomic-helpers
            [instaparse.core :as insta]
            [plumbing.core :as plumbing]
            [such.imperfection :refer [-pprint-]]]
  [:use [plumbing.core
         :only [safe-get safe-get-in]
         :rename {safe-get sget safe-get-in sget-in}]])
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

(defn ->path [pointer & relative-path]
  (into (string/split pointer #"\.") relative-path))

(defn process-pointer [wsdata dollar-pointer]
  (let [pointer (apply str (rest dollar-pointer))
        path (->path pointer)]
    {:repr    dollar-pointer
     :pointer {:pointer/name    pointer
               :pointer/locked? (sget-in wsdata (conj path :locked?))
               :pointer/target  (sget-in wsdata (conj path :target))}}))

;; Note: The Datomic docs say that it ‘represents transaction requests as
;; data structures’. So I call such a data structures (list of
;; lists or maps) a ‘transaction request’ and its parts ‘transaction parts’.
;; There are also the nested data structures that I turn into a transaction
;; request with datomic-helpers/translate-value. These I call ‘transaction
;; trees’.
(declare process-httree)

(defn tmp-htid
  "Return a temporary :db/id for the transaction map of a piece of hypertext."
  [loc]
  (str "htid" (string/join \. loc)))

;; TODO: Document this.
;; TODO: Find a better name for wsdata and children-data.
(defn process-embedded [wsdata loc children]
  (let [children-data (map-indexed (fn [i c]
                                     (process-httree wsdata
                                                     (conj loc i)
                                                     c))
                                   children)]
    {:repr    (str \$ (last loc))
     :pointer {:pointer/name    (str (last loc))
               :pointer/target  (tmp-htid loc)
               :pointer/locked? false}
     :txreq (conj
              (apply concat (filter some? (map :txreq children-data)))
              {:db/id             (tmp-htid loc)
               :hypertext/content (apply str (map :repr children-data))
               :hypertext/pointer (filter some? (map :pointer children-data))})}))

(defn process-httree [wsdata loc [tag & children]]
  "
  `loc` is the location/path of the current element in the syntax tree.
  ‘httree’ means the syntax tree that results from parsing hypertext."
  (case tag
    ;; :repr is the string that will be included in the parent hypertext.
    :text     {:repr (first children)}
    :pointer  (process-pointer wsdata (first children))
    :embedded (process-embedded wsdata loc children)))

(defn ht->txreq [wsdata ht]
  (let [;; TODO: Fix the grammar so we don't have to turn :S into a fake :embedded.
        httree (replace {:S :embedded} (parse-ht ht))]
    (-> (process-httree wsdata [] httree)
        (sget :txreq))))


;;;; Rendering a workspace as a string
(def --Hypertext-rendering)

;; TODO: Think about whether this can produce wrong substitutions.
;; (RM 2018-12-27)
;; Note: This has (count m) passes. Turn it into a one-pass algorithm if necessary.
(defn replace-substrings
  "Replace occurrences of the keys of `m` in `s` with the corresponding vals. "
  [s m]
  (reduce-kv string/replace s m))

(defn string-indexed-map
  "[x y z] → {“0” (f x) “1” (f y) “2” (f z)}
  Curved quotation marks substitute for straight ones in this docstring."
  [f xs]
  (into {} (map-indexed (fn [i v]
                          [(str i) (f v)])
                        xs)))

(defn render-htdata [htdata]
  (let [name->htdata
        (apply dissoc htdata (filter keyword? (keys htdata)))
        pointer->text
        (into {} (map (fn [[name embedded-htdata]]
                        [(str \$ name)
                         (if (sget embedded-htdata :locked?)
                           (str \$ name)
                           (format "[%s: %s]" name
                                   (render-htdata embedded-htdata)))])
                      name->htdata))]
    (replace-substrings (sget htdata :text) pointer->text)))

(defn get-htdata [db id]
  (let [ht (d/pull db '[*] id)]
    (into {:text    (sget ht :hypertext/content)
           :target  id
           :locked? false}
          (map (fn [p]
                 [(sget p :pointer/name)
                  (if (sget p :pointer/locked?)
                    {:id (sget p :db/id)
                     :locked? true
                     :target  (get-in p [:pointer/target :db/id])}
                    (get-htdata db (sget-in p [:pointer/target :db/id])))])
               (get ht :hypertext/pointer [])))))

(defn get-qadata [db
                  {{q-htid :db/id} :qa/question
                   {apid :db/id}   :qa/answer}]
  {"q" (get-htdata db q-htid)
   "a" (let [{pid :db/id
              locked? :pointer/locked?
              {target :db/id} :pointer/target} (d/pull db '[*] apid)]
         (if locked?
           {:id pid
            :locked? true
            :target target}
           (get-htdata db target)))})

(defn render-qadata [qadata]
  {"q" (render-htdata (sget qadata "q"))
   "a" (if (sget-in qadata ["a" :locked?])
         :locked
         (render-htdata (sget qadata "a")))})

(defn get-wsdata [db id]
  (let [{{qid :db/id} :ws/question
         sub-qas :ws/sub-qa}
        (d/pull db '[*] id)]
    {"q"  (some->> qid (get-htdata db))
     "sq" (string-indexed-map #(get-qadata db %) sub-qas)}))

(defn render-wsdata [wsdata]
  {"q"  (render-htdata (sget wsdata "q"))
   "sq" (plumbing/map-vals #(render-qadata %)
                           (get wsdata "sq"))})


;;;; Copying hypertext
(def --Hypertext-copying)

(declare get-cp-hypertext-txtree)

;; Note: This locks all the pointer copies, because that's what I need when I
;; copy a hypertext to another workspace. Adapt if you need faithfully copied
;; locked status somewhere.
(defn get-cp-pointer-txtree [db
                             {{target-id :db/id} :pointer/target}
                             new-name]
  ;; TODO: Test whether it actually retains the target. (RM 2019-01-07)
  (let [target
        (d/pull db '[*] target-id)

        new-target
        (cond
          (some? (get target :hypertext/content))
          (get-cp-hypertext-txtree db target-id)

          (some? (get target :ws/question))
          target-id

          :else (throw (ex-info "Don't know how to handle this pointer target."
                                {:target target})))]
    {:pointer/name new-name
     :pointer/target new-target
     :pointer/locked? true}))

(defn map-keys-vals [f m]
  (into {} (map (fn [[k v]]
                  [(f k) (f v)])
                m)))

;; TODO: Change to Derek's semantics where each occurrence of the same
;; pointer gets its own copy. Implementing that would take half an hour that
;; I don't want to take now. (RM 2019-01-07)
;; Note: For now I don't worry about tail recursion and things like that.
(defn get-cp-hypertext-txtree [db id]
  (let [htdata
        (d/pull db '[*] id)

        orig->anon-pointer
        (into {} (map-indexed (fn [i pmap]
                                [(sget pmap :pointer/name) (str i)])
                              (get htdata :hypertext/pointer)))

        pointer-txtrees
        (mapv (fn [pmap]
                (->> (sget pmap :pointer/name)
                     (sget orig->anon-pointer)
                     (get-cp-pointer-txtree db pmap)))
              (get htdata :hypertext/pointer []))]
    (-> htdata
        (dissoc :db/id)
        (assoc :hypertext/pointer pointer-txtrees)
        (assoc :hypertext/content
               (replace-substrings (sget htdata :hypertext/content)
                                   (map-keys-vals #(str \$ %) orig->anon-pointer))))))

;; TODO: Pull in the code for datomic-helpers/translate-value, so that I
;; have control over it (RM 2019-01-04).
(defn cp-hypertext-txreq [db id]
  (@#'datomic-helpers/translate-value (get-cp-hypertext-txtree db id)))


;;;; Core API
(def --Core-API)

;; Invariants/rules:
;; - Never show a waiting workspace to the user (in fact, never call
;;   get-wsdata on a waiting workspace).
;; - Only show a workspace to the user if it is waited for.
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
(defn ask [db wsid wsdata question]
  (let [qht-txreq (ht->txreq wsdata question)

        {:keys [db-after tempids]}
        (d/with db qht-txreq)

        ;; Note: Both when a question is asked and when an answer is given,
        ;; two copies of it will be created: One to be stored in the current
        ;; workspace and one with locked pointers to be stored in the child
        ;; (for questions) or parent (for answers) workspace. In fact, when
        ;; an answer is given, it will be copied for each pointer that points
        ;; at it.
        [qht-copy-tempid qht-copy-txreq]
        (cp-hypertext-txreq db-after
                              (d/resolve-tempid db-after tempids "htid"))

        final-txreq
                (concat
                  [{:db/id     wsid
                    :ws/sub-qa "qaid"}]

                  qht-txreq
                  [{:db/id       "qaid"
                    :qa/question "htid"
                    :qa/answer   "apid"}
                   {:db/id           "apid"
                    :pointer/locked? true
                    :pointer/target  "sub-wsid"}]

                  qht-copy-txreq
                  [{:db/id       "sub-wsid"
                    :ws/question qht-copy-tempid}

                   {:db/id       "actid"
                    :act/command :act.command/ask
                    :act/content question}
                   {:db/id  "datomic.tx"
                    :tx/ws  wsid
                    :tx/act "actid"}])]
    final-txreq))

(defn- unlock-by-pointer-map [db wsid {target-id :target pid :id} pointer]
  (let [target (d/pull db '[*] target-id)

        set-waiting-txreq
        (if (get target :ws/question) ; Must be a pointer to an ungiven answer.
          [{:db/id          wsid
            :ws/waiting-for (get target :db/id)}]
          [])

        txreq
        (concat
          set-waiting-txreq

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
    txreq))

;; TODO: Check that the pointer is actually locked.
(defn unlock [db wsid wsdata pointer]
  (unlock-by-pointer-map db wsid (sget-in wsdata (->path pointer)) pointer))

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
  (let [aht-txreq (ht->txreq wsdata answer)

        {:keys [db-after tempids]} (d/with db aht-txreq)
        aht-tempid (d/resolve-tempid db-after tempids "htid")

        targeting-pids
        (d/q '[:find [?p ...]
               :in $ ?wsid
               :where
               [?p :pointer/target ?wsid]]
             db wsid)

        ;; Make a copy of the answer for each pointer that points at it.
        aht-copy-data
        (mapcat (fn [pid]
                  (let [[aht-copy-tempid aht-copy-txreq]
                        (cp-hypertext-txreq db-after aht-tempid)]
                    (conj
                      aht-copy-txreq
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

        final-txreq
        (concat
          [{:db/id     wsid
            :ws/answer "htid"}]
          aht-txreq

          aht-copy-data
          unwait-data

          [{:db/id       "actid"
            :act/command :act.command/ask
            :act/content answer}
           {:db/id  "datomic.tx"
            :tx/ws  wsid
            :tx/act "actid"}])]
    final-txreq))


;;;; Single-user runner
(def --Runner)

;; Notes:
;; - This is becoming ugly with transacts at all levels. But I think I can
;;   tidy it up when I'm working on the third milestone.
;; - Also, maybe I should abstract all the query stuff.
;; - I might have to throw in some derefs to make sure that things are
;;   happening in the right order.

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
             (unlock db-after wsid (get-wsdata db-after wsid) "sq.0.a")]
    (d/transact conn unlock-data)))

;; MAYBE TODO: Change the unlock, so that it goes through the same route as
;; all other unlocks. Ie. it uses sq.0.a.* instead of the pointer map
;; directly. For this I'd have to find or write a walker that gives me not
;; just nodes, but also their paths. (RM 2019-01-10)
(defn- get-root-qa [conn wsid]
  (let [db-at-start (d/db conn)
        question (-> (get-wsdata db-at-start wsid)
                     (sget-in ["sq" "0" "q"])
                     render-htdata)]
    (loop [db db-at-start]
      (if (get (d/entity db wsid) :ws/waiting-for)
        [question :waiting]
        (let [wsdata      (get-wsdata db wsid)
              locked-pmap (s/select-first (s/walker :locked?) wsdata)]
          (if (nil? locked-pmap) ; No locked pointers left.
            [question (render-htdata (sget-in wsdata ["sq" "0" "a"]))]
            (do @(d/transact conn
                             (unlock-by-pointer-map
                               db wsid locked-pmap (str locked-pmap)))
                (recur (d/db conn)))))))))

(defn get-root-qas [conn agent]
  (let [db (d/db conn)
        finished-wsids
           (d/q '[:find [?ws ...]
                  :in $ ?handle
                  :where
                  [?a :agent/handle ?handle]
                  [?a :agent/root-ws ?ws]
                  (not [?ws :ws/waiting-for _])]
                db agent)]
    (map #(get-root-qa conn %) finished-wsids)))

(defn start-working [conn]
  (let [db   (d/db conn)
        wsid (first (wss-to-show db))]
    (swap! last-shown-wsid (constantly wsid))
    (render-wsdata (get-wsdata db wsid))))

(defn run [[cmd arg :as command] & [{:keys [trace?]}]]
  (when trace?
    (pprint/pprint command))
  (let [cmd-fn (sget {:ask ask :unlock unlock :reply reply} cmd)
        wsid @last-shown-wsid
        db (d/db conn)
        txreq (cmd-fn db wsid (get-wsdata db wsid) arg)

        _ (d/transact conn txreq)
        db (d/db conn)
        new-wsid (first (wss-to-show db))

        _ (swap! last-shown-wsid (constantly new-wsid))

        res (when new-wsid (render-wsdata (get-wsdata db new-wsid)))]
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

  (get-root-qas conn test-agent)

  (start-working conn)
  (run [:reply "Austin. Keep it [weird]."])

  (get-root-qas conn test-agent)


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

  (get-root-qas conn test-agent)


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
