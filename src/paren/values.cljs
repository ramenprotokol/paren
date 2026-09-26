(ns paren.values
  "Runtime values of the teaching subset, truthiness, and the printer.

  Plain Clojure data (numbers, strings, keywords, nil, booleans, vectors,
  lists and maps) is used as-is. Three extra kinds of value exist:

  - `Closure`: a function made by `fn`/`defn`, remembering its environment.
  - `Builtin`: one of paren's built-in functions (`+`, `map`, ...).
  - `DefVar`: what `def` returns, printed like Clojure's `#'user/x`."
  (:require [clojure.string :as str]))

;; A closure compares by identity, like a Clojure function.
(deftype Closure [name params rest body env])

(defrecord Builtin [name])

(defrecord DefVar [name])

(defn closure? [v] (instance? Closure v))

(defn fn-name [^Closure c] (.-name c))
(defn fn-params [^Closure c] (.-params c))
(defn fn-rest [^Closure c] (.-rest c))
(defn fn-body [^Closure c] (.-body c))
(defn fn-env [^Closure c] (.-env c))
(defn builtin? [v] (instance? Builtin v))
(defn def-var? [v] (instance? DefVar v))

(defn truthy?
  "Clojure truth: only nil and false are falsy."
  [v]
  (not (or (nil? v) (false? v))))

(defn params-str [^Closure c]
  (str "["
       (str/join " " (cond-> (mapv str (.-params c))
                       (.-rest c) (conj "&" (str (.-rest c)))))
       "]"))

(defn closure-label
  "How a closure prints: ‹fn fib› when it has a name, ‹fn [x]› when not."
  [^Closure c]
  (if (.-name c)
    (str "‹fn " (.-name c) "›")
    (str "‹fn " (params-str c) "›")))

(defn- show-number [n]
  (cond
    (js/isNaN n) "##NaN"
    (== n js/Infinity) "##Inf"
    (== n (- js/Infinity)) "##-Inf"
    :else (str n)))

(defn- write!
  "Appends the printed form of v to `buf` (a JS array), stopping once the
  running length in `len` (a volatile) passes `limit`, so printing a huge
  value stays cheap."
  [buf len v limit]
  (let [emit (fn [s]
               (when (< @len limit)
                 (.push buf s)
                 (vswap! len + (count s))))
        again #(write! buf len % limit)
        many (fn [open items close sep write-item]
               (emit open)
               (loop [xs (seq items) first? true]
                 (when (and xs (< @len limit))
                   (when-not first? (emit sep))
                   (write-item (first xs))
                   (recur (next xs) false)))
               (emit close))]
    (cond
      (nil? v) (emit "nil")
      (boolean? v) (emit (str v))
      (number? v) (emit (show-number v))
      (string? v) (emit (pr-str v))
      (keyword? v) (emit (str v))
      (symbol? v) (emit (str v))
      (closure? v) (emit (closure-label v))
      (builtin? v) (emit (:name v))
      (def-var? v) (emit (str "#'user/" (:name v)))
      (map? v) (many "{" v "}" ", " (fn [[k x]] (again k) (emit " ") (again x)))
      (vector? v) (many "[" v "]" " " again)
      (or (seq? v) (list? v)) (many "(" v ")" " " again)
      (set? v) (many "#{" v "}" " " again)
      :else (emit (str v)))))

(defn show
  "Prints a value the way the Clojure REPL would (closures aside).
  With a limit, the result is cut to at most `limit` characters plus an
  ellipsis."
  ([v] (show v 100000))
  ([v limit]
   (let [buf #js []]
     (write! buf (volatile! 0) v (inc limit))
     (let [s (.join buf "")]
       (if (> (count s) limit)
         (str (subs s 0 limit) "…")
         s)))))

(defn type-name
  "A plain-English name for the kind of a value, used in error messages."
  [v]
  (cond
    (nil? v) "nil"
    (boolean? v) "a boolean"
    (number? v) "a number"
    (string? v) "a string"
    (keyword? v) "a keyword"
    (closure? v) "a function"
    (builtin? v) "a function"
    (def-var? v) "a var"
    (map? v) "a map"
    (vector? v) "a vector"
    (or (seq? v) (list? v)) "a list"
    :else "an unknown kind of value"))
