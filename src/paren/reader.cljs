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

(defn nesting
  "Deepest bracket nesting in src, skipping strings, comments and character
  literals such as \\(."
  [src]
  (let [n (count src)]
    (loop [i 0 depth 0 best 0 in-str? false]
      (if (>= i n)
        best
        (let [c (.charAt src i)]
          (cond
            in-str? (case c
                      "\\" (recur (+ i 2) depth best true)
                      "\"" (recur (inc i) depth best false)
                      (recur (inc i) depth best true))
            (= c "\"") (recur (inc i) depth best true)
            (= c ";") (let [j (.indexOf src "\n" i)]
                        (recur (if (neg? j) n j) depth best false))
            (= c "\\") (recur (+ i 2) depth best false)
            (or (= c "(") (= c "[") (= c "{")) (recur (inc i) (inc depth) (max best (inc depth)) false)
            (or (= c ")") (= c "]") (= c "}")) (recur (inc i) (max 0 (dec depth)) best false)
            :else (recur (inc i) depth best false)))))))

(defn format-count
  "12345 -> \"12,345\"."
  [n]
  (str/replace (str n) #"\B(?=(\d{3})+(?!\d))" ","))

(defn- where [{:keys [line column col]}]
  (let [c (or column col)]
    (when line
      (str " (line " line (when c (str ", column " c)) ")"))))

(defn- tidy [msg]
  (-> (str msg)
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
                      (throw (ex-info "syntax-quote (`) isn't in paren's teaching subset"
                                      {:paren/unsupported true})))]
            (loop [forms []]
              (let [form (r/read {:eof ::eof} rdr)]
                (if (= form ::eof)
                  (if (seq forms)
                    {:forms forms}
                    {:error {:kind :empty
                             :message "Nothing to evaluate: the input holds only comments or whitespace."}})
                  (recur (conj forms form))))))
          (catch :default e
            (let [d (ex-data e)]
              (if (:paren/unsupported d)
                {:error {:kind :unsupported :message (ex-message e)}}
                {:error {:kind :read
                         :message (str "Couldn't read that: " (tidy (ex-message e)) (where d) ".")}}))))))))
