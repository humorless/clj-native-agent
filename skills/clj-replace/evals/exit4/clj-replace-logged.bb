#!/usr/bin/env bb
;; Stand-in for clj-replace.bb in exit 4 eval runs. Behaves exactly like the
;; real script (same output, same exit code) and appends each call's
;; arguments and exit code to <run-dir>.calls.edn, next to the run directory
;; and so outside it, so grade.bb can see what the agent did without relying
;; on its own report. setup.bb copies it without these comment lines.
;;
;; The real script is real-clj-replace.bb in the same directory, or
;; CLJ_REPLACE_SCRIPT.

(require '[babashka.fs :as fs]
         '[babashka.process :as p])

(let [here (fs/parent (fs/absolutize *file*))
      real (or (System/getenv "CLJ_REPLACE_SCRIPT")
               (str (fs/path here "real-clj-replace.bb")))
      {:keys [exit out err]} (apply p/shell {:continue true :out :string :err :string}
                                    "bb" real *command-line-args*)]
  (spit (str (fs/path (fs/parent here) (str (fs/file-name here) ".calls.edn")))
        (str (pr-str {:args (vec *command-line-args*) :exit exit}) "\n")
        :append true)
  (print out)
  (binding [*out* *err*] (print err))
  (flush)
  (System/exit exit))
