(ns jursey.core-test
  (:require [clojure.test :refer :all]
            [jursey.core :refer :all]))

(deftest reflection-test
  (testing "pass through ask and reply"
    (is (= (do (set-up {:reset? true})
               (run-ask-root-question conn test-agent "What is the capital of [Texas]?")
               (start-working conn)
               (run [:ask "What is the capital city of $q.1?"])
               (run [:unlock "sq.0.a"])
               (run [:reply "Niniveh"])
               (run [:unlock "r"])
               (run [:unlock "r.2"])
               (run [:ask "Did $r.2.children.0 unlock the pointer in its question?"])
               (run [:unlock "sq.1.a"])
               (run [:unlock "q.1"])
               (run [:unlock "q.1.0"])
               (run [:reply "No, it didn't."])
               (run [:ask "What is the capital city of q.1? Make sure to unlock the pointer."])
               (run [:unlock "sq.2.a"])
               (run [:unlock "r"])
               (run [:reply "There is no pointer. See $r.0. Did you forget to write a dollar sign?"])
               (run [:unlock "sq.2.a.1"]))

           {"q" "What is the capital of $1?",
            "sq" {"0" {"q" "What is the capital city of $q.1?", "a" "Niniveh"},
                  "1" {"q" "Did $r.2.children.0 unlock the pointer in its question?",
                       "a" "No, it didn't."},
                  "2" {"q" "What is the capital city of q.1? Make sure to unlock the pointer.",
                       "a" "There is no pointer. See [1: \n{:max-v 1,
 “parent” :locked,
 “0”
 {“ws”
  {“q”
   “What is the capital city of q.1? Make sure to unlock the pointer.”,
   “sq” {},
   “r” :locked},
  “children” {},
  “act” [:unlock “r”]}}
]. Did you forget to write a dollar sign?"}},
            "r" {:max-v 9,
                 "2" {"ws" {"q" "What is the capital of $1?",
                            "sq" {"0" {"q" "What is the capital city of $q.1?",
                                       "a" "Niniveh"}},
                            "r" :locked},
                      "children" {"0" :locked},
                      "act" [:unlock "r"]}}}))

    (testing "two reflection pointers in question and answer"
      (is (= (do (set-up {:reset? true})
                 (run-ask-root-question conn test-agent "What is the capital of [Texas]?")
                 (start-working conn)
                 (run [:ask "What is the capital city of $q.1?"])
                 (run [:unlock "r"])
                 (run [:unlock "r.1"])
                 (run [:ask "How do $r.0 and $r.1.children.0 look to you?"])
                 (run [:unlock "sq.1.a"])
                 (run [:unlock "q.1"])
                 (run [:unlock "q.1"])
                 (run [:unlock "r"])
                 (run [:unlock "r.parent"])
                 (run [:unlock "r.parent.2"])
                 (run [:reply "I guess they look just like $r.parent.0 and $r.parent.2.children.0"])
                 (run [:unlock "sq.1.a.1"])
                 (run [:unlock "sq.1.a.3"]))

             {"q" "What is the capital of $1?",
              "sq" {"0" {"q" "What is the capital city of $q.1?", "a" :locked},
                    "1" {"q" "How do $r.0 and $r.1.children.0 look to you?",
                         "a" "I guess they look just like [1: \n{:max-v 3,
 “0”
 {“ws” {“q” “What is the capital of $1?”, “sq” {}, “r” :locked},
  “children” {},
  “act” [:ask “What is the capital city of $q.1?”]}}
] and [3: \n{:max-v 0, “parent” :locked}\n]"}},
              "r" {:max-v 7,
                   "1" {"ws" {"q" "What is the capital of $1?",
                              "sq" {"0" {"q" "What is the capital city of $q.1?",
                                         "a" :locked}},
                              "r" :locked},
                        "children" {"0" :locked},
                        "act" [:unlock "r"]}}})))))

