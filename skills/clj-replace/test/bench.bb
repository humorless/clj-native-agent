#!/usr/bin/env bb
;; Performance guard for clj-replace.bb.
;;
;; Usage: bb bench [--baseline <script>] [--runs N] [--max-slowdown X] [--min-diff-ms M]
;;        (from the repository root; or bb skills/clj-replace/test/bench.bb ...)
;;
;; Generates its fixtures, runs each scenario --runs times (median wall
;; time) and fails when:
;;   - with --baseline: the script under test is more than --max-slowdown
;;     times slower than the baseline on some scenario and at least
;;     --min-diff-ms slower in absolute terms (both run on the same machine,
;;     interleaved in alternating order, so machine speed cancels out; the
;;     absolute floor keeps startup-sized noise from failing a run);
;;   - scaling: a file 4x larger takes more than 8x longer (no match), or
;;     matching the first of 20 forms nested 300 deep takes more than 2x as
;;     long as matching the first form of the large flat file. These catch
;;     algorithmic regressions (quadratic, nodes x depth) without a
;;     baseline. The deep check uses a match on the first form because a
;;     no-match run computes values for the near match, which is nodes x
;;     depth by nature in every version.
;; Startup time (a tiny file) is subtracted before the scaling ratios.
;; CLJ_REPLACE_SCRIPT selects the script under test.

