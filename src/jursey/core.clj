(ns jursey.core
  [:refer-clojure :rename {get !get get-in !get-in}]
  [:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.stacktrace :as stktr]
            [clojure.string :as string]
            [com.rpl.specter :as s]
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

;; TODO: Support escaped brackets and dollar signs.
(def parse-ht
  (insta/parser
    "S        = chunks
     <chunks> = chunk*
     <chunk>  = text | pointer | embedded
     text     = #'[^\\[\\]$]+'
     embedded = <'['> chunks <']'>
     pointer  = <'$'> #'\\w[\\w\\d.]+'"))

(defn ->path [pointer & relpath]
  (into (string/split pointer #"\.") relpath))

(defn process-pointer [bg-data pointer]
  (let [path (->path pointer)]
    {:repr    (str \$ pointer)
     :pointer {:pointer/name    pointer
               :pointer/locked? (get-in bg-data (conj path :locked?))
               :pointer/target  (get-in bg-data (conj path :target))}}))
;; ✔ SKETCH
;; Here we would have to put a :pointer if the target exists, a :placeholder if
;; it doesn't. Or would the :placeholder/answering-ws be in there as target?
;; What is the target for? When I ask a question or reply with a pointer $q.1, it
;; allows me to find out what $q.1 is pointing at, so I can include the same
;; reference in the new hypertext.
;;
;; Now: We always put the target, because it always exists. It can be hypertext
;; or a placeholder.
;;
;; I just saw: q, sq.0.q etc. don't have a target, they have an :id. I'd have to
;; unify this in order to make the above function work.

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
(defn replace-occurences
  "Replace occurrences of the keys of `m` in `s` with the corresponding vals. "
  [s m]
  (reduce-kv string/replace s m))

(defn str-idx-map
  "[x y z] → {“0” (f x) “1” (f y) “2” (f z)}
  Curved quotation marks substitute for straight ones."
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
    (replace-occurences (get ht-data :text) pointer->text)))
;; Doesn't have to change.

;; Note: I wanted to name this get-ht-tree, but I already used ht-tree for the
;; syntax tree of a parsed hypertext.
;; TODO: Turn !get-in into get-in. (RM 2019-01-04)
(defn get-ht-data [db id]
  (let [ht (d/pull db '[*] id)]
    (into {:text    (get ht :hypertext/content)
           :target  id}
          (map (fn [p]
                 [(get p :pointer/name)
                  (if (get p :pointer/locked?)
                    {:locked? true
                     :target  (!get-in p [:pointer/target :db/id])}
                    (get-ht-data db (get-in p [:pointer/target :db/id])))])
               (get ht :hypertext/pointer)))))
;; ✔ SKETCH: Here I can also just put the target.
;; And I have to change :id to :target, because in a sense the hypertext is
;; the target of the q or sq.1.q etc, even though they aren't actual pointers
;; in the database. They can't be locked.

(defn get-sub-qa-data [db
                       {{q-htid :db/id} :qa/question
                        {apid :db/id}   :qa/answer}]
  {"q" (get-ht-data db q-htid)
   "a" (let [{locked? :pointer/locked?
              {target :db/id} :pointer/target} (d/pull db '[*] apid)]
         (if locked?
           {:locked? true
            :target target}
           (get-ht-data db target)))})
;; ✔ SKETCH In the locked case we still put the target.

(defn render-sub-qa-data [qa-data]
  {"q" (render-ht-data (get qa-data "q"))
   "a" (if (get-in qa-data ["a" :locked?])
         :locked
         (render-ht-data (get qa-data "a")))})
;; Must stay the same.

(defn get-ws-data [db id]
  (let [pull-res  (d/pull db '[*] id)
        q-ht-data (get-ht-data db (get-in pull-res [:ws/question :db/id]))
        ws-data {"q" q-ht-data}]
    (if-some [sq (get pull-res :ws/sub-qa)]
      (assoc ws-data "sq" (str-idx-map #(get-sub-qa-data db %) sq))
      ws-data)))

;; TODO: Add sq only if there is a sub-question.
(defn render-ws-data [ws-data]
  {"q"  (render-ht-data (get ws-data "q"))
   ;; TODO: Use map-vals here. (RM 2018-12-28)
   "sq" (into {} (map (fn [[k v]] [k (render-sub-qa-data v)]) (get ws-data "sq")))})

;;;; Copying hypertext
(def --Hypertext-copying)

(declare pull-cp-hypertext-data)

(defn pull-cp-pointer-data [db
                            {id :db/id
                             target :pointer/target
                             locked :pointer/locked?
                             :as pointer-map}]
  ;; SKETCH Here I need to look if the :pointer/target is
  ;; the ID of a workspace. If it is a workspace, I don't
  ;; apply pull-cp-hypertext-data to it, but just return
  ;; the ID. Before that I will factor this part out as a
  ;; function.
  ;; How do I know if it is a workspace? A workspace always has a
  ;; question, so I can identify it by that.
  ;; TODO: Test whether it actually retains the target. (RM 2019-01-07)
  (let [pull-res (d/pull db '[*] (get target :db/id))]
    (pprint/pprint pull-res)
    (cond
      (some? (!get pull-res :hypertext/content))
      (-> pointer-map
          (dissoc :db/id)
          (assoc :pointer/target
                 (pull-cp-hypertext-data
                   db
                   (get-in pointer-map
                           ;; ↓
                           [:pointer/target :db/id]))))

      (some? (!get pull-res :ws/question))
      (dissoc pointer-map :db/id)

      :else (throw (ex-info "Don't know how to handle this pointer target."
                            {:target pull-res})))))

(defn pull-cp-hypertext-data [db id]
  (let [pull-res
        (d/pull db '[*] id)

        ;_ (pprint/pprint pull-res)

        sub-ress
        (mapv #(pull-cp-pointer-data db %)
              (!get pull-res :hypertext/pointer []))]
    (-> pull-res
        (dissoc :db/id)
        (assoc :hypertext/pointer sub-ress))))

;;;; Core API
(def --Core-API)

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
(defn ask [conn wsid bg-data question]
  (let [tx-data (concat
                  [{:db/id     wsid
                    :ws/sub-qa "qaid"}
                   {:db/id       "qaid"
                    :qa/question "htid"
                    :qa/answer   "apid"}
                   {:db/id           "apid"
                    :pointer/locked? true}
                   {:db/id       "actid"
                    :act/command :act.command/ask
                    :act/content "htid"}
                   {:db/id  "datomic.tx"
                    :tx/ws  wsid
                    :tx/act "actid"}]
                  (ht->tx-data bg-data question))]
    (d/transact conn tx-data)))
;; Add a ws and make it the target of the pointer with apid.
;; Uh, now I have to implement the copying of the question here.
;; I will implement a general dbht->tx-data here.


(defn show-ws [db id]
  (let [ws-data (get-ws-data db id)
        ws-str  (render-ws-data ws-data)]
    (def test-ws-data ws-data)
    [ws-data ws-str]))

;(defn pull-cp-hypertext-data [db id])


(comment

  (do
    (ask-root-question conn test-agent "What is the capital of [Texas]?")

    (def test-wsid (first (wss-to-show (d/db conn))))

    ;; Just for testing. Later the root question and the root ws must be separate.
    (let [pid (q '[:find ?p .
                   :in $ ?ws
                   :where
                   [?ws :ws/question ?q]
                   [?q :hypertext/pointer ?p]]
                 (d/db conn) test-wsid)]
      (d/transact conn [{:db/id           pid
                         :pointer/locked? true}])))

  (show-ws (d/db conn) test-wsid)

  (ask conn test-wsid test-ws-data "What is the capital city of $q.1?")

  (ask conn test-wsid test-ws-data "What do you think about $sq.0.a?")



  ;; Note: For now I don't worry about tail recursion and things like that.
  ;; TODO: Handle all cases of what a pointer can point to. So far it is only
  ;; hypertext. (RM 2019-01-04)
  ;; TODO: Find some sensible semantics/way to deal with get and friends.
  ;; – The current semantics is like Python with get = __getitem__ and !get =
  ;; get. This is quite okay. I just have to find better names, I guess. (RM
  ;; 2019-01-04)

  ;; TODO: Pull in the code for datomic-helpers/translate-value, so that I
  ;; have control over it (RM 2019-01-04).
  (letfn [
          ]
    (let [db (d/db conn)

          [copied-ht-tempid tx-data]
          (@#'datomic-helpers/translate-value
            (pull-cp-hypertext-data (d/db conn)
                                    (get-in test-ws-data (->path "sq.0.q" :target))))

          {db :db-after :as tx-result}
          (d/with db tx-data)

          copied-ht-id
             (d/resolve-tempid db (get tx-result :tempids) copied-ht-tempid)]
      [tx-data
       (@#'datomic-helpers/translate-value (pull-cp-hypertext-data db copied-ht-id))
       (d/pull db '[*] copied-ht-id)])
    )

  ;; Assumptions:
  ;; - (nil? :pointer/target) implies :pointer/locked?.
  ;; - Unlock can only be called if the workspace is not waiting for another.
  ;; - A sub-workspace belongs to exactly one workspace.
  ;; - Sub-workspaces are only created when the user attempts to unlock the
  ;;   respective sub-question.
  ;; - When the user replies, the answer is copied to the parent workspace and
  ;;   the target filled.
  ;; - From these it follows that when we want to unlock a pointer and it has
  ;;   no target, no sub-workspace exists yet.
  (let [db (d/db conn)
        wsid test-wsid
        wsdata test-ws-data
        pointer "sq.0.a"

        question->tx-data
        (fn question->tx-data [db htid])


        sub-ws-txdata
        (fn sub-ws-txdata [db wsid sqdata]
          ;; data for the question ht
          ;; sub-workspace itself
          ;; transaction data
          ;; connection between sub-workspace and parent
          )]
    (let [path (->path pointer)
          pinfo (get-in wsdata path)]
      (if (and (get pinfo :locked?) (nil? (get pinfo :target)))
        (sub-ws-txdata db wsid (get-in wsdata (conj (vec (butlast path)) "q")))
        )
      ))
  ;; Now there is always a :target. – Either a hypertext or a ws.




  (unlock conn test-wsid test-ws-data "$sq.0.a")

  ;;;; Reply – gas phase

  ;; Go from the ws to the placeholder, then from the placeholder to the
  ;; pointers that point at it. Make a copy of the answer for each
  ;; pointer and change the pointers to pointer at their copies. Throw away
  ;; the placeholder. And the sub-ws!

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
