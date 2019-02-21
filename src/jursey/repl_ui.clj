(ns jursey.repl-ui
  [:require [jursey.core :as j :refer [conn test-agent]]])

(def ^:private last-shown-wsid (atom nil))

(defn set-up []
  (j/set-up {:reset? false}))

(defn reset []
  (j/set-up {:reset? true}))

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
