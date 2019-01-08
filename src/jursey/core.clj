(ns jursey.core
  [:refer-clojure :rename {get !get get-in !get-in}]
  [:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.stacktrace :as stktr]
            [clojure.string :as string]
            [com.rpl.specter :as s]
            ;; TODO: Don't refer q. (RM 2019-01-08)
            [datomic.api :refer [q] :as d]
            [datomic.api :as d]
            [datomic.api :as d]
            datomic-helpers
            [instaparse.core :as insta]
            [such.imperfection :refer [-pprint-]]]
  [:use [plumbing.core
         :only [safe-get safe-get-in]
         :rename {safe-get get safe-get-in get-in}]])
;; Also uses: datomic.Util

;; ht … hypertext

;;;; Setup
(def --Setup)  ; Workaround for a clearer project structure in IntelliJ.

(defn set-up [reset?]
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

  (set-up true)

  (set-up false)

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
;; Doesn't have to change.

;; Note: I wanted to name this get-ht-tree, but I already used ht-tree for the
;; syntax tree of a parsed hypertext.
;; TODO: Turn !get-in into get-in. (RM 2019-01-04)
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
        q-ht-data (get-ht-data db (get-in pull-res [:ws/question :db/id]))
        ws-data {"q" q-ht-data}]
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

;; MAYBE TODO: Add a check before executing a command that the target
;; workspace is not waiting for another workspace. (RM 2019-01-08)

(defn ask-root-question [conn agent question]
  (d/transact conn
              (concat [{:db/id       "wsid"
                        :ws/question "htid"}
                       {:db/id         [:agent/handle agent]
                        :agent/root-ws "wsid"}]
                      (ht->tx-data {} question))))

(defn wss-to-show
  "Return IDs of workspaces that are waited for, but not waiting for.
  Ie. they should and can be worked on. A workspace can be waited for by another
  workspace, or by an agent if it would answer one of that agent's root
  questions."
  [db]
  (q '[:find [?ws ...]
       :where
       (or [_ :ws/waiting-for ?ws]
           (and [_ :agent/root-ws ?ws]
                (not [?ws :ws/answer _])))
       (not [?ws :ws/waiting-for _])]
     db))

