(ns jursey.core
  [:require [clojure.java.io :as io]
            [com.rpl.specter :as s]
            [datomic.api :refer [q db] :as d]
            [datomic.api :as d]
            [datomic.api :as d]])
;; Also uses: datomic.Util

;;;; Setup

(defn set-up []
  (let [base-uri "datomic:free://localhost:4334/"
        db-name  "jursey"
        db-uri   (str base-uri db-name)]

    (when (some #{db-name} (d/get-database-names (str base-uri "*")))
      (d/delete-database db-uri))

    (d/create-database db-uri)
    (def conn (d/connect db-uri)))

  (with-open [rdr (io/reader "src/jursey/schema.edn")]
    (d/transact conn (datomic.Util/readAll rdr)))

  (def test-agent "test")

  (d/transact conn [{:agent/handle test-agent}]))




(comment

;;;; Ask a question

(def first-question
  [{:db/id [:agent/handle "test"]
    :agent/root-ws "wsid"}
   {:db/id "qhtid"
    :hypertext/content "What is 20 * 30?"}
   {:db/id "wsid"
    :ws/content {:ws.content/question "qhtid"}
    :ws/proc {:ws.proc/state :ws.proc.state/pending}}])

;@(d/transact conn first-question)
 
  (set-up)

;;;; Play around



;; Note: I'm using (db conn) only for this interactive exploration. Functions
;; should only receive the result of (db conn), not conn.
(q '[:find ?text
     :where
     [?ws :ws/content ?c]
     [?c :ws.content/question ?ht]
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
  [{:db/id "ahtid"
    :hypertext/content "600"}
   {:db/id ws-id
    :ws/content {:db/id ws-content-id
                 :ws.content/answer "ahtid"}
    :ws/proc {:ws.proc/state :ws.proc.state/retired}}
   {:db/id "acthtid"
    :hypertext/content "600"}
   {:db/id "actid"
    :act/command :act.command/reply
    :act/content "acthtid"}
   {:db/id "datomic.tx"
    :tx/ws ws-id
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
                 {:db/id "datomic.tx"
                  :tx/ws ws-id
                  :tx/act "actid"}
                 {:db/id "acthtid"
                  :hypertext/content content}
                 {:db/id "actid"
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
                [{:db/id ws-proc-id
                  :ws.proc/waiting-for sub-ws-id}
                 {:db/id "actid"
                  :act/command :act.command/unlock
                  :act/content "acthtid"}
                 {:db/id "acthtid"
                  :hypertext/content sub-ws-id}             ; This is not right.
                 {:db/id "datomic.tx"
                  :tx/ws ws-id
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
(def render-data {:q     {:text  "Which [1: monkey] went to zoo $2?"
                          :p->id {1 :id1
                                  2 :id2}}
                  :sq0   {:q     {:text  "…"
                                  :p->id {}}
                          :a     {:text  "…"
                                  :p->id {}}
                          :p->id {:q :idsq0.q :a :idsq0.a}}
                  :p->id {:q  :idq
                          :sq0 :idsq0}})


;; THAT WAS EASY! X-|
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
  {:text (:hypertext/content (d/entity db ht-id))
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


(let [db (db conn)
      sq-id 17592186045433
      ws-data (d/pull db
                      '[{:ws/content [:ws.content/question :ws.content/answer]}]
                      ws-id)
      q-ht-id (get-in ws-data [:ws/content :ws.content/question :db/id])
      a-ht-id (get-in ws-data [:ws/content :ws.content/answer :db/id])
      ]
  ws-data
  {:q (render-ht db q-ht-id)
   :a a-ht-id
   :p->id {:q q-ht-id}})

;; Pointers within a question etc. are numbered local to that hypertext. So
;; unlock would look like [:unlock :q 1].

(let [db (db conn)
      ; Interface!
      ws-data (d/pull db
                      '[{:ws/content [:ws.content/question :ws.content/sub-ws]}]
                      ws-id)
      q-ht-id (get-in ws-data [:ws/content :ws.content/question :db/id])
      sq-ws-ids (s/select [:ws/content :ws.content/sub-ws s/ALL :db/id] ws-data)
      sq-keys (into (sorted-map)
                    (map-indexed (fn [i sq-ws-id]
                                   [(keyword (str "sq" i)) sq-ws-id])
                                 sq-ws-ids))
      to-keep (assoc sq-keys :q q-ht-id)
      entries (concat [:q (render-ht db q-ht-id)]
                      (flatten (for [[k sq-ws-id] sq-keys]
                                 [k (render-sq db sq-ws-id)]))
                      [:p->id to-keep])
      to-show (apply array-map entries)]
  to-show)

)
