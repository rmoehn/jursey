(ns jursey.transcriptor-tools
  [:require clojure.data])

;; Credits: https://stackoverflow.com/a/5947802/5091738
(defn TESTING [s]
  (println (str "\033[0;34m" s "\033[0m")))

;; Note: I tried to make it print the line number of its call according to
;; https://stackoverflow.com/a/10958098/5091738, but it prints the line
;; number of the transcriptor/run call.
;; Credits: source of cognitect.transcriptor/check!
(defmacro check-diff!
  ([expected]
   `(check-diff! ~expected *1))
  ([expected x]
   `(let [expected# ~expected
          x#        ~x]
      (when-not (= expected# x#)
        (let [[in-exp# in-x# :as diff#] (clojure.data/diff expected# x#)]
          (throw (ex-info (format (str "Transcript diff assertion failed!\n"
                                       "Expected: %s\n"
                                       "Actual:   %s")
                                  in-exp#
                                  in-x#)
                          {:diff diff#})))))))