;; TODO: Add a check that all pointers in input hypertext point at things that
;; exist. (RM 2018-12-28)
;; Note: The answer pointer in a QA has no :pointer/name. Not sure if this is
;; alright.
(defn ask [conn wsid bg-data question]
  (let [db (d/db conn)

        qhtdata (ht->tx-data bg-data question)

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
    (d/transact conn final-tx-data)))

(defn unlock [conn wsid wsdata pointer]
  (let [db (d/db conn)
        path (->path pointer :target)
        target (d/pull db '[*] (get-in wsdata path))

        what-to-do
        (cond
          (some? (!get target :hypertext/content))
          [{:db/id           (get-in wsdata (->path pointer :id))
            :pointer/locked? false}]

          (some? (!get target :ws/question))
          [{:db/id          wsid
            :ws/waiting-for (!get target :db/id)}
           {:db/id           (get-in wsdata (->path pointer :id))
            ;; Note: I can already set the pointer to unlocked, because this
            ;; workspace won't be rendered again until the waiting-for is
            ;; cleared. At that point the target will be renderable. This way I
            ;; don't have to find all the pointers with pending unlock after the
            ;; reply is given.
            :pointer/locked? false}]

          :else
          (throw (ex-info "Don't know how to handle this pointer target."
                          {:target target})))

        tx-data
        (concat
          what-to-do

          [{:db/id       "actid"
            :act/command :act.command/unlock
            :act/content pointer}
           {:db/id  "datomic.tx"
            :tx/ws  wsid
            :tx/act "actid"}])]
    (d/transact conn tx-data)))

(defn show-ws [db]
  (let [wsid (first (wss-to-show db))
        ws-data (get-ws-data db wsid)
        ws-str  (render-ws-data ws-data)]
    (def test-wsid wsid)
    (def test-ws-data ws-data)
    [ws-data ws-str]))

(def --Comment)

(comment

  (do
    (ask-root-question conn test-agent "What is the capital of [Texas]?")

    (show-ws (d/db conn))

    ;; Just for testing. Later the root question and the root ws must be separate.
    (let [pid (q '[:find ?p .
                   :in $ ?ws
                   :where
                   [?ws :ws/question ?q]
                   [?q :hypertext/pointer ?p]]
                 (d/db conn) test-wsid)]
      (d/transact conn [{:db/id           pid
                         :pointer/locked? true}])))

  (show-ws (d/db conn))

  (ask conn test-wsid test-ws-data "What is the capital city of $q.1?")

  (show-ws (d/db conn))

  (ask conn test-wsid test-ws-data "What do you think about $sq.0.a?")

  (show-ws (d/db conn))

  (unlock conn test-wsid test-ws-data "sq.0.a")

  (show-ws (d/db conn))

  (unlock conn test-wsid test-ws-data "q.0")

  (show-ws (d/db conn))

  ;; If you've already unlocked sq.0.a, you have to reset before this one,
  ;; because there is no reply yet.
  (unlock conn test-wsid test-ws-data "sq.1.a")

  (show-ws (d/db conn))

  (unlock conn test-wsid test-ws-data "q.0")

  (show-ws (d/db conn))

  ;; Reply "It's a nice city. Once I went to [Clojure/conj] there."

  (unlock conn test-wsid test-ws-data "sq.1.a.0")

  ;;;; Reply – gas phase

  ;; Go from the ws to the placeholder, then from the placeholder to the
  ;; pointers that point at it. Make a copy of the answer for each
  ;; pointer and change the pointers to pointer at their copies. Throw away
  ;; the placeholder. And the sub-ws!

  ;; MAYBE TODO: When a reply is given, it makes sense to retract the workspace
  ;; in which it happens. – Because we don't need it anymore. Nobody will
  ;; look at it. Even reflection will only look at it in an earlier version
  ;; of the database. (Not sure about this.) But :pointer/target can refer to a
  ;; workspace, so :pointer/target cannot be a component attribute, so we'd
  ;; have to manually traverse the tree rooted in the workspace and retract
  ;; all hypertexts. This would take at least an hour to implement.
  ;; Retracting finished workspaces is not crucial, so don't do it for now.
  ;; Do it later. (RM 2019-01-08)

  (let [wsid test-wsid
        wsdata test-ws-data
        answer "Austin"

        db (d/db conn)
        ahtdata (ht->tx-data wsdata answer)

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
        ;; current one is removed.
        unwait-data
        (map (fn [waiting-wsid]
               [:db/retract waiting-wsid :ws/waiting-for test-wsid])
             (d/q '[:find [?waiting-ws ...]
                    :in $ ?this-ws
                    :where [?waiting-ws :ws/waiting-for ?this-ws]]
                  db wsid))

        final-tx-data
        (concat
          [{:db/id wsid
            :ws/answer "htid"}]
          ahtdata

          aht-copy-data
          unwait-data

          [{:db/id       "actid"
            :act/command :act.command/ask
            :act/content answer}
           {:db/id "datomic.tx"
            :tx/ws wsid
            :tx/act "actid"}])]
    (d/transact conn final-tx-data))


  ;;;; Reflection – gas phase

  ;; Find out what the user wants to do with reflection. Derive a small set
  ;; of operations/available pointers etc. to enable that.

  ;;;; Archive

  ;; Example of how the transaction map for a piece of hypertext should look.
  (ask-root-question
    conn test-agent
    "What is the elevation of [Mt Ontake in [Kagoshima] Prefecture]? [-54 m]")
  ;; ↓
  [{:db/id              "htid"
    :hypertext/content  "What is the elevation of $0? $1"
    :hypertext/pointer  [{:pointer/name   "0"
                          :pointer/target  "htid0"
                          :pointer/locked? true}
                         {:pointer/name    "1"
                          :pointer/target  "htid1"
                          :pointer/locked? true}]}
   {:db/id              "htid0"
    :hypertext/content  "Mt Ontake in $0 Prefecture"
    :hypertext/pointer  [{:pointer/name    "0"
                          :pointer/target  "htid0.0"
                          :pointer/locked? true}]}
   {:db/id              "htid0.0"
    :hypertext/content  "Kagoshima"}
   {db/id               "htid1"
    :hypertext/content  "-54 m"}]

  (ask-root-question conn test-agent "What is 4 + 5?")

  ;; Example of what I'm not going to support. One can't refer to input/path
  ;; pointers, only to output/number pointers. This is not a limitation, because
  ;; input pointers can only refer to something that is already in the workspace.
  ;; So one can just refer to that directly.
  {"q"  "What is the capital of $0?"
   "sq" {"0" {"q" "What is the capital city of &q.0?"
              "a" :locked}
         "1" {"q" "What is the population of &sq.0.q.&(q.0)"}}}

  ;;;; Attic

  ;; Note: I'm using (db conn) only for this interactive exploration. Functions
  ;; should only receive the result of (db conn), not conn.
  (q '[:find ?text
       :where
       [?ws :ws/question ?ht]
       [?ht :hypertext/content ?text]]
     (db conn))

  ;; Search for a waited-for workspace.
  (q '[:find [?ws ...]
       :where
       [_ :ws/proc ?p]
       [?p :ws.proc/waiting-for ?ws]]
     (db conn))


  ;; If there are no waited-for workspaces, we look for a workspace that isn't
  ;; a sub-workspace and must therefore be a root workspace. We show that one.
  (def ws-id (q '[:find ?ws .
                  :where
                  [?ws :ws/content]
                  (not [_ :ws.content/sub-ws ?ws])]
                (db conn)))

  (def ws-content-id
    (:db/id (:ws/content (d/pull (db conn) '[{:ws/content [:db/id]}] ws-id))))

  ;; I probably want to build an interface around this. (see also code
  ;; annotated with Interface!)
  (d/pull (db conn) '[{:ws/content [{:ws.content/question [:hypertext/content]}]}] ws-id)

  (defn ws-data [db ws-id]
    (d/pull db
            '[{:ws/content [{:ws.content/question [:hypertext/content]
                             :ws.content/answer   [:hypertext/content]}
                            :ws.content/sub-ws]
               :ws/proc    [{:ws.proc/state [:db/ident]} :ws.proc/waiting-for]}]
            ws-id))


  ;; TODO: Find and assemble all information for the workspace to be shown.
  ;; TODO: Render the workspace.

  ;;;; Answer the question

  ;; MAYBE TODO: Add the ID of the agent that took the action to the
  ;; transaction metadata.

  ;; Is state/retired <-> workspace has an answer?
  ;; https://cjohansen.no/annotating-datomic-transactions/
  (def first-answer
    [{:db/id             "ahtid"
      :hypertext/content "600"}
     {:db/id      ws-id
      :ws/content {:db/id             ws-content-id
                   :ws.content/answer "ahtid"}
      :ws/proc    {:ws.proc/state :ws.proc.state/retired}}
     {:db/id             "acthtid"
      :hypertext/content "600"}
     {:db/id       "actid"
      :act/command :act.command/reply
      :act/content "acthtid"}
     {:db/id  "datomic.tx"
      :tx/ws  ws-id
      :tx/act "actid"}])

  @(d/transact conn first-answer)


  ;;;; Play around

  ;; Find out whether there is any workspace that needs to be shown.
  ;; Those are the ones that someone is waiting for or that belong to the root
  ;; question.
  ;; Similar to before. Actually how I find the workspace earlier should look
  ;; like this.
  (q '[:find [?ws ...]
       :where
       [?ws :ws/content ?c]
       (not [_ :ws.content/sub-ws ?ws])
       (not [?c :ws.content/answer _])]
     (db conn))

  (d/tx->t 13194139534325)

  ;; Identify the root question and its answer to show them to the agent.
  (q '[:find ?q ?a
       :where
       [?u :agent/handle "test"]
       [?u :agent/root-ws ?ws]
       [?ws :ws/content ?c]
       [?c :ws.content/answer ?aht]
       [?c :ws.content/question ?qht]
       [?aht :hypertext/content ?a]
       [?qht :hypertext/content ?q]]
     (db conn))
  ;; From this we get all questions that the agents has ever asked and their
  ;; answers. How should this actually be? What would the agent want to see?
  ;; Only the most recently answered questions?
  ;; Patchwork so far has runs from root question to root answer. How would I
  ;; do this in Jursey? – I could just keep the ID of the root ws until the end.



  ;; TODO: Should we attach agent/agent IDs to the actions for introspection?
  ;; (Not reflection.)

  ;;;; Reflect

  ;; NEXT: Transact the scenario with the pictures that I sent to Andreas. Then
  ;; reflect.

  ;; TODO: The agent wants to know how the root question came about. Were there
  ;; any subquestions asked?
  ;; - The Datomic log contains all data on what action caused what
  ;;   database changes. The question is whether we can easily extract the
  ;;   information required for reflection from that.
  ;; The model here is a little different from Patchwork. Think about what the
  ;; user wants to see. It might be different for different actions.
  ;; - reply: What can happen? Main ws acquires an answer. Other wss
  ;;   might stop waiting for the main ws. The latter shouldn't show up in
  ;;   reflection, so it's only ws before – action – ws after.
  ;; - ask: What happens? Main ws acquires a sub-ws. Again ws before
  ;;   action – ws after.
  ;; - unlock: What happens? Main ws is set to waiting-for the
  ;;   sub-ws. This is like pushing the sub-ws on the stack?
  ;; - We get into a mix-up of history and stack? – It's two views.
  ;;   - how does this fit together with the graph idea? See my notes on paper.


  ;;;; Next steps

  ;; If time is limited, skip the probably easy stuff and go for the things
  ;; with the most expected problems.

  ;; TODO: Do it with automation. – That means, every time we have a new
  ;; workspace, we have to go through the whole history to see whether there
  ;; was an equivalent workspace, then find the action taken from there. This
  ;; is an important test for my model. Not sure if it will get through it.
  ;; Need to find all actions from transactions in history,  the source ws of
  ;; those actions, (render it) and compare it with the current ws. Have to
  ;; make sure that the ws wasn't later retracted maybe.



  ;; First: Find all actions in history and the workspace state before.
  ;; The following gives me workspaces and actions that were taken from there
  ;; (entity IDs only). Using the ?tx and d/as-of I can find out how the
  ;; workspace looked at that time. So this is no problem. If we decide that we
  ;; want to be able to retract workspaces, we can check whether one with ID
  ;; ?ws exists in the present database before looking in the as-of database.
  (q '[:find ?tx ?ws ?a
       :in $ ?log
       :where
       [?tx :tx/ws ?ws]
       [?tx :tx/act ?a]]
     (db conn)
     (d/log conn))

  ;)

  ;; TODO: Do the whole thing again with actual pointers.
  ;; TODO: Do it another time with sub-questions.
  ;; TODO: Implement the event system around it.

  (defn ask-root [conn agent content]
    (d/transact conn
                [{:db/id         [:agent/handle agent]
                  :agent/root-ws "wsid"}
                 {:db/id             "qhtid"
                  :hypertext/content content}
                 {:db/id      "wsid"
                  :ws/content {:ws.content/question "qhtid"}
                  :ws/proc    {:ws.proc/state :ws.proc.state/pending}}]))

  ;(ask-root conn "test" "What is 51 * 5019")


  ;; TODO: Factor out a function that creates the transaction data for actions.
  (defn ask [conn ws-id content]
    (let [ws-cont-id (q '[:find ?c .
                          :in $ ?ws
                          :where [?ws :ws/content ?c]]
                        (db conn) ws-id)]
      (d/transact conn
                  [{:db/id             "qhtid"
                    :hypertext/content content}
                   {:db/id             ws-cont-id
                    :ws.content/sub-ws "new-wsid"}
                   {:db/id      "new-wsid"
                    :ws/content {:ws.content/question "qhtid"}
                    :ws/proc    {:ws.proc/state :ws.proc.state/pending}}
                   {:db/id  "datomic.tx"
                    :tx/ws  ws-id
                    :tx/act "actid"}
                   {:db/id             "acthtid"
                    :hypertext/content content}
                   {:db/id       "actid"
                    :act/command :act.command/ask
                    :act/content "acthtid"}])))

  ; TODO: Put the waiting-for in here. But changed. We need wss that are
  ; waited-for, but not waiting for.
  (defn wss-to-show [db]
    (let [non-waiting-root-wss
          (q '[:find [?ws ...]
               :where
               [?ws :ws/content ?c]
               (not [_ :ws.content/sub-ws ?ws])
               (not [?c :ws.content/answer _])
               [?ws :ws/proc ?p]
               (not [?p :ws.proc/waiting-for _])]
             db)
          waited-for-other-wss
          (q '[:find [?ws ...]
               :where
               [_ :ws/proc ?p]
               [?p :ws.proc/waiting-for ?ws]
               [?ws :ws/proc ?subp]
               (not [?subp :ws.proc/waiting-for _])]
             db)]
      (concat non-waiting-root-wss waited-for-other-wss)))



  ;; Unlocking an answer, ie. unlocking a sub-ws is easy. But how would I do
  ;; this in general?
  ;; When I create a ws, I also add an answer with hypertext that contains only
  ;; a pointer? But that would complicate queries.
  ;; When I unlock a pointer and the pointer points to an answer that hasn't
  ;; yet been given… But an answer that hasn't been given doesn't exist. So the
  ;; pointer has nothing to point to. It could point to [ws :answer]. That
  ;; would be like a pull spec and if there is no result, it means that the
  ;; answer doesn't yet exist, so if I unlock it, I have to set the ws to
  ;; waited-for.
  ;; TODO: Think about the unlocking of fulfilled and unfulfilled pointers.
  (defn unlock-answer [conn sub-ws-id]
    (let [[ws-id ws-proc-id]
          (q '[:find [?ws ?p]
               :in $ ?sub-ws
               :where
               [?ws :ws/content ?c]
               [?c :ws.content/sub-ws ?sub-ws]
               [?ws :ws/proc ?p]]
             (db conn) sub-ws-id)]
      (d/transact conn
                  [{:db/id               ws-proc-id
                    :ws.proc/waiting-for sub-ws-id}
                   {:db/id       "actid"
                    :act/command :act.command/unlock
                    :act/content "acthtid"}
                   {:db/id             "acthtid"
                    :hypertext/content sub-ws-id} ; This is not right.
                   {:db/id  "datomic.tx"
                    :tx/ws  ws-id
                    :tx/act "actid"}])))

  (def cur-ws (first (wss-to-show (db conn))))

  (ask conn cur-ws "What is 50 * 5019?")

  (ask conn cur-ws "What is 50?")



  (wss-to-show (db conn))

  (d/transact conn
              (map (fn [wsid] [:db.fn/retractEntity wsid])
                   (q '[:find [?ws ...]
                        :where [?ws :ws/content _]]
                      (db conn))))

  (let [cur-db (db conn)
        cur-ws (first (wss-to-show cur-db))]
    [(wss-to-show cur-db) (map #(ws-data cur-db %)
                               (wss-to-show cur-db))])



  (unlock-answer conn 17592186045433)


  (d/pull (db conn)
          '[:ws/content]
          (first (wss-to-show (db conn))))

  (d/basis-t (db conn))

  ;; NEXT: Figure out pointers.


  ;;;; Rendering a workspace

  (def ws-id (q '[:find ?ws .
                  :where [?ws :ws/content _]]
                (db conn)))

  (ws-data (db conn) ws-id)


  (type (into (array-map) (map (fn [n] [n :b]) (range 50))))


  (into (array-map) [[:question 1] [:subquestion 5] [:answer 3]])


  ;; I could also generate such data with spec.
  (def render-data {:q     {:text  "Which $1 went to zoo $2?"
                            1 {:text "monkey $1"
                               :p->id {1 :id1.1}}
                            :p->id {1 :id1
                                    2 :id2}}
                    :sq0   {:q     {:text  "…"
                                    :p->id {}}
                            :a     {:text  "…"
                                    :p->id {}}
                            :p->id {:q :idsq0.q :a :idsq0.a}}
                    :p->id {:q   :idq
                            :sq0 :idsq0}})


  ;; THAT WAS SIMPLE! X-|
  (->> render-data
       (s/transform (s/walker :text) :text)
       (s/setval (s/walker #(= % :p->id)) s/NONE))

  (def to-lookup (->> render-data
                      (s/setval (s/walker #(= % :text)) s/NONE)))


  [:ask "Bla $[:q]"]

  (s/select [:p->id :q] to-lookup)

  [:ask "Bla $[:q 1]"]

  (s/select [:q :p->id 1] to-lookup)

  [:ask "Bla $[:sq0 :a]"]

  (s/select [:sq0 :p->id :a] to-lookup)

  [:unlock [:sq0 :a]]

  (s/select [:sq0 :p->id :a] to-lookup)

  (let [db (db conn)
        ]
    {})

  ;; NEXT: Implement something that takes the complete map and returns the
  ;; to-show data. Then implement something that takes the complete map and
  ;; returns the to-lookup data. Then try different unlock and point to
  ;; patterns to make sure this kind of navigation works. It will be
  ;; complicated further by reflection. At that point I'll need recursive
  ;; rendering.

  [:ask "How about $[q 1] and $[sq a"]

  (d/pull (db conn) '[:hypertext/content] 17592186045427)


  ;; TODO: Add the pointer stuff.
  (defn render-ht [db ht-id]
    {:text  (:hypertext/content (d/entity db ht-id))
     :p->id {}})

  (defn render-sq [db sq-id] sq-id)

  ;; Now I have a problem! There is no answer yet, so there is no answer
  ;; hypertext yet, so I can't pass around a pointer to it, because I don't
  ;; have a pointer. This is a corner case, isn't it?
  ;; So I could introduce a second type of pointer that points to workspaces
  ;; and which indicates the answer of that workspace, whether it exists or not.
  ;; It would be like [:unlock-answer :sq0], "Look at $a[sq0].".


  ;; Unfulfilled answers are a special case anyway. Two somewhat awkward
  ;; solutions for now:
  ;; - When a pointer points to a workspace, it means that workspace's not yet
  ;;   given answer. We can put that for :a in the :p->id map.
  ;; - Depending on the workspace they're shown in, pointers have to have
  ;;   different string representations.
  ;;   → Pointer numbering is the business of the workspace?
  ;;   - What about locked/unlocked state? I wanted to make copies for that?
  ;;   - Maybe the Patchwork way to separate that from the pointers and the
  ;;     hypertext is right.
  ;;   - One could also view it as information hiding, though. We show a ws to
  ;;     the user with locked pointers. If she needs more information, she
  ;;     requests another version of the workspace with some of the information
  ;;     uncovered.
  ;;   - But how does this play with the concept of a tree of wss?

  ;; - If I don't want to always treat reflection as an afterthought, can I make
  ;;   it first class? Do I also get (immutable) edits for free then?
  ;;   Because the user can visit any part of the computational forest and make
  ;;   alternate histories.
  ;; - The actual user interaction would be on top of that, I guess.


  (let [db      (db conn)
        sq-id   17592186045433
        ws-data (d/pull db
                        '[{:ws/content [:ws.content/question :ws.content/answer]}]
                        ws-id)
        q-ht-id (get-in ws-data [:ws/content :ws.content/question :db/id])
        a-ht-id (get-in ws-data [:ws/content :ws.content/answer :db/id])
        ]
    ws-data
    {:q     (render-ht db q-ht-id)
     :a     a-ht-id
     :p->id {:q q-ht-id}})

  ;; Pointers within a question etc. are numbered local to that hypertext. So
  ;; unlock would look like [:unlock :q 1].

  (let [db        (db conn)
        ; Interface!
        ws-data   (d/pull db
                          '[{:ws/content [:ws.content/question :ws.content/sub-ws]}]
                          ws-id)
        q-ht-id   (get-in ws-data [:ws/content :ws.content/question :db/id])
        sq-ws-ids (s/select [:ws/content :ws.content/sub-ws s/ALL :db/id] ws-data)
        sq-keys   (into (sorted-map)
                        (map-indexed (fn [i sq-ws-id]
                                       [(keyword (str "sq" i)) sq-ws-id])
                                     sq-ws-ids))
        to-keep   (assoc sq-keys :q q-ht-id)
        entries   (concat [:q (render-ht db q-ht-id)]
                          (flatten (for [[k sq-ws-id] sq-keys]
                                     [k (render-sq db sq-ws-id)]))
                          [:p->id to-keep])
        to-show   (apply array-map entries)]
    to-show)

  )


;; Sometimes I make the mistake to refer to a tempid that is not defined
;; anywhere in the transaction. Datomic's error message is uninformative,
;; so this can help to find what I forgot or misspelled.
(comment

  (let [present-tempids (set (s/transform (s/walker :db/id) :db/id trx-data))]
    (pprint/pprint present-tempids)
    (pprint/pprint (set (s/select (s/walker #(contains? present-tempids %))
                    ;(constantly nil)
                    trx-data))))

  )
