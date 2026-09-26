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
  :empty, :too-long, :read or :unsupported."
  [src]
  (let [src (or src "")]
    (cond
      (> (count src) max-chars)
      {:error {:kind :too-long
               :message (str "That is " (format-count (count src)) " characters; paren reads up to "
                             (format-count max-chars) ". Try a smaller expression.")}}

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
