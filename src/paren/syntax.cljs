(ns paren.syntax
  "Turns read Clojure data into the stepper's expression tree, checking that
  every form is in paren's teaching subset. Anything outside the subset is
  rejected here, with a message that names it, before a single step runs.

  Tree nodes are maps with a :t key:
    :val    an evaluated value            {:v}
    :sym    a symbol to look up           {:s}
    :call   a function call               {:f :args}
    :if :cond :and :or :do :let :fn :def :quote   special forms
    :vec :map                             collection literals still being evaluated
  The stepper adds :scope (a function body being run), :seq (a list being
  built by map/filter), :pick (one filter test) and :reduce."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def special-forms
  '#{def defn fn fn* let let* if cond do and or quote})

(def ^:private hints
  {"loop" "write it as plain recursion instead"
   "recur" "write it as plain recursion instead"
   "when" "write (if test (do …)) instead"
   "when-not" "write (if test nil (do …)) instead"
   "if-not" "swap the branches of an if instead"
   "if-let" "use let and if instead"
   "when-let" "use let and if instead"
   "case" "use cond instead"
   "condp" "use cond instead"
   "->" "write the calls nested instead"
   "->>" "write the calls nested instead"
   "for" "use map and filter instead"
   "doseq" "use map or recursion instead"
   "dotimes" "use recursion instead"
   "while" "use recursion instead"
   "println" "paren shows values, not printed output"
   "prn" "paren shows values, not printed output"
   "print" "paren shows values, not printed output"
   "atom" "the subset has no mutable state"
   "swap!" "the subset has no mutable state"
   "reset!" "the subset has no mutable state"
   "deref" "the subset has no mutable state"
   "set!" "the subset has no mutable state"
   "defmacro" "macros are on paren's Next list"
   "lazy-seq" "lazy sequences are on paren's Next list"
   "iterate" "lazy sequences are on paren's Next list"
   "repeat" "lazy sequences are on paren's Next list"
   "cycle" "lazy sequences are on paren's Next list"
   "letfn" "use let with fn instead"
   "apply" "call the function directly instead"
   "second" "use (first (rest xs)) instead"})

(def unsupported-names
  "Real Clojure names that paren does not implement. They get a clear
  message instead of a confusing \"unable to resolve\" (unless the program
  defines the name itself). Kept as one string: a set of ~150 symbol
  literals would cost several KB of bundle."
  (into (set (keys hints))
        (str/split (str/trim "when-some if-some when-first cond-> cond->> as-> some-> some->> doto try
         catch finally throw new defmulti defmethod defprotocol defrecord deftype
         definterface reify proxy ns require import use binding lazy-cat delay
         future var comment declare defonce defn- fn? take drop take-while
         drop-while take-nth partition partition-by partition-all interleave
         interpose concat into sort sort-by group-by frequencies comp partial
         juxt identity constantly some every? not-every? not-any? keep
         keep-indexed remove mapcat map-indexed mapv filterv reverse last butlast
         ffirst fnext nnext next nthrest nthnext seq vals keys merge merge-with
         select-keys update update-in assoc-in get-in dissoc disj contains? find
         hash-map hash-set sorted-map sorted-set set vec subs printf format
         re-find re-matches re-seq re-pattern rand rand-int rand-nth shuffle
         trampoline memoize doall dorun realized? transduce sequence zipmap
         distinct dedupe flatten reduced reduced? reduce-kv run! every-pred
         some-fn fnil keyword symbol name namespace int double long char boolean
         number? string? keyword? symbol? map? vector? list? seq? coll? integer?
         true? false? some? peek pop subvec compare abs max-key min-key
         repeatedly pr prn-str pr-str print-str eval quot* time assert")
                   #"\s+")))

(defn fail!
  "Throws a parse error. kind is :unsupported or :syntax."
  [kind msg]
  (throw (ex-info msg {:paren/kind kind})))

(defn- unsupported-name! [s]
  (fail! :unsupported
         (str "`" s "` isn't in paren's teaching subset"
              (when-let [h (hints (str s))] (str " (" h ")"))
              ".")))

(defn- norm
  "clojure.core/inc and cljs.core/inc mean plain inc."
  [s]
  (if (#{"clojure.core" "cljs.core"} (namespace s))
    (symbol (name s))
    s))

(defn val-node [x] {:t :val :v x})

(defn- val-node? [n] (= :val (:t n)))

(defn- bound-names
  "Every name the program binds anywhere (def, defn, fn and let names).
  A program may define its own `range` or `last`; those are allowed."
  [forms]
  (into #{}
        (comp
         (filter #(and (seq? %) (symbol? (first %))))
         (mapcat (fn [[h a b]]
                   (case (norm h)
                     (def defn) (when (symbol? a) [a])
                     (fn fn*) (concat (when (symbol? a) [a])
                                      (filter symbol? (cond (vector? a) a (vector? b) b)))
                     (let let*) (when (vector? a) (filter symbol? (take-nth 2 a)))
                     nil))))
        (tree-seq coll? seq forms)))

(declare parse)

(defn- parse-body [ctx forms] (mapv #(parse ctx %) forms))

(defn- plain-name? [s]
  (and (symbol? s) (nil? (namespace s)) (not= s '&)))

(defn- destructuring! [where form]
  (fail! :unsupported
         (str "Destructuring (" (pr-str form) ") in " where
              " isn't in paren's teaching subset; bind a single name instead.")))

(defn- parse-params [params]
  (loop [ps (seq params) out []]
    (cond
      (nil? ps) [out nil]

      (= '& (first ps))
      (let [r (second ps)]
        (cond
          (or (vector? r) (map? r)) (destructuring! "parameters" r)
          (and (plain-name? r) (nil? (nnext ps))) [out r]
          :else (fail! :syntax "`&` in a parameter list must be followed by exactly one name.")))

      (plain-name? (first ps)) (recur (next ps) (conj out (first ps)))
      (or (vector? (first ps)) (map? (first ps))) (destructuring! "parameters" (first ps))
      :else (fail! :syntax (str "Parameters must be names, but found " (pr-str (first ps)) ".")))))

(defn- fn-node [ctx name params body]
  (let [[ps r] (parse-params params)]
    {:t :fn :name name :params ps :rest r :body (parse-body ctx body)}))

(defn- anon-name
  "#(…) reads as (fn* [p1__12#] …). Give its parameters the names the
  learner typed: %, %1, %2 … and %&."
  [params]
  (let [positional (take-while #(not= '& %) params)
        single? (= 1 (count positional))]
    (into {}
          (keep (fn [p]
                  (when-let [[_ kind n] (re-matches #"(p|rest)(\d*)__\d+#" (str p))]
                    [p (symbol (cond (= kind "rest") "%&" single? "%" :else (str "%" n)))])))
          params)))

(defn- multi-arity! [what]
  (fail! :unsupported
         (str "Multi-arity functions (" what " with several parameter lists) aren't in paren's teaching subset.")))

(defn- parse-fn [ctx head args]
  (let [[a & more] args
        [name params body] (if (symbol? a) [a (first more) (rest more)] [nil a more])]
    (cond
      (seq? params) (multi-arity! "`fn`")
      (not (vector? params)) (fail! :syntax "`fn` needs a parameter vector, like (fn [x] (* x x)).")
      (= head 'fn*)
      (let [m (anon-name params)]
        (fn-node ctx name (walk/postwalk-replace m params) (walk/postwalk-replace m body)))
      :else (fn-node ctx name params body))))

(defn- quoted-data! [x]
  (cond
    (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x)) x
    (symbol? x) (fail! :unsupported (str "Quoted symbols ('" x ") aren't in paren's teaching subset; quote data such as '(1 2 3) instead."))
    (or (vector? x) (seq? x)) (do (run! quoted-data! x) x)
    (map? x) (do (run! quoted-data! (keys x)) (run! quoted-data! (vals x)) x)
    (set? x) (fail! :unsupported "Sets (#{…}) aren't in paren's teaching subset.")
    :else (fail! :unsupported (str "This kind of literal (" (pr-str x) ") isn't in paren's teaching subset."))))

(defn- parse-special [ctx h args]
  (case h
    def
    (let [[name & more] args
          more (if (and (string? (first more)) (next more)) (rest more) more)]
      (when-not (plain-name? name)
        (fail! :syntax "`def` needs a name first, like (def x 1)."))
      (case (count more)
        0 (fail! :syntax (str "`def` needs a value too, like (def " name " 1)."))
        1 {:t :def :kind :def :name name :init (parse ctx (first more))}
        (fail! :syntax (str "`def` takes a name and one value, but got " (count more) " values."))))

    defn
    (let [[name & more] args
          _ (when-not (plain-name? name)
              (fail! :syntax "`defn` needs a name first, like (defn square [x] (* x x))."))
          more (if (and (string? (first more)) (next more)) (rest more) more)
          more (if (and (map? (first more)) (next more)) (rest more) more)]
      (cond
        (seq? (first more)) (multi-arity! "`defn`")
        (not (vector? (first more)))
        (fail! :syntax (str "`defn " name "` needs a parameter vector, like (defn " name " [x] …)."))
        :else {:t :def :kind :defn :name name :init (fn-node ctx name (first more) (rest more))}))

    (fn fn*) (parse-fn ctx h args)

    (let let*)
    (let [[bv & body] args]
      (when-not (vector? bv)
        (fail! :syntax "`let` needs a vector of bindings, like (let [x 1] (* x 2))."))
      (when (odd? (count bv))
        (fail! :syntax "`let` bindings come in pairs: a name, then its value."))
      {:t :let
       :n-bound 0
       :bindings (mapv (fn [[s e]]
                         (cond
                           (plain-name? s) [s (parse ctx e)]
                           (or (vector? s) (map? s)) (destructuring! "`let`" s)
                           :else (fail! :syntax (str "`let` can only bind names, but found " (pr-str s) "."))))
                       (partition 2 bv))
       :body (parse-body ctx body)})

    if
    (case (count args)
      (0 1) (fail! :syntax "`if` needs a test and a then-branch, like (if test then else).")
      (2 3) {:t :if
             :test (parse ctx (first args))
             :then (parse ctx (second args))
             :else (when (= 3 (count args)) (parse ctx (nth args 2)))}
      (fail! :syntax (str "`if` takes a test, a then-branch and an optional else-branch, but got "
                          (count args) " parts.")))

    cond
    (if (odd? (count args))
      (fail! :syntax "`cond` needs test/result pairs, but it has an odd number of forms.")
      {:t :cond :clauses (mapv (fn [[t e]] [(parse ctx t) (parse ctx e)]) (partition 2 args))})

    do {:t :do :body (parse-body ctx args)}
    and {:t :and :items (parse-body ctx args)}
    or {:t :or :items (parse-body ctx args)}

    quote
    (if (= 1 (count args))
      {:t :quote :v (quoted-data! (first args))}
      (fail! :syntax "`quote` takes exactly one form."))))

(defn parse
  "One read form -> one tree node. Throws ex-info with :paren/kind on
  anything outside the subset."
  [ctx form]
  (cond
    (or (nil? form) (boolean? form) (number? form) (string? form) (keyword? form))
    (val-node form)

    (symbol? form)
    (let [s (norm form)]
      (cond
        (namespace s)
        (fail! :unsupported (str "Namespaced symbols such as `" form "` aren't in paren's teaching subset."))
        (special-forms s)
        (fail! :syntax (str "`" s "` is a special form, so it only works at the start of a list, like (" s " …)."))
        (= s '&) (fail! :syntax "`&` only belongs in a parameter list.")
        (and (unsupported-names (str s)) (not ((:bound ctx) s))) (unsupported-name! s)
        :else {:t :sym :s s}))

    (seq? form)
    (if (empty? form)
      (val-node ())
      (let [[head & args] form
            h (when (symbol? head) (norm head))]
        (if (special-forms h)
          (parse-special ctx h args)
          {:t :call :f (parse ctx head) :args (parse-body ctx args)})))

    (vector? form)
    (let [items (parse-body ctx form)]
      (if (every? val-node? items)
        (val-node (mapv :v items))
        {:t :vec :items items}))

    (map? form)
    (let [entries (mapv (fn [[k x]] [(parse ctx k) (parse ctx x)]) form)]
      (if (every? (fn [[k x]] (and (val-node? k) (val-node? x))) entries)
        (val-node (into {} (map (fn [[k x]] [(:v k) (:v x)])) entries))
        {:t :map :entries entries}))

    (set? form) (fail! :unsupported "Sets (#{…}) aren't in paren's teaching subset.")
    (regexp? form) (fail! :unsupported "Regular expressions (#\"…\") aren't in paren's teaching subset.")
    :else (fail! :unsupported (str "This kind of literal (" (pr-str form) ") isn't in paren's teaching subset."))))

(defn parse-program
  "Read forms -> {:tree top-node} or {:error {:kind :message}}."
  [forms]
  (try
    (let [ctx {:bound (bound-names forms)}]
      {:tree {:t :top :forms (mapv #(parse ctx %) forms)}})
    (catch :default e
      (if-let [kind (:paren/kind (ex-data e))]
        {:error {:kind kind :message (ex-message e)}}
        (throw e)))))

(defn- subset-line [label xs]
  (str label ": " (str/join " " xs)))

(def summary
  "Plain-text summary of the subset, shared with the page."
  [(subset-line "Data" ["numbers" "strings" "keywords" "nil" "true/false" "vectors" "maps" "'quoted lists"])
   (subset-line "Forms" (map str (sort (disj special-forms 'fn* 'let*))))])
