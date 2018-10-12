(ns user
  [:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.api :refer [q db] :as d]
            [datomic.api :as d]
            [datascript.core :as d]])

;;;; Setup

(def uri "datomic:free://localhost:4334/jursey")

(d/delete-database uri)

(d/create-database uri)

(def conn (d/connect uri))

(def schema (-> "src/jursey/schema.edn"
                io/reader
                datomic.Util/readAll))
(d/transact conn schema)


;;;; Ask a question

(def first-question
  [{:db/id "qhtid"
    :hypertext/content "What is 20 * 30?"}
   {:ws/content {:ws.content/question "qhtid"}
    :ws/proc {:ws.proc/state :ws.proc.state/pending}}])

@(d/transact conn first-question)

(q '[:find ?text
     :where
     [?ws :ws/content ?c]
     [?c :ws.content/question ?ht]
     [?ht :hypertext/content ?text]]
  (db conn))


;;;; Play around

;; TODO: Search for a waited-for workspace.

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
    :tx/act "actid"}])

@(d/transact conn first-answer)


;;;; Play around

;; TODO: Find out whether there is any workspace that needs to be shown.
;; TODO: Identify the root question and its answer to show them to the user.
;; TODO: Should we have a user ID on the root question, because when the root
;; question is answered, the answer should be shown to the asker?
;; TODO: Should we attach user/agent IDs to the actions for introspection?
;; (Not reflection.)

(q '[:find ?ws ?q ?a
     :where
     [?ws :ws/content ?c]
     [?c :ws.content/answer ?aht]
     [?c :ws.content/question ?qht]
     [?aht :hypertext/content ?a]
     [?qht :hypertext/content ?q]]
   (db conn))


;;;; Reflect

;; TODO: The user wants to know how the root question came about. Were there
;; any subquestions asked?


;;;; Next steps

;; If time is limited, skip the probably easy stuff and go for the things
;; with the most expected problems.

;; TODO: Do the whole thing again with actual pointers.
;; TODO: Do it another time with sub-questions.
;; TODO: Implement the event system around it.
