#!/usr/bin/env bb
;; Corpus property test for clj-replace.bb (black box).
;;
;; Usage: bb corpus [--sample N] [--seed S] [--max-chars C] <dir-or-file>...
;;        (from the repository root; or bb skills/clj-replace/test/corpus.bb ...)
;;
;; Not part of bb test: it needs a Clojure corpus on disk, for example a
;; clone of github.com/jonase/eastwood or github.com/NoahTheDuke/splint.
;;
;; Samples nodes from Clojure files. For each node, old-string is the node's
;; own source with every layout node (whitespace, comma, newline) turned into
;; a newline, so only layout differs. clj-replace must then either replace
;; exactly that node (exit 0, file equal to replacing it with rewrite-clj) or
;; report it among the ambiguous candidates (exit 3). Anything else is a
;; failure. CLJ_REPLACE_SCRIPT selects the script under test.

(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.string :as str]
         '[rewrite-clj.zip :as z]
         '[rewrite-clj.node :as n])

(def script (or (System/getenv "CLJ_REPLACE_SCRIPT")
                (str (fs/path (fs/parent (fs/absolutize *file*)) ".." "scripts" "clj-replace.bb"))))

(defn parse-args [args]
  (loop [[a b & more :as args] args opts {:sample 200 :seed 1 :max-chars 1500 :paths []}]
    (cond
      (empty? args) opts
      (= a "--sample") (recur more (assoc opts :sample (parse-long b)))
      (= a "--seed") (recur more (assoc opts :seed (parse-long b)))
      (= a "--max-chars") (recur more (assoc opts :max-chars (parse-long b)))
      :else (recur (rest args) (update opts :paths conj a)))))

(defn clojure-files [paths]
  (->> paths
       (mapcat #(if (fs/directory? %)
                  (fs/glob % "**.{clj,cljc,cljs,edn,bb}")
                  [(fs/path %)]))
       (map str)
       (remove #(re-find #"/\.|node_modules" %))
       sort))

(defn layout? [nd] (contains? #{:whitespace :newline :comma} (n/tag nd)))

(defn relayout
  "nd with every layout node replaced by a newline."
  [nd]
  (if (n/inner? nd)
    (n/replace-children nd (map #(if (layout? %) (n/newlines 1) (relayout %)) (n/children nd)))
    nd))

(defn nodes-of [file max-chars]
  (try
    (loop [z (z/of-string (slurp file) {:track-position? true}) acc []]
      (if (z/end? z)
        acc
        (let [s (z/string z)]
          (recur (z/next z)
                 (if (<= 1 (count s) max-chars)
                   (conj acc {:file file :pos (z/position z) :z z})
                   acc)))))
    (catch Exception _ [])))

(def sentinel "(CLJ-REPLACE-CORPUS-SENTINEL)")

(defn check [tmp {:keys [file pos z]}]
  (let [old (n/string (relayout (z/node z)))
        target (str (fs/path tmp (str "t" (fs/extension file) "." (or (fs/extension file) "clj"))))
        _ (fs/copy file target {:replace-existing true})
        want (z/root-string (z/replace z (n/token-node nil sentinel)))
        {:keys [exit out err]} (proc/shell {:continue true :out :string :err :string}
                                           "bb" script target old sentinel)
        got (slurp target)
        [line col] pos
        ok (case exit
             0 (= want got)
             3 (str/includes? out (str "line " line ", column " col ":"))
             false)]
    (when-not ok
      {:file file :line line :col col :exit exit
       :old (subs old 0 (min 200 (count old)))
       :output (subs (str out err) 0 (min 600 (count (str out err))))
       :file-matches? (= want got)})))

(let [{:keys [sample seed max-chars paths]} (parse-args *command-line-args*)
      files (clojure-files paths)
      all (vec (mapcat #(nodes-of % max-chars) files))
      rnd (java.util.Random. seed)
      picked (if (<= (count all) sample)
               all
               (repeatedly sample #(nth all (.nextInt rnd (count all)))))
      tmp (fs/create-temp-dir {:prefix "clj-replace-corpus"})
      failures (doall (keep #(check tmp %) picked))
      by-exit (frequencies (map :exit failures))]
  (fs/delete-tree tmp)
  (doseq [f failures] (prn f))
  (println (format "files %d, nodes %d, sampled %d, failures %d %s"
                   (count files) (count all) (count picked) (count failures) (pr-str by-exit)))
  (System/exit (if (seq failures) 1 0)))
