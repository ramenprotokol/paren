(ns paren.reader
  "Reads the learner's text into Clojure data with cljs.tools.reader (the
  real Clojure reader, ported to ClojureScript). Code is data: everything
  after this point works on the lists, vectors and symbols read here."
  (:require [cljs.tools.reader :as r]
            [cljs.tools.reader.reader-types :as rt]
            [clojure.string :as str]))

(def max-chars
  "Longest input paren will read. paren is for small, watchable programs."
  2000)

(def max-nesting
  "Deepest bracket nesting paren will read. Real teaching programs stay far
  below this; the cap keeps the reader and the stepper's recursion safe."
  50)

(def max-read-depth
  "Deepest the read forms may nest, counting every list, vector and map.
  Quote marks nest a form without a bracket ('x reads as (quote x), ~x as
  (unquote x)), so the bracket cap alone doesn't bound this, and parsing
  recurses once per level."
  (* 2 max-nesting))

(defn read-depth
  "Deepest nesting of collections in the read forms. Walks with its own
  stack, so a deep form can't overflow the call stack."
  [forms]
  (let [colls (fn [d] (comp (filter coll?) (map (fn [x] [x d]))))]
    (loop [todo (into [] (colls 1) forms) best 0]
      (if-let [[x d] (peek todo)]
        (recur (into (pop todo) (colls (inc d)) (if (map? x) (mapcat identity x) x))
               (max best d))
        best))))

(defn scan
  "One pass over src, skipping strings, comments and character literals
  such as \\( and \\`. Returns {:depth d :backtick? b}: the deepest bracket
  nesting, and whether a syntax-quote backtick appears in the code."
  [src]
  (let [n (count src)]
    (loop [i 0 depth 0 best 0 in-str? false tick? false]
      (if (>= i n)
        {:depth best :backtick? tick?}
        (let [c (.charAt src i)]
          (cond
            in-str? (case c
                      "\\" (recur (+ i 2) depth best true tick?)
                      "\"" (recur (inc i) depth best false tick?)
                      (recur (inc i) depth best true tick?))
            (= c "\"") (recur (inc i) depth best true tick?)
            (= c ";") (let [j (.indexOf src "\n" i)]
                        (recur (if (neg? j) n j) depth best false tick?))
            (= c "\\") (recur (+ i 2) depth best false tick?)
            (= c "`") (recur (inc i) depth best false true)
            (or (= c "(") (= c "[") (= c "{")) (recur (inc i) (inc depth) (max best (inc depth)) false tick?)
            (or (= c ")") (= c "]") (= c "}")) (recur (inc i) (max 0 (dec depth)) best false tick?)
            :else (recur (inc i) depth best false tick?)))))))

(defn nesting
  "Deepest bracket nesting in src (see scan)."
  [src]
  (:depth (scan src)))

(def syntax-quote-message
  "Syntax-quote (the backtick) isn't in paren's teaching subset; quote data with ' instead, as in '(1 2 3).")

(defn format-count
  "12345 -> \"12,345\"."
  [n]
  (str/replace (str n) #"\B(?=(\d{3})+(?!\d))" ","))

(defn- where [{:keys [line column col]}]
  (let [c (or column col)]
    (when line
      (str " (line " line (when c (str ", column " c)) ")"))))

(defn- tidy
  "The reader's message without its own \"input [line 1, col 9]\" prefix
  (paren adds the position once, at the end) or final full stop."
  [msg]
  (-> (str msg)
      (str/replace #"^input \[line \d+, col \d+\]:?\s*" "")
      (str/replace #"\.$" "")))

(defn read-program
  "Reads every top-level form in `src`.
  Returns {:forms [...]} or {:error {:kind k :message m}}, where k is one of
  :empty, :too-long, :too-deep, :read or :unsupported."
  [src]
  (let [src (or src "")]
    (cond
      (> (count src) max-chars)
      {:error {:kind :too-long
               :message (str "That is " (format-count (count src)) " characters; paren reads up to "
                             (format-count max-chars) ". Try a smaller expression.")}}

      (> (nesting src) max-nesting)
      {:error {:kind :too-deep
               :message (str "That expression is nested " (nesting src) " brackets deep; paren reads up to "
                             max-nesting ". Try a flatter expression.")}}

      ;; `(1 2) never calls resolve-symbol below, and would otherwise be
      ;; reported by the name of what the reader expands it into.
      (:backtick? (scan src))
      {:error {:kind :unsupported :message syntax-quote-message}}

      (str/blank? src)
      {:error {:kind :empty
               :message "Nothing to evaluate yet. Type an expression or pick a preset."}}

      :else
      (let [rdr (rt/indexing-push-back-reader src 1 "input")]
        (try
          (binding [r/*data-readers* {}
                    r/*default-data-reader-fn* nil
                    r/*alias-map* {}
                    r/resolve-symbol
                    (fn [_]
                      (throw (ex-info syntax-quote-message {:paren/unsupported true})))]
            (loop [forms []]
              (let [form (r/read {:eof ::eof} rdr)]
                (if (= form ::eof)
                  (if (seq forms)
                    (let [d (read-depth forms)]
                      (if (> d max-read-depth)
                        {:error {:kind :too-deep
                                 :message (str "That expression is nested " d " levels deep once quote marks such as ' and ~ "
                                               "are counted; paren reads up to " max-read-depth ". Try a flatter expression.")}}
                        {:forms forms}))
                    {:error {:kind :empty
                             :message "Nothing to evaluate: the input holds only comments or whitespace."}})
                  (recur (conj forms form))))))
          (catch :default e
            (let [d (ex-data e)]
              (cond
                (:paren/unsupported d)
                {:error {:kind :unsupported :message (ex-message e)}}

                ;; The reader recurses once per quote mark too; far past the
                ;; cap above it runs out of stack before read-depth can look.
                ;; (It wraps the RangeError in its own reader error.)
                (some #(instance? js/RangeError %) (take-while some? (iterate ex-cause e)))
                {:error {:kind :too-deep
                         :message (str "That expression is nested too deeply to read; paren reads up to "
                                       max-read-depth " levels. Try a flatter expression.")}}

                :else
                {:error {:kind :read
                         :message (str "Couldn't read that: " (tidy (ex-message e)) (where d) ".")}}))))))))
