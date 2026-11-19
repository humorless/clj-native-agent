#!/usr/bin/env bb
;; Black-box regression runner for clj-replace.bb.
;;
;; Usage: bb skills/clj-replace/test/run.bb [case-id ...]
;;
;; Set CLJ_REPLACE_FAIL_FAST to stop at the first FAIL or XPASS (used by
;; mutation testing, where one failure is enough).
;;
;; Set CLJ_REPLACE_SCRIPT to test another implementation of the script
;; against the same cases (default: ../scripts/clj-replace.bb).
;;
;; Each case in cases.edn writes :file to a temp .clj file, runs
;; clj-replace.bb on it with :old and :new, and checks the exit code and
;; the resulting file. When :expect has no :file and the expected exit is
;; non-zero, the file must be left unchanged.
;;
;; :args, when present, replaces the default [file old new] arguments;
;; the string "%FILE" in it stands for the temp file path.
;;
;; The run also fails when an exit code used in some case's :expect is not
;; listed under "## Exit Codes" in SKILL.md (a "- `N`" item or a "| N |"
;; table row), so the documented contract cannot fall behind the cases.
;;
;; :expect may also hold :output, a regex the script's output must match.
;;
;; Cases marked :known-bug true describe the correct behaviour of a bug
;; that is not fixed yet: they are expected to fail (XFAIL). Their :interim
;; map, same shape as :expect, pins what the script does today; a known-bug
;; case that matches neither is a FAIL. A known-bug case that matches
;; :expect is reported as XPASS and fails the run, so the marker gets removed.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def here (fs/parent (fs/absolutize *file*)))
(def script (or (System/getenv "CLJ_REPLACE_SCRIPT")
                (str (fs/path here ".." "scripts" "clj-replace.bb"))))
(def cases (edn/read-string (slurp (str (fs/path here "cases.edn")))))

(def skill-md (str (fs/path here ".." "SKILL.md")))

(defn documented-exit-codes
  "Exit codes listed in the ## Exit Codes section of SKILL.md."
  []
  (let [text (slurp skill-md)
        section (or (second (re-find #"(?s)## Exit Codes\n(.*?)(?:\n## |\z)" text)) "")]
    (set (keep (fn [line]
                 (when-let [[_ a b] (re-find #"^\s*(?:[-*]\s*`(\d+)`|\|\s*`?(\d+)`?\s*\|)" line)]
                   (parse-long (or a b))))
               (str/split-lines section)))))

(defn undocumented-exit-codes []
  (sort (remove (documented-exit-codes) (set (map (comp :exit :expect) cases)))))

(defn run-case [tmp {:keys [id file old new args missing-file?]}]
  (let [path (str (fs/path tmp (str (name id) ".clj")))
        argv (if args
               (map #(if (= "%FILE" %) path %) args)
               [path old new])]
    (when-not missing-file? (spit path file))
    (let [{:keys [exit out err]} (apply p/shell {:continue true :out :string :err :string}
                                        "bb" script argv)]
      {:exit exit
       :file (when (fs/exists? path) (slurp path))
       :output (str out err)})))

(defn expected-file [file expect]
  (if (contains? expect :file)
    (:file expect)
    (when (not= 0 (:exit expect)) file)))

(defn check
  "Problems found comparing actual against expect (a case's :expect or :interim)."
  [file expect actual]
  (let [want-file (expected-file file expect)]
    (cond-> []
      (not= (:exit expect) (:exit actual))
      (conj (str "exit: expected " (:exit expect) ", got " (:exit actual)))
      (and (some? want-file) (not= want-file (:file actual)))
      (conj (str "file: expected " (pr-str want-file) ", got " (pr-str (:file actual))))
      (and (:output expect) (not (re-find (re-pattern (:output expect)) (:output actual))))
      (conj (str "output: expected to match " (pr-str (:output expect)))))))

(defn evaluate
  "Returns [outcome problems]."
  [{:keys [file expect interim known-bug]} actual]
  (let [problems (check file expect actual)]
    (cond
      (and (not known-bug) (empty? problems)) [:pass []]
      (not known-bug) [:fail problems]
      (empty? problems) [:xpass []]
      (nil? interim) [:xfail []]
      :else (let [interim-problems (check file interim actual)]
              (if (empty? interim-problems)
                [:xfail []]
                [:fail (into ["known bug changed behaviour (:interim no longer matches):"]
                             interim-problems)])))))

(let [only (set (map keyword *command-line-args*))
      selected (cond->> cases (seq only) (filter (comp only :id)))
      tmp (fs/create-temp-dir {:prefix "clj-replace-test"})
      fail-fast? (some? (System/getenv "CLJ_REPLACE_FAIL_FAST"))
      results (reduce
               (fn [acc c]
                 (let [actual (run-case tmp c)
                       [o problems] (evaluate c actual)]
                   (println (format "%-6s %-14s %s" (str/upper-case (name o)) (name (:group c)) (name (:id c))))
                   (when (#{:fail :xpass} o)
                     (println "       " (:desc c))
                     (if (= o :xpass)
                       (println "        now passes: remove :known-bug from this case")
                       (doseq [pr problems] (println "       " pr)))
                     (when (and (= o :fail) (not (str/blank? (:output actual))))
                       (println "        output:" (str/trim (:output actual)))))
                   (if (and fail-fast? (#{:fail :xpass} o))
                     (reduced (conj acc o))
                     (conj acc o))))
               []
               selected)
      freq (frequencies results)
      undocumented (undocumented-exit-codes)]
  (fs/delete-tree tmp)
  (when (seq undocumented)
    (println (str "DOC    exit codes used in :expect but missing from SKILL.md ## Exit Codes: "
                  (str/join ", " undocumented))))
  (println)
  (println (format "%d cases: %d pass, %d fail, %d xfail (known bugs), %d xpass"
                   (count results) (:pass freq 0) (:fail freq 0) (:xfail freq 0) (:xpass freq 0)))
  (System/exit (if (or (pos? (+ (:fail freq 0) (:xpass freq 0))) (seq undocumented)) 1 0)))
