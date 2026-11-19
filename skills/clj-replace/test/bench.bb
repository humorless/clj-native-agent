#!/usr/bin/env bb
;; Performance guard for clj-replace.bb.
;;
;; Usage: bb bench [--baseline <script>] [--runs N] [--max-slowdown X]
;;        (from the repository root; or bb skills/clj-replace/test/bench.bb ...)
;;
;; Generates its fixtures, runs each scenario --runs times (median wall
;; time) and fails when:
;;   - with --baseline: the script under test is more than --max-slowdown
;;     times slower than the baseline on some scenario (both run on the same
;;     machine, interleaved, so machine speed cancels out);
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

(defn parse-args [args]
  (loop [[a b & more :as args] args opts {:runs 7 :max-slowdown 1.3}]
    (cond
      (empty? args) opts
      (= a "--baseline") (recur more (assoc opts :baseline b))
      (= a "--runs") (recur more (assoc opts :runs (parse-long b)))
      (= a "--max-slowdown") (recur more (assoc opts :max-slowdown (parse-double b)))
      :else (do (println "unknown argument:" a) (System/exit 1)))))

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
  [tmp script {:keys [file old]}]
  (let [copy (str (fs/path tmp "work.clj"))]
    (fs/copy file copy {:replace-existing true})
    (let [start (System/nanoTime)
          {:keys [exit out err]} (proc/shell {:continue true :out :string :err :string}
                                             "bb" script copy old "(done)")
          ms (/ (- (System/nanoTime) start) 1e6)]
      (when-not (#{0 2} exit)
        (println "unexpected exit" exit "for" script (:file old) (str out err))
        (System/exit 1))
      ms)))

(defn median [xs]
  (let [v (vec (sort xs))]
    (v (quot (count v) 2))))

(defn measure
  "{scenario-id {:new ms :base ms}}, runs interleaved."
  [tmp scs runs baseline]
  (into {}
        (for [sc scs]
          (let [pairs (doall (for [_ (range runs)]
                               [(time-run tmp script sc)
                                (when baseline (time-run tmp baseline sc))]))]
            [(:id sc) {:new (median (map first pairs))
                       :base (when baseline (median (map second pairs)))}]))))

;; ---------------------------------------------------------------- checks

(defn check [results {:keys [baseline max-slowdown]}]
  (let [t (fn [id] (get-in results [id :new]))
        startup (t :tiny)
        net (fn [id] (max 1.0 (- (t id) startup)))
        size-ratio (/ (net :large-no-match) (net :small-no-match))
        deep-ratio (/ (net :deep-match-first) (net :large-match-first))]
    (concat
     (when baseline
       (for [[id {:keys [new base]}] results
             :let [r (/ new base)]
             :when (> r max-slowdown)]
         (format "%s: %.0f ms vs baseline %.0f ms (%.2fx > %.2fx)" (name id) new base r max-slowdown)))
     (when (> size-ratio 8.0)
       [(format "scaling: 4x larger file took %.1fx longer (limit 8x)" size-ratio)])
     (when (> deep-ratio 2.0)
       [(format "scaling: matching in the deeply nested file took %.1fx the large flat file (limit 2x)" deep-ratio)]))))

(let [{:keys [baseline runs] :as opts} (parse-args *command-line-args*)
      tmp (fs/create-temp-dir {:prefix "clj-replace-bench"})
      results (measure tmp (scenarios (write-fixtures tmp)) runs baseline)
      problems (check results opts)]
  (fs/delete-tree tmp)
  (println (format "%-18s %10s %10s %8s" "scenario" "ms" "baseline" "ratio"))
  (doseq [[id {:keys [new base]}] results]
    (println (format "%-18s %10.0f %10s %8s" (name id) new
                     (if base (format "%.0f" base) "-")
                     (if base (format "%.2f" (/ new base)) "-"))))
  (if (seq problems)
    (do (doseq [p problems] (println "PERF  " p))
        (System/exit 1))
    (println "performance ok")))
