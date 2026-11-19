#!/usr/bin/env bb

(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}}})

(require '[rewrite-clj.zip :as z]
         '[rewrite-clj.node :as n]
         '[rewrite-clj.parser :as p]
         '[clojure.string :as str])

;; ---------------------------------------------------------------- canonical form
;; Two nodes match when their canonical strings are equal. The canonical
;; string is the node's own source with layout normalised: whitespace,
;; newline and comma nodes between children are dropped and one space is
;; put between the remaining children. Leaves (tokens, strings, regexes,
;; ...) keep their exact source text. Comments keep their text, minus
;; trailing whitespace, and end with a newline so they cannot swallow the
;; next child.

(defn layout? [nd]
  (contains? #{:whitespace :newline :comma} (n/tag nd)))

(def separator (n/token-node nil " "))

(defn canonical-node [nd]
  (cond
    (= :comment (n/tag nd))
    (n/token-node nil (str (str/trimr (n/string nd)) "\n"))

    (n/inner? nd)
    (n/replace-children nd (->> (n/children nd)
                                (remove layout?)
                                (map canonical-node)
                                (interpose separator)))

    :else nd))

(defn canonical [nd]
  (n/string (canonical-node nd)))

;; ---------------------------------------------------------------- input

(defn parse-forms
  "Parse s; returns {:ok true :nodes [...non-layout top-level nodes...]}
  or {:ok false :error msg}."
  [s label]
  (try
    {:ok true :nodes (vec (remove layout? (n/children (p/parse-string-all s))))}
    (catch Exception e
      {:ok false :error (str label " parse error: " (.getMessage e))})))

(defn one-form-problem
  "nil when nodes is exactly one form (not a comment), else a message."
  [label nodes]
  (when-not (and (= 1 (count nodes)) (not= :comment (n/tag (first nodes))))
    (str label " must contain exactly one form (found "
         (count (remove #(= :comment (n/tag %)) nodes)) " form(s) and "
         (count (filter #(= :comment (n/tag %)) nodes)) " top-level comment(s))")))

;; ---------------------------------------------------------------- search

(defn find-location [z]
  (try
    (let [[line col] (z/position z)]
      {:line line :column col})
    (catch Exception _
      {:line "?" :column "?"})))

(defn safe-sexpr [nd]
  (try {:ok true :value (n/sexpr nd)}
       (catch Exception _ {:ok false})))

(defn search
  "One pass over the file. Returns {:exact [...] :near [...]} where exact
  nodes have the target's canonical form and near nodes have the same
  value (n/sexpr + =) but a different canonical form."
  [file-zip target-node]
  (let [target-canon (canonical target-node)
        target-value (safe-sexpr target-node)]
    (loop [z file-zip
           exact []
           near []]
      (if (z/end? z)
        {:exact exact :near near}
        (let [nd (z/node z)
              hit {:z z :loc (find-location z)}]
          (cond
            (= target-canon (canonical nd))
            (recur (z/next z) (conj exact hit) near)

            (and (:ok target-value)
                 (let [v (safe-sexpr nd)]
                   (and (:ok v) (= (:value v) (:value target-value)))))
            (recur (z/next z) exact (conj near hit))

            :else
            (recur (z/next z) exact near)))))))

(defn fail [code & lines]
  (run! println lines)
  (System/exit code))

;; ---------------------------------------------------------------- main

(let [[filename old-string new-string] *command-line-args*]
  (when (or (nil? filename) (nil? old-string) (nil? new-string))
    (fail 1 "Usage: clj-replace.bb <filename> <old-string> <new-string>"))
  (when-not (.exists (java.io.File. filename))
    (fail 1 (str "✗ Error: File not found: " filename)))
  (let [old-parse (parse-forms old-string "old-string")
        new-parse (parse-forms new-string "new-string")]
    (when-not (:ok old-parse) (fail 1 (str "✗ " (:error old-parse))))
    (when-not (:ok new-parse) (fail 1 (str "✗ " (:error new-parse))))
    (when-let [msg (one-form-problem "old-string" (:nodes old-parse))]
      (fail 1 (str "✗ Error: " msg)))
    (when-not (or (str/blank? new-string) (nil? (one-form-problem "new-string" (:nodes new-parse))))
      (fail 1 (str "✗ Error: " (one-form-problem "new-string" (:nodes new-parse))
                   "; use a blank new-string to delete the matched form")))
    (let [file-zip (z/of-string (slurp filename) {:track-position? true})
          target (first (:nodes old-parse))
          new-node (if (str/blank? new-string)
                     (z/node (z/of-string new-string))
                     (first (:nodes new-parse)))
          {:keys [exact near]} (search file-zip target)]
      (cond
        (= 1 (count exact))
        (let [{:keys [z loc]} (first exact)]
          (spit filename (z/root-string (z/replace z new-node)))
          (println (str "✓ Replaced 1 node(s) in " filename))
          (println (str "  Location: line " (:line loc) ", column " (:column loc)))
          (println (str "  Old: " old-string))
          (println (str "  New: " new-string))
          (System/exit 0))

        (> (count exact) 1)
        (do
          (println (str "✗ Error: Found " (count exact) " matching S-expressions in " filename " (ambiguous)"))
          (doseq [[i {:keys [z loc]}] (map-indexed vector exact)]
            (println (str "  " (inc i) ". line " (:line loc) ", column " (:column loc) ": " (z/string z))))
          (println "Please provide a more specific old-string (e.g., include surrounding context)")
          (System/exit 3))

        (seq near)
        (do
          (println (str "✗ Error: No exact match in " filename
                        ", but these forms have the same value and differ in spelling, comments or metadata:"))
          (doseq [{:keys [z loc]} near]
            (println (str "  line " (:line loc) ", column " (:column loc) ":"))
            (println (z/string z)))
          (println "Retry with the file's text above as old-string; keep its comments and metadata in new-string if you mean to.")
          (System/exit 4))

        :else
        (fail 2 (str "✗ Error: No matching S-expression found in " filename))))))
