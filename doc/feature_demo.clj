(use 'jursey.repl-ui)
(require '[jursey.transcriptor-tools :refer [check-diff!]])

;; This file contains verbose data structures. Please concentrate on the parts
;; indicated by arrows (↑↓) and check-diff!s. I hope you won't be too confused.
;; If you want to see the intermediate workspaces, please run the commands in
;; REPL by yourself.

(defn base-scenario []
  (reset)
  (ask-root "Approx. how many [Rubik's Cubes] fit in a [Buc-ee's]?")
  (start-working)

  (ask "What is the outside volume of $q.3?")
  (ask "What is the inside volume of $q.3?")
  (unlock "sq.0.a")

  (unlock "q.1")
  (reply (format "%s cm³" (* 5.7 5.7 5.7)))

  (unlock "r")
  (unlock "r.3")
  (unlock "r.3.children.0"))

;;;; Children

(base-scenario)
(check-diff!
  {"q" "Approx. how many $1 fit in a $3?",
   "sq" {"0" {"q" "What is the outside volume of $q.1?", "a" "185.193 cm³"},
         "1" {"q" "What is the inside volume of $q.3?", "a" :locked}},
   "r" {:max-v 6,
        "3" {"ws" {"q" "Approx. how many $1 fit in a $3?",
                   "sq" {"0" {"q" "What is the outside volume of $q.1?",
                              "a" "185.193 cm³"},
                         "1" {"q" "What is the inside volume of $q.3?",
                              "a" :locked}},
                   "r" :locked},
             "children" {"0" {:max-v 1, "parent" :locked}, "1" :locked},
             ;;           ↑                                 ↑
             ;; One entry for each sub-question.
             "act" [:unlock "r"]}}})

;;;; Latest version / Reflection of reflection

(base-scenario)
(unlock "r.6")
(check-diff!
  {"q" "Approx. how many $1 fit in a $3?",
   "sq" {"0" {"q" "What is the outside volume of $q.1?", "a" "185.193 cm³"},
         "1" {"q" "What is the inside volume of $q.3?", "a" :locked}},
   "r" {:max-v 7,
        ;;     ↑
        ;; In the top-level workspace one can access everything up to the
        ;; previous version.
        "3" {"ws" {"q" "Approx. how many $1 fit in a $3?",
                   "sq" {"0" {"q" "What is the outside volume of $q.1?",
                              "a" "185.193 cm³"},
                         "1" {"q" "What is the inside volume of $q.3?",
                              "a" :locked}},
                   "r" :locked},
             "children" {"0" {:max-v 1, "parent" :locked}, "1" :locked},
             "act" [:unlock "r"]},
        "6" {"ws" {"q" "Approx. how many $1 fit in a $3?",
                   "sq" {"0" {"q" "What is the outside volume of $q.1?",
                              "a" "185.193 cm³"},
                         "1" {"q" "What is the inside volume of $q.3?",
                              "a" :locked}},
                   ;; If one of the earlier versions contained reflection, it
                   ;; will be reflected, too.
                   ;; ↓
                   "r" {:max-v 6,
                        "3" {"ws" {"q" "Approx. how many $1 fit in a $3?",
                                   "sq" {"0" {"q" "What is the outside volume of $q.1?",
                                              "a" "185.193 cm³"},
                                         "1" {"q" "What is the inside volume of $q.3?",
                                              "a" :locked}},
                                   "r" :locked},
                             "children" {"0" {:max-v 1, "parent" :locked},
                                         "1" :locked},
                             "act" [:unlock "r"]}}},
             "children" {"0" :locked, "1" :locked},
             "act" [:unlock "r.6"]}}})

;;;; Children's versions

(base-scenario)
(unlock "r.3.children.0.0")
(unlock "r.3.children.0.1")
(check-diff!
  {"q" "Approx. how many $1 fit in a $3?",
   "sq" {"0" {"q" "What is the outside volume of $q.1?", "a" "185.193 cm³"},
         "1" {"q" "What is the inside volume of $q.3?", "a" :locked}},
   "r" {:max-v 8,
        "3" {"ws" {"q" "Approx. how many $1 fit in a $3?",
                   "sq" {"0" {"q" "What is the outside volume of $q.1?",
                              "a" "185.193 cm³"},
                         "1" {"q" "What is the inside volume of $q.3?",
                              "a" :locked}},
                   "r" :locked},
             "children" {"0" {:max-v 1,
                              "parent" :locked,
                              ;; One can inspect each version of a descendant,
                              ;; ↓
                              "0" {"ws" {"q" "What is the outside volume of $1?",
                                         "sq" {},
                                         "r" :locked},
                                   "children" {},
                                   ;; including what action the user took there.
                                   ;;     ↓
                                   "act" [:unlock "q.1"]},
                              ;; ↓
                              "1" {"ws" {"q" "What is the outside volume of [1: Rubik's Cubes]?",
                                         "sq" {},
                                         "r" :locked},
                                   "children" {},
                                   ;;     ↓
                                   "act" [:reply "185.193 cm³"]}},
                         "1" :locked},
             "act" [:unlock "r"]}}})

;;;; Parent

(base-scenario)
(unlock "r.3.children.0.parent")
(unlock "r.3.children.0.parent.0")
(check-diff!
  {"q" "Approx. how many $1 fit in a $3?",
   "sq" {"0" {"q" "What is the outside volume of $q.1?", "a" "185.193 cm³"},
         "1" {"q" "What is the inside volume of $q.3?", "a" :locked}},
   ;; This workspace has no parent, because it is a root workspace.
   ;;   ↓
   "r" {:max-v 8,
        "3" {"ws" {"q" "Approx. how many $1 fit in a $3?",
                   "sq" {"0" {"q" "What is the outside volume of $q.1?",
                              "a" "185.193 cm³"},
                         "1" {"q" "What is the inside volume of $q.3?",
                              "a" :locked}},
                   "r" :locked},
             "children" {"0" {:max-v 1,
                              ;; One can go up the tree, too, but not forward in
                              ;; time. In this example, one can only reach the
                              ;; versions of the parent that came before
                              ;; sub-question 0 was asked.
                              ;;               ↓
                              "parent" {:max-v 0,
                                        "0" {"ws" {"q" "Approx. how many $1 fit in a $3?",
                                                   "sq" {},
                                                   "r" :locked},
                                             "children" {},
                                             "act" [:ask
                                                    "What is the outside volume of $q.1?"]}}},
                         "1" :locked},
             "act" [:unlock "r"]}}})

;;;; Reflection pointer in hypertext / Unlocking reflected pointers

(base-scenario)
(ask "Have a look at $r and tell me your impression.")
;; TODO: When I write
;; (ask "Have a look at $r and $r.3 and tell me your impression.")
;; here, I get a wrong result. Fix it. (RM 2019-02-12)
(unlock "sq.2.a")
(unlock "q.1")
(check-diff!
  ;; Reflection structures can be included in hypertext, too.
  ;;                   ↓
  {"q" "Have a look at [1: \n{:max-v 6}\n] and tell me your impression.",
   ;;                         ↑
   ;; Note that one doesn't get the whole structure that you saw in the
   ;; parent, but only the small part that the pointer points at. One can
   ;; explore from there.
   "sq" {},
   "r" :locked})
(unlock "q.1.2")
(comment
  {"q" "Have a look at [1:
      {:max-v 6,
      ;;      ↑
      ;; Reflection structures in hypertext can only reach backwards from the
      ;; time when the hypertext was created.
       “2”
       {“ws”
        {“q” “Approx. how many $1 fit in a $3?”,
        ;;                      ↑
        ;; Reflected structures are snapshots of the past. Therefore, one cannot
        ;; unlock a reflected pointer. But one can still point at it and
        ;; ‘launder’ it through a sub-question. See below.
         “sq”
         {“0” {“q” “What is the outside volume of $q.1?”, “a” :locked},
          “1” {“q” “What is the inside volume of $q.3?”, “a” :locked}},
         “r” :locked},
        “children” {“0” :locked, “1” :locked},
        “act” [:unlock “sq.0.a”]}}
      ] and tell me your impression.",
   "sq" {},
   "r" :locked})
(ask "Give me $q.1.2.ws.q.1.")
(unlock "sq.0.a")
(reply "$q.1")
(unlock "sq.0.a.0")
(check-diff!
  {…
   "sq" {"0" {"q" "Give me $q.1.2.ws.q.0.", "a" "[1: Rubik's Cubes]"}},
   "r" :locked})

;;;; Reflection pointer in root reply

(base-scenario)
(reply "I have one good child $r.3.children.0.1 and one bad $r.3.children.1")
(get-root-qas)
(comment
  '(["Approx. how many [1: Rubik's Cubes] fit in a [3: Buc-ee's]?"
     "I have one good child [1:
     ;; When you pass a pointer to a version, the surrounding structure will
     ;; be included.
     ;; ↓
      {:max-v 1,
       “parent” :locked,
       “1”
       {“ws”
        {“q” “What is the outside volume of [1: Rubik's Cubes]?”,
         “sq” {},
         ;; Unlike normal pointers in a root reply, pointers inside a
         ;; reflection structure won't be unlocked automatically.
         ;;  ↓
         “r” :locked},
        “children” {},
        “act” [:reply “185.193 cm³”]}}
      ] and one bad [3:
      {:max-v 0, “parent” :locked}
      ]"]))
