#!/usr/bin/env bb
;; Grades one exit 4 eval run.
;;
;; Usage: bb grade.bb <scenario-id> <run-dir>
;;
;; Reads <run-dir>/target.clj (the final file) and the call log written by
;; the stand-in script (<run-dir>.calls.edn next to the run directory, or
;; <run-dir>/calls.edn for older runs), prints one EDN map and exits 1 unless
;; :pass? is true. The run passes when:
;;   - the final file passes the scenario's text checks (:checks lists
;;     failures);
;;   - the first call used the scenario's :first-old / :first-new and got
;;     exit 4, and a later call got exit 0;
;;   - replaying the logged calls on the fixture with the run's
;;     real-clj-replace.bb gives exactly the final file (:replay-matches?),
;;     so the file was changed through clj-replace only; the script must
;;     still have the hash setup.bb recorded in <run-dir>.script.sha256.
;; :retried-with-file-text? (reported, not required) is true when a later
;; call's old-string is the file's text of the form, line by line.

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn squash [s] (str/trim (str/replace s #"\s+" " ")))

(defn lines
  "s with each line trimmed, inner whitespace runs collapsed, blank lines
  dropped: layout within a line is ignored, line breaks are not."
  [s]
  (->> (str/split-lines s)
       (map #(str/replace (str/trim %) #"\s+" " "))
       (remove str/blank?)
       (str/join "\n")))

(defn read-calls [dir]
  (let [outside (str (fs/path (fs/parent (fs/absolutize dir)) (str (fs/file-name dir) ".calls.edn")))
        inside (str (fs/path dir "calls.edn"))
        f (some #(when (fs/exists? %) %) [outside inside])]
    (if f
      (mapv edn/read-string (remove str/blank? (str/split-lines (slurp f))))
      [])))

(defn sha256 [path]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes path))))))

(defn script-unchanged?
  "False when setup.bb recorded the script's hash next to the run directory
  and real-clj-replace.bb no longer has it (the replay would be meaningless)."
  [dir]
  (let [recorded (str (fs/path (fs/parent (fs/absolutize dir)) (str (fs/file-name dir) ".script.sha256")))]
    (or (not (fs/exists? recorded))
        (= (str/trim (slurp recorded)) (sha256 (str (fs/path dir "real-clj-replace.bb")))))))

(defn replay
  "The fixture after replaying calls with the run's script."
  [scenario dir calls]
  (let [tmp (fs/create-temp-dir {:prefix "exit4-replay"})
        target (str (fs/path tmp "target.clj"))
        script (str (fs/path dir "real-clj-replace.bb"))]
    (try
      (spit target (:file scenario))
      (doseq [{[_ old new] :args} calls]
        (p/shell {:continue true :out :string :err :string} "bb" script target old new))
      (slurp target)
      (finally (fs/delete-tree tmp)))))

(let [[id dir] *command-line-args*
      here (fs/parent (fs/absolutize *file*))
      scenario (some #(when (= (keyword id) (:id %)) %)
                     (edn/read-string (slurp (str (fs/path here "scenarios.edn")))))
      _ (when-not (and scenario dir)
          (println "usage: bb grade.bb <scenario-id> <run-dir>")
          (System/exit 1))
      final (slurp (str (fs/path dir "target.clj")))
      calls (read-calls dir)
      [first-call & later] calls
      failed (vec (concat
                   (for [s (:must-contain scenario) :when (not (str/includes? (lines final) (lines s)))] [:missing s])
                   (for [s (:must-contain-squashed scenario) :when (not (str/includes? (squash final) (squash s)))] [:missing s])
                   (for [s (:must-not-contain scenario) :when (str/includes? (lines final) (lines s))] [:present s])
                   (for [s (:unchanged scenario) :when (not (str/includes? final s))] [:changed s])))
      first-ok? (boolean (and first-call
                              (= [(:first-old scenario) (:first-new scenario)] (vec (rest (:args first-call))))
                              (= 4 (:exit first-call))))
      later-ok? (boolean (some #(= 0 (:exit %)) later))
      script-ok? (script-unchanged? dir)
      replay-ok? (and script-ok? (= final (replay scenario dir calls)))
      pass? (boolean (and (empty? failed) first-ok? later-ok? replay-ok?))]
  (prn {:scenario (:id scenario)
        :pass? pass?
        :checks failed
        :first-call-as-dictated? (boolean first-ok?)
        :later-exit-0? later-ok?
        :script-unchanged? script-ok?
        :replay-matches? replay-ok?
        :calls (count calls)
        :exits (mapv :exit calls)
        :retried-with-file-text? (boolean (some #(= (lines (:near-text scenario)) (lines (get-in % [:args 1])))
                                                later))})
  (System/exit (if pass? 0 1)))
