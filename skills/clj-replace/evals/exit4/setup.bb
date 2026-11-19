#!/usr/bin/env bb
;; Sets up one exit 4 eval run and prints the prompt to give the agent.
;;
;; Usage: bb setup.bb <scenario-id> <with|without> <run-dir> [script]
;;
;; Creates <run-dir> with target.clj (the scenario's fixture), clj-replace.bb
;; (the logging stand-in, comments stripped) and real-clj-replace.bb (script,
;; default ../../scripts/clj-replace.bb), records its SHA-256 in
;; <run-dir>.script.sha256, removes an old <run-dir>.calls.edn,
;; and prints prompt.txt filled in; with "with", prefixed by
;; prompt-skill-prefix.txt holding ../../SKILL.md. Give the printed prompt to
;; a fresh agent, then grade with: bb grade.bb <scenario-id> <run-dir>

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn fill [template m]
  (reduce-kv (fn [s k v] (str/replace s (str "{{" (name k) "}}") v)) template m))

(let [[id condition dir script] *command-line-args*
      here (fs/parent (fs/absolutize *file*))
      scenario (some #(when (= (keyword id) (:id %)) %)
                     (edn/read-string (slurp (str (fs/path here "scenarios.edn")))))
      _ (when-not (and scenario (#{"with" "without"} condition) dir)
          (println "usage: bb setup.bb <scenario-id> <with|without> <run-dir> [script]")
          (System/exit 1))
      dir (str (fs/absolutize dir))
      script (or script (str (fs/path here ".." ".." "scripts" "clj-replace.bb")))
      stand-in (->> (str/split-lines (slurp (str (fs/path here "clj-replace-logged.bb"))))
                    (remove #(str/starts-with? % ";;"))
                    (str/join "\n"))]
  (fs/create-dirs dir)
  (spit (str (fs/path dir "target.clj")) (:file scenario))
  (spit (str (fs/path dir "clj-replace.bb")) stand-in)
  (fs/copy script (fs/path dir "real-clj-replace.bb") {:replace-existing true})
  (fs/delete-if-exists (str dir ".calls.edn"))
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (spit (str dir ".script.sha256")
          (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes script))))))
  (print (str (when (= "with" condition)
                (fill (slurp (str (fs/path here "prompt-skill-prefix.txt")))
                      {:skill (slurp (str (fs/path here ".." ".." "SKILL.md")))}))
              (fill (slurp (str (fs/path here "prompt.txt")))
                    {:dir dir :goal (:goal scenario)
                     :first-old (:first-old scenario) :first-new (:first-new scenario)})))
  (flush))
