(ns user
  [:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.api :refer [q db] :as d]])

(def uri "datomic:mem://jursey")

(d/create-database uri)

(def conn (d/connect uri))

(def schema (-> "src/jursey/schema.edn"
                io/reader
                datomic.Util/readAll))
(d/transact conn schema)


;; Now what do I do?

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


;; If there are no waited-for workspaces, we look for a workspace that isn't
;; a sub-workspace and must therefore be a root workspace. We show that one.
(q '[:find ?ws
     :where
     [?ws :ws/content]
     (not [_ :ws.content/sub-ws ?ws])]
   (db conn))
