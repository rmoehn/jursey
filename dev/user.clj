(ns user
  [:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.api :refer [q db] :as d]
            [datomic.api :as d]
            [datascript.core :as d]])

;;;; Setup

(def uri "datomic:free://localhost:4334/jursey")

;; TODO: (next time I start over with the db) Rename agent to agent, because
;; not only agents will be taking actions.
(d/delete-database uri)

(d/create-database uri)

(def conn (d/connect uri))

;; Should this be in a with-open?
(def schema (-> "src/jursey/schema.edn"
                io/reader
                datomic.Util/readAll))
(d/transact conn schema)


;;;; Users

(d/transact conn [{:agent/handle "test"}])


;;;; Ask a question

(def first-question
  [{:db/id [:agent/handle "test"]
    :agent/root-ws "wsid"}
   {:db/id "qhtid"
    :hypertext/content "What is 20 * 30?"}
   {:db/id "wsid"
    :ws/content {:ws.content/question "qhtid"}
    :ws/proc {:ws.proc/state :ws.proc.state/pending}}])

@(d/transact conn first-question)

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

;; I probably want to build an interface around this.
(d/pull (db conn) '[{:ws/content [{:ws.content/question [:hypertext/content]}]}] ws-id)

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
   (d/as-of (db conn) 1013))

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

;; NEXT!
;; TODO: The agent wants to know how the root question came about. Were there
;; any subquestions asked?
;; - The Datomic log contains all data on what action caused what
;;   database changes. The question is whether we can easily extract the
;;   information required for reflection from that.
;; The model here is a little different from Patchwork. Think about what the
;; user wants to see. It might be different for different actions.


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

;; TODO: Do the whole thing again with actual pointers.
;; TODO: Do it another time with sub-questions.
;; TODO: Implement the event system around it.
