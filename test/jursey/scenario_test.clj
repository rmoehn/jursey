(ns jursey.scenario-test
  (:require [clojure.test :refer :all]
            [jursey.repl-ui :refer :all]))

(deftest automation-test
  (testing "Automation in sub-workspaces"
    (is (= (do (reset)
               (ask-root "What are the capitals of [Texas] and [Alaska]?")
               (start-working)
               (ask "What is the capital city of $q.1?")
               (ask "What is the capital city of $q.3?")

               (unlock "sq.0.a")
               (unlock "q.1")
               (reply "Austin")

               (unlock "sq.1.a")
               ;; No unlock necessary because of automation.
               (reply "Juneau")

               (reply "The capital of $q.1 is $sq.0.a. The capital of $q.3 is $sq.1.a.")

               (get-root-qas))
           (list ["What are the capitals of [1: Texas] and [3: Alaska]?"
                  (str "The capital of [1: Texas] is [3: Austin]."
                       " The capital of [5: Alaska] is [7: Juneau].")]))))

  (testing "Automation for a root question"
    (is (= (do (reset)
               (ask-root "Are we there yet?")
               (start-working)
               (reply "Almost.")

               (ask-root "Are we there yet?")
               (get-root-qas))
           (list ["Are we there yet?" "Almost."]
                 ["Are we there yet?" "Almost."])))))
