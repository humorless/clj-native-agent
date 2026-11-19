#!/usr/bin/env bb
;; Mutation testing for clj-replace.bb.
;;
;; Usage: bb mutate [--jobs N] [--limit N] [--seed S] [--out report.edn]
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
;;
;; A mutant counts as killed only when the cases report a FAIL, XPASS or
;; DOC problem; any other non-zero exit is an :error, kept out of the score.
;; Survivors that a person has judged equivalent are listed, with the reason,
;; in mutate-equivalent.edn (matched by source line and operator, not line
;; number). bb mutate exits 1 when there is a new survivor or an error.
;; --limit N runs a random sample of N mutants (--seed S, default 1).
;; CLJ_REPLACE_SCRIPT selects the script to mutate.

(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.edn]
         '[clojure.string :as str]
         '[rewrite-clj.node :as n]
         '[rewrite-clj.zip :as z])

(def here (fs/parent (fs/absolutize *file*)))
(def script (or (System/getenv "CLJ_REPLACE_SCRIPT")
                (str (fs/path here ".." "scripts" "clj-replace.bb"))))
(def runner (str (fs/path here "run.bb")))
(def equivalent-file (str (fs/path here "mutate-equivalent.edn")))

(def swaps
  {"=" "not=", "not=" "=", "<" "<=", "<=" "<", ">" ">=", ">=" ">",
   "and" "or", "or" "and", "empty?" "seq", "seq" "empty?",
   "inc" "dec", "dec" "inc", "true" "false", "false" "true",
   "when" "when-not", "when-not" "when", "if" "if-not", "if-not" "if",
   "first" "last", "some?" "nil?", "nil?" "some?", "pos?" "zero?",
   "zero?" "pos?", "every?" "some", "filter" "remove", "remove" "filter"})

(defn usage-error [msg]
  (println (str "mutate: " msg))
  (println "usage: bb mutate [--jobs N] [--limit N] [--seed S] [--out report.edn]")
  (System/exit 1))

