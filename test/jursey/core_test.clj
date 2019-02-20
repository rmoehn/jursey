(ns jursey.core-test
  (:require [clojure.test :refer :all]
            [jursey.core :refer :all]))

(deftest tool-test
  (is (= "bla rsubs blu $r.2 bli"
         (replace-substrings "bla $r blu $r.2 bli" {"$r" "rsubs" "$r.2" "$r.2"}))))
