#!/usr/bin/env bb
;; Grades one exit 4 eval run.
;;
;; Usage: bb grade.bb <scenario-id> <run-dir>
;;
;; Reads <run-dir>/target.clj (the final file) and <run-dir>/calls.edn
;; (written by clj-replace-logged.bb) and prints one EDN map:
;;   :pass?            every check on the final file holds
;;   :checks           which :must-contain / :must-not-contain / :unchanged
;;                     checks failed
;;   :calls            number of clj-replace calls, :exits their exit codes
;;   :first-exit       exit code of the first call (4 is the setup)
;;   :retried-with-file-text?  a later call used the file's text (up to
;;                     whitespace) as old-string, as the exit 4 message asks

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn squash [s] (str/trim (str/replace s #"\s+" " ")))

(let [[id dir] *command-line-args*
      here (fs/parent (fs/absolutize *file*))
      scenario (some #(when (= (keyword id) (:id %)) %)
                     (edn/read-string (slurp (str (fs/path here "scenarios.edn")))))
      _ (when-not (and scenario dir)
          (println "usage: bb grade.bb <scenario-id> <run-dir>")
          (System/exit 1))
      final (slurp (str (fs/path dir "target.clj")))
      calls-file (str (fs/path dir "calls.edn"))
      calls (if (fs/exists? calls-file)
              (mapv edn/read-string (remove str/blank? (str/split-lines (slurp calls-file))))
              [])
      failed (concat
              (for [s (:must-contain scenario) :when (not (str/includes? final s))] [:missing s])
              (for [s (:must-not-contain scenario) :when (str/includes? final s)] [:present s])
              (for [s (:unchanged scenario) :when (not (str/includes? final s))] [:changed s]))]
  (prn {:scenario (:id scenario)
        :pass? (empty? failed)
        :checks (vec failed)
        :calls (count calls)
        :exits (mapv :exit calls)
        :first-exit (:exit (first calls))
        :retried-with-file-text? (boolean (some #(= (squash (:near-text scenario)) (squash (get-in % [:args 1])))
                                                (rest calls)))}))
