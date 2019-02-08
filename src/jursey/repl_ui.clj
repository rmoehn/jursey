(ns jursey.repl-ui
  [:require [jursey.core :as j :refer [conn test-agent]]])

(defn set-up []
  (j/set-up {:reset? false}))

(defn reset []
  (j/set-up {:reset? true}))

(defn ask-root [q]
  (j/run-ask-root-question conn test-agent q))

(defn start-working []
  (j/start-working conn))

(defn ask [q]
  (j/run [:ask q]))

(defn unlock [p]
  (j/run [:unlock p]))

(defn reply [a]
  (j/run [:reply a]))

(defn get-root-qas []
  (j/get-root-qas conn test-agent))
