(ns check-paths
  "Very sloppily written code for finding possibly broken paths in files in a
  Git repo."
  [:require [clojure.java.io :as io]
            [clojure.string :as string]])

(def root ".")

(def relevant-files
  (->> (io/file root)
       file-seq
       (filter (fn [^java.io.File file]
                 (and (.isFile file)
                      (not (string/includes? (.getPath file) "datomic"))
                      (re-matches #".*[.](?:md|clj|repl|edn)" (.getName file)))))))

(def path-re #"(?xms)
              \/?
              (?:[a-z]+/)*(?:[0-9a-z_]|-)+[a-z][.](?:md|clj|repl|edn)
              ")

(def found-paths
  (map (fn [file]
         (let [content (slurp file)]
           [file (re-seq path-re content)]))
       relevant-files))

(doseq [[^java.io.File container paths] found-paths
        path paths]
  (if-not (#{"jursey.repl"} path)
    (let [^java.io.File file
         (if (.startsWith path "/")
           (io/as-file (str root "/" path))
           (io/file (.getParentFile (.getAbsoluteFile container)) path))]
     (if-not (.exists file)
       (println (.getPath container) path)))))
