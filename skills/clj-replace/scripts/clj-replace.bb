#!/usr/bin/env bb

(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {rewrite-clj/rewrite-clj {:mvn/version "1.1.47"}}})

(require '[rewrite-clj.zip :as z]
         '[rewrite-clj.node :as n]
         '[rewrite-clj.parser :as p]
         '[clojure.string :as str]
         '[clojure.walk :as walk])

;; ---------------------------------------------------------------- comparison
;; Two nodes are written the same way when they differ only in layout:
;; whitespace, newline and comma nodes between children. Inner nodes need
;; the same tag, the same delimiters ("(" ")", "#(" ")", "^", ...) and the
;; same non-layout children in order; leaves (tokens, strings, regexes, ...)
;; need the same source text; comments the same text up to trailing
;; whitespace. The comparison stops at the first difference, so most nodes
;; are rejected by their tag or child count.

(defn layout? [nd]
  (contains? #{:whitespace :newline :comma} (n/tag nd)))

(defn significant-children [nd]
  (into [] (remove layout?) (n/children nd)))

(defn delimiters
  "The node's own text without its children, e.g. \"()\" or \"#()\".
  Some nodes cannot render without children (a namespaced map needs its
  qualifier and map); their text lives entirely in the children, which
  are compared anyway, so they have no delimiters of their own."
  [nd]
  (try
    (n/string (n/replace-children nd []))
    (catch Exception _ ::children-only)))

(defn leaf-text [nd]
  (if (= :comment (n/tag nd))
    (str/trimr (n/string nd))
    (n/string nd)))

(defn significant-count [nd]
  (reduce (fn [k c] (if (layout? c) k (inc k))) 0 (n/children nd)))

(defn same-source? [a b]
  (and (= (n/tag a) (n/tag b))
       (if (n/inner? a)
         (and (n/inner? b)
              (= (significant-count a) (significant-count b))
              (= (delimiters a) (delimiters b))
              (every? true? (map same-source? (significant-children a) (significant-children b))))
         (and (not (n/inner? b))
              (= (leaf-text a) (leaf-text b))))))

;; ---------------------------------------------------------------- near match
;; Values compared with n/sexpr and =, only to choose the exit 4 message.
;; #() reads to fresh gensyms (p1__123#) on every call, so they are renamed
;; in order of appearance before comparing.

(defn- gensym-symbol? [x]
  (and (symbol? x) (re-matches #"(p\d+|rest)__\d+#" (name x))))

(defn stable-gensyms [form]
  (let [names (volatile! {})]
    (walk/prewalk
     (fn [x]
       (if (gensym-symbol? x)
         (or (get @names x)
             (let [s (symbol (str "%" (count @names)))]
               (vswap! names assoc x s)
               s))
         x))
     form)))

(defn has-gensym? [form]
  (boolean (some gensym-symbol? (tree-seq coll? seq form))))

(defn safe-value
  "{:ok true :value v} with n/sexpr's value, gensyms made stable when
  stable? is true; {:ok false} when the node has no value (e.g. #_)."
  [nd stable?]
  (try (let [v (n/sexpr nd)]
         {:ok true :value (if stable? (stable-gensyms v) v)})
       (catch Exception _ {:ok false})))

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

(defn search
  "One walk over the file. Returns {:exact [...] :near [...]}: exact nodes
  are written like target up to layout; near nodes have the same value but
  are written differently. Values are compared only until the first exact
  match, since near matches matter only when there is none."
  [file-zip target]
  (let [target-tag (n/tag target)
        raw (safe-value target false)
        ;; Gensyms only need renaming when old-string has a #(); doing it
        ;; for every node would cost time proportional to depth.
        stable? (and (:ok raw) (has-gensym? (:value raw)))
        target-value (if stable? (safe-value target true) raw)]
    (loop [z file-zip
           exact []
           near []]
      (if (z/end? z)
        {:exact exact :near (if (seq exact) [] near)}
        (let [nd (z/node z)]
          (cond
            (and (= target-tag (n/tag nd)) (same-source? target nd))
            (recur (z/next z) (conj exact {:z z :loc (find-location z)}) near)

            (and (empty? exact)
                 (:ok target-value)
                 (let [v (safe-value nd stable?)]
                   (and (:ok v) (= (:value v) (:value target-value)))))
            (recur (z/next z) exact (conj near {:z z :loc (find-location z)}))

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
    (when-not (or (empty? (:nodes new-parse)) (nil? (one-form-problem "new-string" (:nodes new-parse))))
      (fail 1 (str "✗ Error: " (one-form-problem "new-string" (:nodes new-parse))
                   "; use a blank new-string to delete the matched form")))
    (let [file-zip (try
                     (z/of-string (slurp filename) {:track-position? true})
                     (catch Exception e
                       (fail 1 (str "✗ file parse error: " (.getMessage e)))))
          target (first (:nodes old-parse))
          new-node (if (empty? (:nodes new-parse))
                     (n/forms-node [])
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