(require '[babashka.fs :as fs]
         '[babashka.process :as proc]
         '[clojure.string :as str])

(def script (or (System/getenv "CLJ_REPLACE_SCRIPT")
                (str (fs/path (fs/parent (fs/absolutize *file*)) ".." "scripts" "clj-replace.bb"))))

(defn usage-error [msg]
  (println (str "bench: " msg))
  (println "usage: bb bench [--baseline <script>] [--runs N] [--max-slowdown X] [--min-diff-ms M]")
  (System/exit 1))

(def number-options
  {"--runs" [:runs parse-long]
   "--max-slowdown" [:max-slowdown parse-double]
   "--min-diff-ms" [:min-diff-ms parse-double]})

(defn parse-args [args]
  (loop [[a b & more :as args] args
         opts {:runs 7 :max-slowdown 1.3 :min-diff-ms 20.0}]
    (cond
      (empty? args) opts

      (= a "--baseline")
      (if b
        (recur more (assoc opts :baseline b))
        (usage-error "--baseline needs a path"))

      (number-options a)
      (let [[k parse] (number-options a)
            v (some-> b parse)]
        (if (and v (not (Double/isNaN (double v))) (pos? v))
          (recur more (assoc opts k v))
          (usage-error (str a " needs a positive number"))))

      :else (usage-error (str "unknown argument: " a)))))

;; ---------------------------------------------------------------- fixtures

(defn flat-form [i]
  (str "(defn f" i " [x]\n  (let [y (inc x)]\n    {:a y :b [x y \"s" i "\"]}))\n"))

(defn flat-file [n] (str/join "\n" (map flat-form (range n))))

(defn deep-form [i depth]
  (str "(def d" i " " (str/join (repeat depth "(a ")) "x" (str/join (repeat depth ")")) ")\n"))

(defn write-fixtures [dir]
  (let [f (fn [name content] (let [p (str (fs/path dir name))] (spit p content) p))]
    {:tiny (f "tiny.clj" "(defn f [x]\n  (inc x))\n")
     :small (f "small.clj" (flat-file 300))
     :large (f "large.clj" (flat-file 1200))
     :deep (f "deep.clj" (str/join "\n" (map #(deep-form % 300) (range 20))))}))

(defn scenarios [fx]
  [{:id :tiny :file (:tiny fx) :old "(nope)"}
   {:id :small-no-match :file (:small fx) :old "(nope)"}
   {:id :large-no-match :file (:large fx) :old "(nope)"}
   {:id :large-match-last :file (:large fx) :old (str/trim (flat-form 1199))}
   {:id :deep-no-match :file (:deep fx) :old "(nope)"}
   {:id :large-match-first :file (:large fx) :old (str/trim (flat-form 0))}
   {:id :deep-match-first :file (:deep fx) :old (str/trim (deep-form 0 300))}])

;; ---------------------------------------------------------------- timing

(defn time-run
  "Wall time in ms of one run on a fresh copy of file."
  [tmp script {:keys [id file old]}]
  (let [copy (str (fs/path tmp "work.clj"))]
    (fs/copy file copy {:replace-existing true})
    (let [start (System/nanoTime)
          {:keys [exit out err]} (proc/shell {:continue true :out :string :err :string}
                                             "bb" script copy old "(done)")
          ms (/ (- (System/nanoTime) start) 1e6)]
      (when-not (#{0 2} exit)
        (throw (ex-info (str "unexpected exit " exit " in scenario " (name id)
                             " (" file ") from " script "\n" out err)
                        {})))
      ms)))

(defn median [xs]
  (let [v (vec (sort xs))]
    (v (quot (count v) 2))))

(defn measure
  "[[scenario-id {:new ms :base ms}] ...] in scenario order. With a
  baseline, each iteration runs both scripts, alternating which goes first."
  [tmp scs runs baseline]
  (vec
   (for [sc scs]
     (let [pairs (vec (for [i (range runs)]
                        (if (and baseline (odd? i))
                          (let [b (time-run tmp baseline sc)] [(time-run tmp script sc) b])
                          [(time-run tmp script sc) (when baseline (time-run tmp baseline sc))])))]
       [(:id sc) {:new (median (map first pairs))
                  :base (when baseline (median (map second pairs)))}]))))

;; ---------------------------------------------------------------- checks

(defn check [results {:keys [baseline max-slowdown min-diff-ms]}]
  (let [by-id (into {} results)
        t (fn [id] (get-in by-id [id :new]))
        startup (t :tiny)
        net (fn [id] (max 1.0 (- (t id) startup)))
        size-ratio (/ (net :large-no-match) (net :small-no-match))
        deep-ratio (/ (net :deep-match-first) (net :large-match-first))]
    (concat
     (when baseline
       (for [[id {:keys [new base]}] results
             :let [r (/ new base)]
             :when (and (> r max-slowdown) (> (- new base) min-diff-ms))]
         (format "%s: %.0f ms vs baseline %.0f ms (%.2fx > %.2fx)" (name id) new base r max-slowdown)))
     (when (> size-ratio 8.0)
       [(format "scaling: 4x larger file took %.1fx longer (limit 8x)" size-ratio)])
     (when (> deep-ratio 2.0)
       [(format "scaling: matching in the deeply nested file took %.1fx the large flat file (limit 2x)" deep-ratio)]))))

(let [{:keys [baseline runs] :as opts} (parse-args *command-line-args*)
      _ (when-not (pos? runs) (usage-error "--runs must be at least 1"))
      tmp (fs/create-temp-dir {:prefix "clj-replace-bench"})
      results (try
                (measure tmp (scenarios (write-fixtures tmp)) runs baseline)
                (catch clojure.lang.ExceptionInfo e
                  (println (ex-message e))
                  ::failed)
                ;; System/exit skips finally, so exit only after this.
                (finally (fs/delete-tree tmp)))
      _ (when (= ::failed results) (System/exit 1))
      problems (check results opts)]
  (println (format "%-18s %10s %10s %8s" "scenario" "ms" "baseline" "ratio"))
  (doseq [[id {:keys [new base]}] results]
    (println (format "%-18s %10.0f %10s %8s" (name id) new
                     (if base (format "%.0f" base) "-")
                     (if base (format "%.2f" (/ new base)) "-"))))
  (if (seq problems)
    (do (doseq [p problems] (println "PERF  " p))
        (System/exit 1))
    (println "performance ok")))
