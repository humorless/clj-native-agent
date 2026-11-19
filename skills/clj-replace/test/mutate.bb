#!/usr/bin/env bb
;; Mutation testing for clj-replace.bb.
;;
;; Usage: bb mutate [--jobs N] [--limit N] [--out report.edn]
;;        (from the repository root; or bb skills/clj-replace/test/mutate.bb ...)
;;
;; Makes one mutant of clj-replace.bb per mutation point, runs the regression
;; cases (test/run.bb, stopping at the first failure) against each, and
;; reports the mutation score: the share of mutants the cases kill. A
;; surviving mutant is either a gap in the cases or an equivalent mutant
;; (same behaviour), which needs a human to tell apart.
;;
;; Mutation operators:
;;   swap     a symbol for its counterpart (= / not=, and / or, when /
;;            when-not, empty? / seq, < / <=, inc / dec, true / false, ...)
;;   int      an integer literal n to n+1 (exit codes, counts)
;;   string   a non-empty string literal to "" (messages; docstrings skipped)
;; The require / add-deps forms at the top are not mutated.
;; CLJ_REPLACE_SCRIPT selects the script to mutate.

(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.string :as str]
         '[rewrite-clj.node :as n]
         '[rewrite-clj.zip :as z])

(def here (fs/parent (fs/absolutize *file*)))
(def script (or (System/getenv "CLJ_REPLACE_SCRIPT")
                (str (fs/path here ".." "scripts" "clj-replace.bb"))))
(def runner (str (fs/path here "run.bb")))

(def swaps
  {"=" "not=", "not=" "=", "<" "<=", "<=" "<", ">" ">=", ">=" ">",
   "and" "or", "or" "and", "empty?" "seq", "seq" "empty?",
   "inc" "dec", "dec" "inc", "true" "false", "false" "true",
   "when" "when-not", "when-not" "when", "if" "if-not", "if-not" "if",
   "first" "last", "some?" "nil?", "nil?" "some?", "pos?" "zero?",
   "zero?" "pos?", "every?" "some", "filter" "remove", "remove" "filter"})

(defn usage-error [msg]
  (println (str "mutate: " msg))
  (println "usage: bb mutate [--jobs N] [--limit N] [--out report.edn]")
  (System/exit 1))

(defn parse-args [args]
  (loop [[a b & more :as args] args
         opts {:jobs (.availableProcessors (Runtime/getRuntime))}]
    (cond
      (empty? args) opts
      (#{"--jobs" "--limit"} a) (if-let [v (some-> b parse-long)]
                                  (if (pos? v)
                                    (recur more (assoc opts (keyword (subs a 2)) v))
                                    (usage-error (str a " needs a positive number")))
                                  (usage-error (str a " needs a positive number")))
      (= a "--out") (if b (recur more (assoc opts :out b)) (usage-error "--out needs a path"))
      :else (usage-error (str "unknown argument: " a)))))

;; ---------------------------------------------------------------- mutants

(defn top-level-form [zloc]
  (loop [z zloc]
    (let [up (z/up z)]
      (if (or (nil? up) (= :forms (z/tag up)))
        z
        (recur up)))))

(defn mutable-top-level? [zloc]
  (not (re-find #"^\((require|deps/add-deps|ns) " (z/string (top-level-form zloc)))))

(defn docstring? [zloc]
  (let [l2 (some-> zloc z/left z/left)]
    (boolean (and l2 (#{"defn" "defn-" "defmacro"} (z/string l2))))))

(defn string-node? [nd]
  (and (#{:token :multi-line} (n/tag nd))
       (string? (try (n/sexpr nd) (catch Exception _ nil)))))

(defn mutation [zloc]
  (let [nd (z/node zloc)
        s (n/string nd)
        v (when (= :token (n/tag nd)) (try (n/sexpr nd) (catch Exception _ ::none)))]
    (cond
      (contains? swaps s)
      {:kind :swap :from s :to (swaps s) :node (n/token-node nil (swaps s))}

      (int? v)
      {:kind :int :from s :to (str (inc v)) :node (n/token-node nil (str (inc v)))}

      (and (string-node? nd) (not (str/blank? (n/sexpr nd))) (not (docstring? zloc)))
      {:kind :string :from s :to "\"\"" :node (n/token-node nil "\"\"")})))

(defn mutants [source]
  (loop [zloc (z/of-string source {:track-position? true})
         acc []]
    (if (z/end? zloc)
      acc
      (recur (z/next zloc)
             (if-let [m (and (mutable-top-level? zloc) (mutation zloc))]
               (let [[line col] (z/position zloc)]
                 (conj acc (-> m
                               (dissoc :node)
                               (assoc :id (count acc) :line line :col col
                                      :source (z/root-string (z/replace zloc (:node m)))))))
               acc)))))

;; ---------------------------------------------------------------- running

(defn run-cases
  ":killed, :survived or :timeout for the script at path."
  [path]
  (let [p (proc/process {:out :string :err :string
                         :extra-env {"CLJ_REPLACE_SCRIPT" path
                                     "CLJ_REPLACE_FAIL_FAST" "1"}}
                        "bb" runner)
        r (deref p 180000 ::timeout)]
    (cond
      (= ::timeout r) (do (proc/destroy-tree p) :timeout)
      (zero? (:exit r)) :survived
      :else :killed)))

(defn run-all [tmp jobs ms]
  (let [sem (java.util.concurrent.Semaphore. jobs)
        done (atom 0)]
    (->> ms
         (mapv (fn [m]
                 (future
                   (.acquire sem)
                   (try
                     (let [path (str (fs/path tmp (str "mutant-" (:id m) ".bb")))]
                       (spit path (:source m))
                       (let [outcome (run-cases path)]
                         (let [k (swap! done inc)]
                           (when (zero? (mod k 25))
                             (println (format "  %d/%d" k (count ms)))))
                         (assoc (dissoc m :source) :outcome outcome)))
                     (finally (.release sem))))))
         (mapv deref))))

(defn source-line [source line]
  (str/trim (nth (str/split-lines source) (dec line) "")))

(let [{:keys [jobs limit out]} (parse-args *command-line-args*)
      source (slurp script)
      all (mutants source)
      ms (cond->> all limit (take limit))
      tmp (fs/create-temp-dir {:prefix "clj-replace-mutate"})
      outcome (try
                (if (not= :survived (run-cases script))
                  (do (println "The unmutated script does not pass the cases; fix that first.")
                      ::failed)
                  (do (println (format "%d mutants (%s), %d jobs" (count ms)
                                       (pr-str (frequencies (map :kind ms))) jobs))
                      (run-all tmp jobs ms)))
                (finally (fs/delete-tree tmp)))]
  (when (= ::failed outcome) (System/exit 1))
  (let [freq (frequencies (map :outcome outcome))
        killed (+ (:killed freq 0) (:timeout freq 0))
        survivors (sort-by :id (filter #(= :survived (:outcome %)) outcome))]
    (println)
    (println "Surviving mutants (a gap in the cases, or equivalent to the original):")
    (doseq [{:keys [line col kind from to]} survivors]
      (println (format "  %4d:%-3d %-6s %s -> %s   | %s" line col (name kind) from to
                       (source-line source line))))
    (println)
    (println (format "mutation score: %d/%d killed (%.1f%%); %d survived, %d timed out"
                     killed (count outcome) (* 100.0 (/ killed (max 1 (count outcome))))
                     (count survivors) (:timeout freq 0)))
    (when out
      (spit out (pr-str {:script script :results (mapv #(dissoc % :source) outcome)}))
      (println "report:" out))))
