(ns jursey.core
  [:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.spec.alpha :as spec]
            [clojure.stacktrace :as stacktrace]
            [clojure.string :as string]
            [cognitect.rebl :as rebl]
            [cognitect.transcriptor :as transcriptor :refer [check!]]
            [com.rpl.specter :as s]
            [datomic.api :as d]
            datomic-helpers
            [instaparse.core :as insta]
            [plumbing.core :as plumbing]
            [such.imperfection :refer [-pprint-]]]
  [:use [plumbing.core
         :only [safe-get safe-get-in]
         :rename {safe-get sget safe-get-in sget-in}]])
;; Also uses: datomic.Util

;; aht  … answer hypertext
;; ht   … hypertext
;; pid  … pointer entity ID
;; pmap … pointer map
;; qht  … question hypertext
;; ws   … workspace
;; wsid … workspce entity ID


;; MAYBE TODO: If this ever goes into production, use (d/query … :timeout …).
;; Cf. Michael Nygard: Release It!
;; (RM 2019-01-21)


;;;; Setup
(def --Setup) ; Workaround for a clearer project structure in IntelliJ.

(declare test-agent)
(declare conn)

(defn set-up [{:keys [reset?]}]
  (def test-agent "test")

  (let [base-uri "datomic:free://localhost:4334/"
        db-name "jursey"
        db-uri (str base-uri db-name)]

    (if-not reset?
      (def conn (d/connect db-uri))

      (do (when (some #{db-name} (d/get-database-names (str base-uri "*")))
            (d/delete-database db-uri))

          (d/create-database db-uri)
          (def conn (d/connect db-uri))

          (with-open [rdr (io/reader "src/jursey/schema.edn")]
            @(d/transact conn (datomic.Util/readAll rdr)))

          @(d/transact conn [{:agent/handle test-agent}])))))

(comment

  (set-up {:reset? true})

  (set-up {:reset? false})

  )


;;;; Workspace API
(def --Workspace-API)

(defn get-ws-version-txids
  "Return the IDs of all actions after which ws was showable to the user.
  Caution: This might not work with a since-db."
  [db wsid]
  (let [;; The tx in which the ws was created.
        first-txid (->> (d/datoms db :eavt wsid) (map :tx) sort first)]
    (->> (list first-txid)
         ;; Txs of all actions that didn't block ws.
         (concat (d/q '[:find [?tx ...]
                        :in $ ?ws
                        :where
                        [?tx :tx/ws ?ws]
                        (not [?ws :ws/waiting-for _ ?tx true])
                        ;; Because ws won't be shown after reply.
                        (not-join [?tx]
                                  [?tx :tx/act ?a]
                                  [?a :act/command :act.command/reply])]
                      (d/history db) wsid))
         ;; Txs of all actions that unblocked ws.
         (concat (d/q '[:find [?tx ...]
                        :in $ ?ws
                        :where
                        [?ws :ws/waiting-for _ ?tx false]]
                      (d/history db) wsid))
         sort
         vec)))

(defn get-ws-act-txids
  "Return the IDs of transactions that resulted from an action in ws.
  Caution: This might not work with a since-db."
  [db wsid]
  (->> (d/q '[:find [?tx ...]
              :in $ ?ws
              :where
              [?tx :tx/ws ?ws]]
            (d/history db) wsid)
       sort
       vec))

(defn get-ws-parent-wsid
  "Return the ID of the parent ws of `wsid`.
  If `wsid` points to a root workspace, return nil."
  [db wsid]
  (d/q '[:find ?pws .
         :in $ ?ws
         :where
         [?qa :qa/ws ?ws]
         [?pws :ws/sub-qa ?qa]
         ;; Don't include root question pseudo workspaces.
         [?pws :ws/question]]
       db wsid))

;;;; Version API
(def --Version-API)

(defn get-version-act [db id]
  (let [[wsid version-txid] (d/q '[:find [?ws ?tx]
                                   :in $ ?v
                                   :where
                                   [?r :reflect/version ?v]
                                   [?r :reflect/ws ?ws]
                                   [?v :version/tx ?tx]]
                                 db id)
        next-version-txid (->> (get-ws-act-txids db wsid)
                               (drop-while #(<= % version-txid))
                               first)]
    (when next-version-txid
      (d/q '[:find [?command ?content]
             :in $ ?tx
             :where
             [?tx :tx/act ?a]
             [?a :act/command ?cmdident]
             [?a :act/content ?content]
             [?cmdident :db/ident ?command]]
           db next-version-txid))))


;;;; Reflect API
(def --Reflect-API)

(defn get-reflect-versions
  "Return versions of reflect entity `id`, ordered from oldest to newest."
  [db id]
  (->> (d/q '[:find ?tx ?v
              :in $ ?r
              :where
              [?r :reflect/version ?v]
              [?v :version/tx ?tx]]
            db id)
       (sort-by first)
       (map second)))