(defn parse-args [args]
  (loop [[a b & more :as args] args
         opts {:jobs (.availableProcessors (Runtime/getRuntime)) :seed 1}]
    (cond
      (empty? args) opts
      (#{"--jobs" "--limit" "--seed"} a) (if-let [v (some-> b parse-long)]
                                           (if (pos? v)
                                             (recur more (assoc opts (keyword (subs a 2)) v))
                                             (usage-error (str a " needs a positive number")))
                                           (usage-error (str a " needs a positive number")))
      (= a "--out") (if (and b (not (str/starts-with? b "--")))
                      (recur more (assoc opts :out b))
                      (usage-error "--out needs a path"))
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
    (boolean (and l2
                  (or (#{"defn" "defn-" "defmacro"} (z/string l2))
                      ;; (def name "doc" value)
                      (and (#{"def" "defonce"} (z/string l2)) (z/right zloc)))))))

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
  "{:outcome :killed | :survived | :timeout | :error} for the script at path.
  Killed needs evidence from the cases (a FAIL, XPASS or DOC line); any
  other failure of the run is an :error."
  [path]
  (let [p (proc/process {:out :string :err :string
                         :extra-env {"CLJ_REPLACE_SCRIPT" path
                                     "CLJ_REPLACE_FAIL_FAST" "1"}}
                        "bb" runner)
        r (deref p 180000 ::timeout)]
    (cond
      (= ::timeout r) (do (proc/destroy-tree p) {:outcome :timeout})
      (zero? (:exit r)) {:outcome :survived}
      (re-find #"(?m)^(FAIL|XPASS|DOC) " (:out r)) {:outcome :killed}
      :else {:outcome :error
             :error (let [s (str (:out r) (:err r))] (subs s (max 0 (- (count s) 600))))})))

(defn run-all [tmp jobs ms]
  (let [sem (java.util.concurrent.Semaphore. jobs)
        done (atom 0)]
    (->> ms
         (mapv (fn [m]
                 (future
                   (.acquire sem)
                   (try
                     (let [path (str (fs/path tmp (str "mutant-" (:id m) ".bb")))
                           result (try
                                    (spit path (:source m))
                                    (run-cases path)
                                    (catch Exception e
                                      {:outcome :error :error (ex-message e)}))
                           k (swap! done inc)]
                       (when (zero? (mod k 25))
                         (println (format "  %d/%d" k (count ms))))
                       (merge (dissoc m :source) result))
                     (finally (.release sem))))))
         (mapv deref))))

(defn source-line [source line]
  (str/trim (nth (str/split-lines source) (dec line) "")))

(defn equivalent-key [{:keys [source-line from to]}]
  [source-line from to])

(defn load-equivalents []
  (if (fs/exists? equivalent-file)
    (into {} (map (juxt equivalent-key identity))
          (clojure.edn/read-string (slurp equivalent-file)))
    {}))

(defn sample [seed n xs]
  (let [rnd (java.util.Random. seed)]
    (sort-by :id (take n (sort-by (fn [_] (.nextDouble rnd)) xs)))))

(defn print-survivor [{:keys [line col kind from to source-line]}]
  (println (format "  %4d:%-3d %-6s %s -> %s   | %s" line col (name kind) from to source-line)))

(let [{:keys [jobs limit seed out]} (parse-args *command-line-args*)
      source (slurp script)
      all (mapv #(assoc % :source-line (source-line source (:line %))) (mutants source))
      ms (if limit (sample seed limit all) all)
      equivalents (load-equivalents)
      tmp (fs/create-temp-dir {:prefix "clj-replace-mutate"})
      _ (.addShutdownHook (Runtime/getRuntime) (Thread. #(fs/delete-tree tmp)))
      results (try
                (if (not= :survived (:outcome (run-cases script)))
                  (do (println "The unmutated script does not pass the cases; fix that first.")
                      ::failed)
                  (do (println (format "%d mutants (%s), %d jobs" (count ms)
                                       (pr-str (frequencies (map :kind ms))) jobs))
                      (run-all tmp jobs ms)))
                (finally (fs/delete-tree tmp)))]
  (when (= ::failed results) (System/exit 1))
  (let [by (group-by :outcome results)
        survivors (sort-by :id (:survived by))
        known (filter #(equivalents (equivalent-key %)) survivors)
        new-survivors (remove #(equivalents (equivalent-key %)) survivors)
        errors (:error by)
        killed (+ (count (:killed by)) (count (:timeout by)))
        scored (- (count results) (count errors))
        stale (when-not limit
                (remove (set (map equivalent-key survivors)) (keys equivalents)))]
    (println)
    (when (seq known)
      (println (format "Known equivalent survivors (%d, see mutate-equivalent.edn):" (count known)))
      (doseq [s known] (print-survivor s)))
    (when (seq new-survivors)
      (println (format "NEW survivors (%d): add a case, or record why the mutant is equivalent:" (count new-survivors)))
      (doseq [s new-survivors] (print-survivor s)))
    (when (seq errors)
      (println (format "Errors (%d, not scored):" (count errors)))
      (doseq [{:keys [line col from to error]} errors]
        (println (format "  %d:%d %s -> %s: %s" line col from to (str/trim (str error))))))
    (when (seq stale)
      (println "Equivalent entries that no longer survive (remove them):")
      (doseq [k stale] (println "  " (pr-str k))))
    (println)
    (println (format "mutation score: %d/%d killed (%.1f%%); excluding %d known equivalent: %d/%d (%.1f%%); %d timed out, %d errors"
                     killed scored (* 100.0 (/ killed (max 1 scored)))
                     (count known) killed (- scored (count known))
                     (* 100.0 (/ killed (max 1 (- scored (count known)))))
                     (count (:timeout by)) (count errors)))
    (when out
      (spit out (pr-str {:script script :results (vec results)}))
      (println "report:" out))
    (System/exit (if (or (seq new-survivors) (seq errors)) 1 0))))