;;;; Hypertext string → transaction map
(def --Hypertext-parsing)

(def pointer-re #"(?xms)
                  \$
                  (
                    \w+
                    (?: [.] \w+ )*
                  )")

;; TODO: Support escaped brackets and dollar signs.
(def parse-ht
  (insta/parser
    (format
      "S        = chunks
       <chunks> = chunk*
       <chunk>  = text | pointer | embedded
       text     = #'[^\\[\\]$]+'
       embedded = <'['> chunks <']'>
       pointer  = #'%s'"
      pointer-re)))

(defn ->path [pointer & relative-path]
  (into (string/split pointer #"\.") relative-path))

;; TODO: Use the ::*-path specs for identification. (RM 2019-01-30)
;; Note: An unlocked reflection structure already has a :target entry. However,
;; reflection pointers should always get a new reflection structure as their
;; target. Because reflection pointers only indicate a specific part of the
;; structure. (Not sure about this. Just using the :target entry should also
;; be fine.)
(defn target-type [_ wsdata path]
  (let [parent-path (pop path)
        parent-type (get-in wsdata (conj parent-path :type))]
    (cond
      (= path ["r"])                        :reflection-root
      (= (last path) "parent")              :parent
      (and (= parent-type :reflect)
           (re-matches #"\d+" (last path))) :version
      (= (last parent-path) "children")     :child
      :else                                 :hypertext)))

(defmulti get-target target-type)

;; TODO: This supports only a top-level reflection root. Support deeper
;; reflection roots too, for pointer laundering. This is not crucial, because
;; the user can refer to the same thing by the version. (RM 2019-02-04)
(defmethod get-target :reflection-root [db {wsid :id} _]
  (let [rid (d/tempid :db.part/user)]
    [rid
     [{:db/id             rid
       :reflect/ws        wsid
       :reflect/reachable (-> (d/basis-t db) d/t->tx)}]]))
;; basis-t is the last transaction, so the transaction where the target is
;; created won't be reachable anymore.

(spec/def ::strnum #(re-matches #"\d+" %))
;; TODO: Use better namespaces, such as ::jursey.path/child. (RM 2019-02-04)
(spec/def ::child-path (spec/cat :version-path (spec/+ string?)
                                 :_ #{"children"}
                                 :child ::strnum))

;; TODO: This overlaps with unlock-child. Clean it up analogous to
;; make-{parent,version}-txpart. (RM 2019-01-30)
(defmethod get-target :child [db wsdata path]
  (let [{:keys [version-path]} (spec/conform ::child-path path)
        version (d/entity db (sget-in wsdata (conj version-path :target)))
        rid (d/tempid :db.part/user)]
    [rid
     [{:db/id             rid
       :reflect/ws        (sget-in wsdata (conj path :wsid))
       :reflect/reachable (sget-in version [:version/tx :db/id])}]]))

(spec/def ::parent-path (spec/cat :reflect-path (spec/+ string?)
                                  :_ #{"parent"}))

;; TODO: Reorganize the code, so that I don't have to put in all these
;; `declare`s (RM 2019-01-30).
(declare make-parent-txpart)

(defmethod get-target :parent [db wsdata path]
  (make-parent-txpart db wsdata path {:attach? false}))

(declare make-version-txpart)

(defmethod get-target :version [db wsdata path]
  (make-version-txpart db wsdata path {:attach? false}))

(defmethod get-target :hypertext [_ wsdata path]
  [(sget-in wsdata (conj path :target)) nil])

(defn process-pointer [db wsdata dollar-pointer]
  (let [pointer (apply str (rest dollar-pointer))
        path (->path pointer)
        [target txreq] (get-target db wsdata path)]
    {:repr    dollar-pointer
     :txreq   txreq
     :pointer {:pointer/name    pointer
               :pointer/locked? (get-in wsdata (conj path :locked?) true)
               :pointer/target  target}}))

;; Note: Datomic's docs say that it "represents transaction requests as data
;; structures". That's why I call such a data structure (list of lists or maps)
;; a "transaction request" and its parts "transaction parts". There are also the
;; nested data structures that I turn into a transaction request with
;; datomic-helpers/translate-value. These I call "transaction trees".
;; TODO: Make the naming more consistent. For example, there is act-txreq,
;; which never returns a whole txreq, just some txparts (or a txpart?). (RM
;; 2019-01-21)
(declare process-httree)

(defn tmp-htid
  "Return a temporary :db/id for the transaction map of a piece of hypertext."
  [loc]
  (str "htid" (string/join \. loc)))

;; Note: Is this the right approach?
(defn sgetter [k]
  (fn [m]
    (sget m k)))

;; TODO: Document this.
;; TODO: Find a better name for wsdata.
(defn process-embedded [db wsdata loc children]
  (let [processed-children (map-indexed (fn [i c]
                                          (process-httree db wsdata
                                                          (conj loc i)
                                                          c))
                                        children)]
    {:repr    (str \$ (last loc))
     :pointer {:pointer/name    (str (last loc))
               :pointer/target  (tmp-htid loc)
               :pointer/locked? false}
     :txreq   (conj
                (apply concat (filter some? (map :txreq processed-children)))
                {:db/id             (tmp-htid loc)
                 :hypertext/content (apply str (map (sgetter :repr)
                                                    processed-children))
                 :hypertext/pointer (filter some? (map :pointer processed-children))})}))

(defn process-httree [db wsdata loc [tag & children]]
  "
  `loc` is the location/path of the current element in the syntax tree.
  'httree' means the syntax tree that results from parsing hypertext."
  (case tag
    ;; :repr is the string that will be included in the parent hypertext.
    :text {:repr (first children)}
    :pointer (process-pointer db wsdata (first children))
    :embedded (process-embedded db wsdata loc children)))

(defn ht->txreq [db wsdata ht]
  (let [;; TODO: Fix the grammar so we don't have to turn :S into a fake :embedded.
        httree (replace {:S :embedded} (parse-ht ht))]
    (-> (process-httree db wsdata [] httree)
        (sget :txreq))))


;;;; Rendering a workspace as a string
(def --Hypertext-rendering)

;; Credits: source of clojure.pprint
(defmulti hypertext-dispatch class)
(defmethod hypertext-dispatch String [s]
  ((pprint/formatter-out "“~A”") s))
(defmethod hypertext-dispatch :default [x]
  (pprint/simple-dispatch x))

(defn hypertext-format [x]
  (pprint/with-pprint-dispatch hypertext-dispatch
    (pprint/write x :stream nil)))

;; TODO: Think about whether this can produce wrong substitutions.
;; (RM 2018-12-27)
;; Note: This has (count m) passes. Turn it into a one-pass algorithm if necessary.
(defn replace-substrings
  "Replace occurrences of the keys of `m` in `s` with the corresponding vals."
  [s m]
  (reduce-kv string/replace s m))

(defn string-indexed-map
  "[x y z] → {“0” (f x) “1” (f y) “2” (f z)}
  Curved quotation marks substitute for straight ones in this docstring."
  [f xs]
  (into {} (map-indexed (fn [i v]
                          [(str i) (f v)])
                        xs)))

(declare render-reflect-data)

(defn render-htdata [htdata]
  (case (sget htdata :type)
    :hypertext
    (let [name->htdata
          (apply dissoc htdata (filter keyword? (keys htdata)))
          pointer->text
          (into {} (map (fn [[name embedded-htdata]]
                          [(str \$ name)
                           (if (sget embedded-htdata :locked?)
                             (str \$ name)
                             (format "[%s: %s]" name
                                     (render-htdata embedded-htdata)))])
                        name->htdata))]
      (replace-substrings (sget htdata :text) pointer->text))

    :reflect
    (str \newline
         (hypertext-format (render-reflect-data htdata))
         \newline)))

(declare get-htdata)
(declare get-reflect-data)

(defn get-pointer-data [db id]
  (let [{:keys [pointer/target]} (d/entity db id)]
    (assert target)
    (cond
      (get target :hypertext/content)
      (get-htdata db (sget target :db/id))

      (get target :reflect/ws)
      (get-reflect-data db (sget target :db/id))

      :else
      (throw (ex-info "Don't know how to handle this pointer target."
                      {:target target
                       :pull   (d/pull db '[*] (sget target :db/id))})))))

(spec/def :db/id int?)
(spec/def ::entity (spec/keys :req [:db/id]))

(spec/def :pointer/name string?)
(spec/def :pointer/target ::entity)
(spec/def :pointer/locked? boolean?)
(spec/def ::pointer (spec/keys :req [:pointer/name :pointer/target
                                     :pointer/locked?]))

(defn get-htdata [db id]
  (let [ht (d/pull db '[*] id)]
    (into {:type    :hypertext
           :text    (sget ht :hypertext/content)
           :target  id
           :locked? false}
          (map (fn [{pid                :db/id
                     pname              :pointer/name
                     {target-id :db/id} :pointer/target
                     :keys              [pointer/locked?]
                     :as                p}]
                 {:pre [(spec/valid? ::pointer p)]}
                 [pname
                  (if locked?
                    {:id      pid
                     :locked? true
                     :target  target-id}
                    (get-pointer-data db pid))])
               (get ht :hypertext/pointer [])))))

(defn get-qadata [db
                  {{q-htid :db/id} :qa/question
                   {apid :db/id}   :qa/answer}]
  {"q" (get-htdata db q-htid)
   "a" (let [{pid             :db/id
              locked?         :pointer/locked?
              {target :db/id} :pointer/target} (d/pull db '[*] apid)]
         (if locked?
           {:id      pid
            :locked? true
            :target  target}
           (get-htdata db target)))})

(defn render-qadata [qadata]
  {"q" (render-htdata (sget qadata "q"))
   "a" (if (sget-in qadata ["a" :locked?])
         :locked
         (render-htdata (sget qadata "a")))})

(declare get-wsdata)

;; TODO: Make it consistent where (caller/called) and how arguments are
;; passed, destructured and verified. See also branch abandoned/ids-to-entities
;; (RM 2019-01-22)
(defn get-child-data [db
                      {version-id :db/id}
                      {{sub-wsid :db/id} :qa/ws}]
  (assert (and version-id sub-wsid))
  (let [base {:wsid sub-wsid}
        child-reflect-id (d/q '[:find ?r .
                                :in $ ?v ?w
                                :where
                                [?v :version/child ?r]
                                [?r :reflect/ws ?w]]
                              db version-id sub-wsid)]
    (if child-reflect-id
      (merge base (get-reflect-data db child-reflect-id)
             {:locked? false})
      (assoc base :locked? true))))

(defn get-version-data [db wsid id]
  (let [{version-no    :version/number
         {txid :db/id} :version/tx
         :as           version}
        (d/entity db id)
        db-at-version (d/as-of db txid)]
    {:number    version-no
     :target    id
     "ws"       (get-wsdata db-at-version wsid)
     "act"      (get-version-act db id)
     "children" (into {}
                      (string-indexed-map
                        #(get-child-data db version %)
                        (get (d/entity db-at-version wsid) :ws/sub-qa)))}))

(declare render-wsdata)

(defn render-version-data [{wsdata "ws" children "children"
                            [command act-content :as act] "act"}]
  {"ws"       (render-wsdata wsdata)
   "children" (plumbing/map-vals (fn [c]
                                   (if (get c :locked?)
                                     :locked
                                     (render-reflect-data c))) children)
   "act"      (when (some? act)
                [(keyword (name command)) act-content])})

;; Note on naming: qa, ht, ws are abbreviations, so I write qadata, htdata,
;; wsdata without a dash. "reflect" is a whole word, so I write reflect-data
;; with a dash.
;; Note on reachability: It is important to pass the
;; reachable-db/db-at-version only in specific places, because it might not
;; contain the necessary reflection structures.
;; TODO: Make sure that the :version/tx and :reflect/reachable are
;; monotonically decreasing on every branch of the tree. (RM 2019-01-23)
(defn get-reflect-data [db id]
  (let [{{wsid :db/id}              :reflect/ws
         {parent-reflect-id :db/id} :reflect/parent
         {reachable-txid :db/id}    :reflect/reachable} (d/entity db id)
        _ (assert wsid)

        reachable-db  (if reachable-txid
                        (d/as-of db reachable-txid)
                        db)
        version-count (count (get-ws-version-txids reachable-db wsid))
        version-data  (->> (get-reflect-versions db id)
                           (map #(let [{:keys [number] :as version-data}
                                       (get-version-data db wsid %)]
                                   [(str number) version-data])))
        parent-data   (cond (some? parent-reflect-id)
                            (get-reflect-data db parent-reflect-id)

                            (some? (get-ws-parent-wsid db wsid))
                            {:locked? true}

                            :else
                            nil)]
    (-> {:type    :reflect
         :target  id
         :max-v   (dec version-count)
         :locked? false}
        (plumbing/assoc-when "parent" parent-data)
        (into version-data))))

;; TODO: Maybe I can remove the "data" from the get- and render- functions.
;; What they return and accept is obvious from their argument. (RM 2019-01-24)
;; TODO: The structure of the get- and render- functions is similar. How can I
;; avoid repetition? (RM 2019-02-04)
(defn render-reflect-data [{parent "parent" max-v :max-v :as reflect-data}]
  (let [rendered-versions (->> reflect-data
                               (filter (fn [[k _]] (re-matches #"\d+" (str k))))
                               (plumbing/map-vals render-version-data))
        rendered-parent (cond (nil? parent)          nil
                              (sget parent :locked?) :locked
                              :else
                              (render-reflect-data parent))]
    (-> {:max-v max-v}
        (plumbing/assoc-when "parent" rendered-parent)
        (into rendered-versions))))

(defn get-wsdata [db id]
  (let [{{qid :db/id} :ws/question
         sub-qas      :ws/sub-qa}
        (d/pull db '[*] id)]
    {:id      id
     "q"      (some->> qid (get-htdata db))
     "sq"     (string-indexed-map #(get-qadata db %) (sort-by :db/id sub-qas))
     "r"      (if-let [reflect-id (get-in (d/entity db id) [:ws/reflect :db/id])]
                (get-reflect-data db reflect-id)
                {:locked? true})
     :locked? false}))

(defn render-wsdata [{reflect-data "r" :as wsdata}]
  {"q"  (render-htdata (sget wsdata "q"))
   "sq" (plumbing/map-vals #(render-qadata %) (get wsdata "sq"))
   "r"  (if (sget reflect-data :locked?)
          :locked
          (render-reflect-data reflect-data))})


;;;; Copying hypertext
(def --Hypertext-copying)

(declare get-cp-hypertext-txtree)

(defn get-cp-version-txtree [version]
  {:version/number (sget version :version/number)
   :version/tx     (sget-in version [:version/tx :db/id])})

;; Note: I don't need to copy anything else. Reflection pointers can only
;; point to a reflect or a reflect together with exactly one version. So I
;; don't need to make a recursive call. What about the workspaces and
;; actions that reflects point at? I don't need to copy them, either. I only
;; need to copy something if parts of it might be unlocked in another place. A
;; workspace or action cannot be passed around on its own. It has to be part
;; of a reflection structure. And in there it is immutable. Of course we can
;; pass around a hypertext from within that workspace, but copying of
;; hypertexts is already supported.
(defn get-cp-reflect-txtree [db id]
  (let [{{wsid :db/id}           :reflect/ws
         {reachable-txid :db/id} :reflect/reachable
         versions                :reflect/version}
        (d/entity db id)]
    (assert wsid)
    (assert (<= (count versions) 1))
    {:reflect/ws        wsid
     :reflect/reachable reachable-txid
     :reflect/version   (map get-cp-version-txtree versions)}))

;; Note: This locks all the pointer copies, because that's what I need when I
;; copy a hypertext to another workspace. Adapt if you need faithfully copied
;; locked status somewhere.
(defn get-cp-pointer-txtree [db
                             {{target-id :db/id} :pointer/target}
                             new-name]
  ;; TODO: Test whether it actually retains the target. (RM 2019-01-07)
  (let [target
        (d/pull db '[*] target-id)

        new-target
        (cond
          (some? (get target :hypertext/content))
          (get-cp-hypertext-txtree db target-id)

          (some? (get target :ws/question))
          target-id

          (some? (get target :reflect/ws))
          (get-cp-reflect-txtree db target-id)

          :else (throw (ex-info "Don't know how to handle this pointer target."
                                {:target target})))]
    {:pointer/name    new-name
     :pointer/target  new-target
     :pointer/locked? true}))

(defn map-keys-vals [f m]
  (into {} (map (fn [[k v]]
                  [(f k) (f v)])
                m)))

;; TODO: Change to Derek's semantics, where each occurrence of the same
;; pointer gets its own copy. Implementing this would take half an hour that
;; I don't want to take now. (RM 2019-01-07)
;; Note: For now I don't worry about tail recursion and things like that.
(defn get-cp-hypertext-txtree [db id]
  (let [htdata
        (d/pull db '[*] id)

        orig->anon-pointer
        (into {} (map-indexed (fn [i pmap]
                                [(sget pmap :pointer/name) (str i)])
                              (get htdata :hypertext/pointer)))

        pointer-txtrees
        (mapv (fn [pmap]
                (->> (sget pmap :pointer/name)
                     (sget orig->anon-pointer)
                     (get-cp-pointer-txtree db pmap)))
              (get htdata :hypertext/pointer []))]
    (-> htdata
        (dissoc :db/id)
        (assoc :hypertext/pointer pointer-txtrees)
        (assoc :hypertext/content
               (replace-substrings (sget htdata :hypertext/content)
                                   (map-keys-vals #(str \$ %) orig->anon-pointer))))))

;; TODO: Pull in the code for datomic-helpers/translate-value, so that I
;; have control over it (RM 2019-01-04).
(defn cp-hypertext-txreq [db id]
  (@#'datomic-helpers/translate-value (get-cp-hypertext-txtree db id)))


;;;; Core API
(def --Core-API)

;; Invariants/rules:
;; - Never show a waiting workspace to the user (in fact, never call
;;   get-wsdata on a waiting workspace).
;; - Only show a workspace to the user if it is waited for.
;; - Workspaces that :agent/root-ws refers to have no :ws/question. All other
;;   workspaces have a :ws/question.
;; - For anything that can be part of a pointer string, its key in wsdata has to
;;   be the same as in the rendered wsdata.
;; - All render- functions must return a string.

;; MAYBE TODO: Check before executing a command that the target
;; workspace is not waiting for another workspace. (RM 2019-01-08)

(defn wss-to-show
  "Return IDs of workspaces that are waited for, but not waiting for.
  Ie. they should and can be worked on. A workspace can be waited for by another
  workspace, or by an agent if it would answer one of that agent's root
  questions."
  [db]
  (d/q '[:find [?ws ...]
         :where
         [_ :ws/waiting-for ?ws]
         (not [_ :agent/root-ws ?ws])
         (not [?ws :ws/waiting-for _])]
       db))

;; TODO: Add a check that all pointers in input hypertext point at things that
;; exist. (RM 2018-12-28)
;; Notes:
;; - The answer pointer in a QA has no :pointer/name. Not sure if this is
;;   alright.
;; - For now, passing "ws" or "act" is not supported. You have to pass the
;;   whole version. This can be amended in less than ten hours.
(defn ask [db wsid wsdata question]
  (let [qht-txreq (ht->txreq db wsdata question)

        {:keys [db-after tempids]}
        (d/with db qht-txreq)

        ;; Note: Both when a question is asked and when an answer is given,
        ;; two copies of it will be created: One to be stored in the current
        ;; workspace and one with locked pointers to be stored in the child
        ;; (for questions) or parent (for answers) workspace. In fact, when
        ;; an answer is given, it will be copied for each pointer that points
        ;; at it.
        [qht-copy-tempid qht-copy-txreq]
        (cp-hypertext-txreq db-after
                            (d/resolve-tempid db-after tempids "htid"))

        final-txreq
        (concat
          [{:db/id     wsid
            :ws/sub-qa "qaid"}]

          qht-txreq
          [{:db/id       "qaid"
            :qa/question "htid"
            :qa/answer   "apid"
            :qa/ws       "sub-wsid"}
           {:db/id           "apid"
            :pointer/locked? true
            :pointer/target  "sub-wsid"}]

          qht-copy-txreq
          [{:db/id       "sub-wsid"
            :ws/question qht-copy-tempid}

           {:db/id       "actid"
            :act/command :act.command/ask
            :act/content question}
           {:db/id  "datomic.tx"
            :tx/ws  wsid
            :tx/act "actid"}])]
    final-txreq))

(defn- unlock-by-pmap [db wsid {target-id :target pid :id}]
  (let [target (d/pull db '[*] target-id)

        set-waiting-txreq
        (if (get target :ws/question) ; Must be a pointer to an ungiven answer.
          [{:db/id          wsid
            :ws/waiting-for (get target :db/id)}]
          [])

        txreq
        (concat
          set-waiting-txreq

          ;; Note: I can set the pointer to unlocked even if it's pointing to an
          ;; ungiven answer, because this workspace won't be rendered again
          ;; until the waiting-for is cleared. At that point the target will be
          ;; renderable. This way I don't have to find all the pointers with
          ;; pending unlock after the reply is given.
          [{:db/id           pid
            :pointer/locked? false}])]
    txreq))

;; TODO: Use this in all functions that set :tx/ws and :tx/act. (RM 2019-01-18)
;; Note: If it should be used in all command-implementing functions,
;; shouldn't I just put it in their caller? Or make it a sort of wrapper?
;; On the one hand that would avoid repetition. On the other hand: Wrappers
;; can make debugging harder. And currently the only caller is `run`, which is
;; in a higher layer. I could put a caller in between, but that would be ugly as
;; well. So for now leave calls to `act-txreq` in the command-implementing
;; functions.
(defn act-txreq [wsid command content]
  [{:db/id       "actid"
    :act/command (keyword "act.command" (name command))
    :act/content content}
   {:db/id  "datomic.tx"
    :tx/ws  wsid
    :tx/act "actid"}])

(defn- unlock-reflect [wsid]
  [{:db/id      wsid
    :ws/reflect "rid"}
   {:db/id      "rid"
    :reflect/ws wsid}])

;; TODO: Make this stricter, so that it not only parses, but also validates.
;; (RM 2019-01-31)
(spec/def ::version-path (spec/cat :reflect-path (spec/+ string?)
                                   :version ::strnum))

;; TODO: Make sure that the version <= max-v. (RM 2019-01-23)
(defn- make-version-txpart [db wsdata path & [{:keys [attach?]
                                               :or   {attach? true}}]]
  (let [{:keys [reflect-path version]} (spec/conform ::version-path path)
        reflect-id (sget-in wsdata (conj reflect-path :target))

        {{wsid :db/id}      :reflect/ws
         {reachable :db/id} :reflect/reachable}
        (d/entity db reflect-id)

        rid (if attach?
              reflect-id
              (d/tempid :db.part/user))
        vid (d/tempid :db.part/user)
        int-version (Integer/parseInt version)]
    [rid
     [(-> {:db/id           rid
           :reflect/ws      wsid
           :reflect/version vid}
          (plumbing/assoc-when
            :reflect/reachable
            (cond
              (and (not attach?) (some? reachable)) reachable
              (and (not attach?) (nil? reachable)) (-> (d/basis-t db) d/t->tx)
              :else nil)))
      {:db/id          vid
       :version/number int-version
       :version/tx     (-> (get-ws-version-txids db wsid) (nth int-version))}]]))

(defn- unlock-child [db version-id child-wsid]
  [{:db/id         version-id
    :version/child "rid"}
   {:db/id             "rid"
    :reflect/ws        child-wsid
    :reflect/reachable (sget-in (d/entity db version-id)
                                [:version/tx :db/id])}])

(defn- make-parent-txpart [db wsdata path & [{:keys [attach?]
                                              :or   {attach? true}}]]
  (let [{:keys [reflect-path]} (spec/conform ::parent-path path)
        reflect-id (sget-in wsdata (conj reflect-path :target))
        [child-wsid parent-wsid]
        (d/q '[:find [?w ?p]
               :in $ ?r
               :where
               [?r :reflect/ws ?w]
               [?qa :qa/ws ?w]
               [?p :ws/sub-qa ?qa]]
             db reflect-id)
        child-created-txid (first (get-ws-version-txids db child-wsid))
        reachable-txid (->> (get-ws-version-txids db parent-wsid)
                            (filter #(< % child-created-txid))
                            last)
        rid (d/tempid :db.part/user)]
    [rid
     (cond-> [{:db/id             rid
               :reflect/ws        parent-wsid
               :reflect/reachable reachable-txid}]
             attach? (conj {:db/id          reflect-id
                            :reflect/parent rid}))]))

;; TODO: Check that the pointer is actually locked.
(defn unlock [db wsid wsdata pointer]
  (let [path (->path pointer)
        parent-path (pop path)

        txreq
        ;; MAYBE TODO: Maybe turn this into a multimethod. Cf. get-target. (RM
        ;; 2019-01-29)
        (case (target-type db wsdata path)
          :reflection-root
          (unlock-reflect wsid)

          :parent
          (-> (make-parent-txpart db wsdata path) second)

          :version
          (-> (make-version-txpart db wsdata path) second)

          :child
          (unlock-child db (get-in wsdata (conj (pop parent-path)
                                                :target))
                        (get-in wsdata (conj path :wsid)))

          :hypertext
          (unlock-by-pmap db wsid (sget-in wsdata (->path pointer))))]
    (concat txreq (act-txreq wsid :unlock pointer))))

;; MAYBE TODO: When a reply is given, it makes sense to retract the workspace
;; in which it happens. Because we don't need it anymore. Nobody will look at
;; it. Even reflection will only look at it in an earlier version of the
;; database. (Not sure about this.) But :pointer/target can refer to a
;; workspace, so :pointer/target cannot be a component attribute, so we'd have
;; to manually traverse the tree rooted in the workspace and retract all
;; hypertexts. This would take at least an hour to implement. Retracting
;; finished workspaces is not crucial, so don't do it for now. Do it later. (RM
;; 2019-01-08)
(defn reply [db wsid wsdata answer]
  (let [aht-txreq (ht->txreq db wsdata answer)

        {:keys [db-after tempids]} (d/with db aht-txreq)
        aht-tempid (d/resolve-tempid db-after tempids "htid")

        targeting-pids
        (d/q '[:find [?p ...]
               :in $ ?wsid
               :where
               [?p :pointer/target ?wsid]]
             db wsid)

        ;; Make a copy of the answer for each pointer that points at it.
        aht-copy-txreq
        (mapcat (fn [pid]
                  (let [[aht-copy-tempid aht-copy-txreq]
                        (cp-hypertext-txreq db-after aht-tempid)]
                    (conj
                      aht-copy-txreq
                      {:db/id          pid
                       :pointer/target aht-copy-tempid})))
                targeting-pids)

        ;; TODO: Make sure that if it's waiting for multiple wss, only the
        ;; current one is removed. I'm not sure about the semantics of
        ;; retract. (RM 2019-01-08)
        unwait-txreq
        (map (fn [waiting-wsid]
               [:db/retract waiting-wsid :ws/waiting-for wsid])
             (d/q '[:find [?waiting-ws ...]
                    :in $ ?this-ws
                    :where [?waiting-ws :ws/waiting-for ?this-ws]]
                  db wsid))

        final-txreq
        (concat
          [{:db/id     wsid
            :ws/answer "htid"}]
          aht-txreq

          aht-copy-txreq
          unwait-txreq

          [{:db/id       "actid"
            :act/command :act.command/reply
            :act/content answer}
           {:db/id  "datomic.tx"
            :tx/ws  wsid
            :tx/act "actid"}])]
    final-txreq))


;;;; Single-user runner
(def --Runner)

;; Notes:
;; - This is becoming ugly with transacts at all levels. But I think I can
;;   tidy it up when I'm working on the third milestone.
;; - Also, maybe I should abstract all the query stuff.
;; - I might have to throw in some derefs to make sure that things are
;;   happening in the right order.

(def last-shown-wsid (atom nil))

;; TODO: Turn this into a function that returns data to be transacted by
;; someone else, just like the rest of the core API. (RM 2019-01-10)
;; Note: This frames the root question as the sub-question of a workspace
;; without own question. Thus we can handle it almost like any other question.
(defn run-ask-root-question [conn agent question]
  (let [ask-txreq
        (concat
          (ask (d/db conn) "wsid" {} question)

          [{:db/id "wsid"} ; Empty ws that just gets a qa from `ask`.
           {:db/id         [:agent/handle agent]
            :agent/root-ws "wsid"}])

        {:keys [db-after tempids]}
        @(d/transact conn ask-txreq)

        wsid (d/resolve-tempid db-after tempids "wsid")

        ;; Kick off processing by unlocking the answer to this root question.
        unlock-txreq
        (unlock db-after wsid (get-wsdata db-after wsid) "sq.0.a")]
    (d/transact conn unlock-txreq)))

;; Note: I wanted to do this with Specter, but that was difficult.
(defn- first-locked-pointer [htdata]
  (cond
    (= (get htdata :type) :hypertext) (->> htdata
                                           (filter #(string? (key %)))
                                           (map #(first-locked-pointer (val %)))
                                           (filter some?)
                                           first)
    (get htdata :locked?)             htdata
    :default                          nil))

;; MAYBE TODO: Change the unlock, so that it goes through the same route as
;; all other unlocks. Ie. it uses sq.0.a.* instead of the pointer map
;; directly. For this I'd have to find or write a walker that gives me not
;; just nodes, but also their paths. (RM 2019-01-10)
(defn- get-root-qa [conn wsid]
  (let [db-at-start (d/db conn)
        question (-> (get-wsdata db-at-start wsid)
                     (sget-in ["sq" "0" "q"])
                     render-htdata)]
    (loop [db db-at-start]
      (if (get (d/entity db wsid) :ws/waiting-for)
        [question :waiting]
        (let [wsdata (get-wsdata db wsid)
              locked-pmap (first-locked-pointer (get-in wsdata ["sq" "0" "a"]))]
          (if (nil? locked-pmap) ; No locked pointers left.
            [question (render-htdata (sget-in wsdata ["sq" "0" "a"]))]
            (do @(d/transact conn
                             (unlock-by-pmap db wsid locked-pmap))
                ;; Note that this doesn't create a :tx/act entry.
                (recur (d/db conn)))))))))

(defn get-root-qas [conn agent]
  (let [db (d/db conn)

        finished-wsids
        (d/q '[:find [?ws ...]
               :in $ ?handle
               :where
               [?a :agent/handle ?handle]
               [?a :agent/root-ws ?ws]
               (not [?ws :ws/waiting-for _])]
             db agent)]
    (map #(get-root-qa conn %) finished-wsids)))

(defn start-working [conn]
  (let [db (d/db conn)
        wsid (first (wss-to-show db))]
    (swap! last-shown-wsid (constantly wsid))
    (render-wsdata (get-wsdata db wsid))))

(defn run [[cmd arg :as command] & [{:keys [trace?]}]]
  (when trace?
    (pprint/pprint command))
  (let [cmd-fn (sget {:ask ask :unlock unlock :reply reply} cmd)
        wsid @last-shown-wsid
        db (d/db conn)
        txreq (cmd-fn db wsid (get-wsdata db wsid) arg)

        _ @(d/transact conn txreq)
        db (d/db conn)
        new-wsid (first (wss-to-show db))

        _ (swap! last-shown-wsid (constantly new-wsid))

        new-ws (when new-wsid (render-wsdata (get-wsdata db new-wsid)))]
    (when trace?
      (pprint/pprint new-ws))
    new-ws))


(def --Comment)

(comment

  ;;;; Tools

  (rebl/ui)
  (in-ns 'jursey.core)
  (stacktrace/e)

  ;; Don't forget to save test/scenarios.repl!
  (transcriptor/run "test/scenarios.repl")


  ;;;; Archive

  ;; Example of what I'm not going to support. One can't refer to input/path
  ;; pointers, only to output/number pointers. This is not a limitation, because
  ;; input pointers can only refer to something that is already in the workspace.
  ;; So one can just refer to that directly.
  {"q"  "What is the capital of $0?"
   "sq" {"0" {"q" "What is the capital city of &q.0?"
              "a" :locked}
         "1" {"q" "What is the population of &sq.0.q.&(q.0)"}}}

  )

(def --Tools)

;; Sometimes I make the mistake to refer to a tempid that is not defined
;; anywhere in the transaction. Datomic's error message in that case is
;; uninformative, so this can help to find what I forgot or misspelled.
(comment

  (let [present-tempids (set (s/transform (s/walker :db/id) :db/id trx-data))]
    (pprint/pprint present-tempids)
    (pprint/pprint (set (s/transform (s/walker #(contains? present-tempids %))
                                     (constantly nil)
                                     trx-data))))

  )
