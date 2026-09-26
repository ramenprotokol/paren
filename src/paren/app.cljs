(ns paren.app
  "The page: source slip, stage of paper cards, caption tray and the
  environment's index cards. Plain DOM, no framework, to keep the bundle
  small."
  (:require [clojure.string :as str]
            [paren.builtins :as b]
            [paren.layout :as layout]
            [paren.presets :as presets]
            [paren.reader :as reader]
            [paren.stepper :as s]))

(defonce app (atom {:trace nil :i 0 :playing? false}))

(def play-interval-ms 1100)

;; ---------------------------------------------------------------------------
;; DOM helpers

(defn- $ [id] (js/document.getElementById id))

(defn- h
  "Creates an element. attrs: :class, :text, or any attribute name."
  [tag attrs & kids]
  (let [e (js/document.createElement tag)]
    (doseq [[k v] attrs]
      (case k
        :class (set! (.-className e) v)
        :text (set! (.-textContent e) v)
        (.setAttribute e (name k) v)))
    (doseq [kid (flatten kids)]
      (when kid
        (.appendChild e (if (string? kid) (js/document.createTextNode kid) kid))))
    e))

(defn- clear! [e]
  (while (.-firstChild e) (.removeChild e (.-firstChild e))))

(defn- reduced-motion? []
  (.-matches (js/window.matchMedia "(prefers-reduced-motion: reduce)")))

(defn- path-key [path]
  (str/join "." (map #(if (keyword? %) (name %) %) path)))

(defn- code-text
  "Caption text with `code` spans -> DOM nodes."
  [text]
  (map-indexed (fn [i part]
                 (if (odd? i) (h "code" {} part) part))
               (str/split text #"`" -1)))

;; ---------------------------------------------------------------------------
;; Stage

(defn- render-el [el redex]
  (case (:k el)
    :atom
    (let [e (h "span" {:class (str "atom " (:cls el) (when (and (:path el) (= (:path el) redex)) " redex"))}
               (:text el))]
      (when (:path el) (.setAttribute e "data-path" (path-key (:path el))))
      (when (:title el) (set! (.-title e) (:title el)))
      e)

    :br (h "span" {:class (str "br " (:cls el)) :aria-hidden "true"} (:text el))

    (:card :group)
    (let [card? (= :card (:k el))
          red? (and card? (= (:path el) redex))
          e (h "span" {:class (str (name (:k el)) " " (:cls el)
                                   (when card? (str " d" (:depth el)))
                                   (when (:broken? el) " broken")
                                   (when red? " redex"))})]
      (when (:path el) (.setAttribute e "data-path" (path-key (:path el))))
      (when-let [w (:min-ch el)]
        (set! (.. e -style -minWidth) (str w "ch")))
      (when-let [tab (:tab el)]
        (.appendChild e (h "span" {:class "tab"}
                           (h "span" {:class "tab-name"} (:name tab))
                           (h "span" {:class "tab-note"} (:note tab)))))
      (doseq [ln (:lines el)]
        (let [l (h "span" {:class "ln"})]
          (when (pos? (:indent ln))
            (set! (.. l -style -paddingLeft) (str (:indent ln) "ch")))
          (doseq [x (:els ln)] (.appendChild l (render-el x redex)))
          (.appendChild e l)))
      e)

    :top
    (h "div" {:class "top"}
       (map (fn [x] (h "div" {:class "form"} (render-el x redex))) (:items el)))))

(defonce ch-px (atom nil))

(defn- measure-ch []
  (let [probe (h "span" {:class "measure" :aria-hidden "true"} "0000000000")
        tree ($ "tree")]
    (.appendChild tree probe)
    (let [w (/ (.-width (.getBoundingClientRect probe)) 10)]
      (.removeChild tree probe)
      (when (pos? w) (reset! ch-px w)))))

(defn- stage-width-ch []
  (let [stage ($ "stage")
        px (or @ch-px (measure-ch) 9.6)
        cs (js/getComputedStyle stage)
        inner (- (.-clientWidth stage)
                 (js/parseFloat (.-paddingLeft cs))
                 (js/parseFloat (.-paddingRight cs)))]
    (max 16 (js/Math.floor (- (/ inner px) 1)))))

(def scales
  "Stage type sizes, largest first: a small program is set larger, like a
  card on a desk, and a large one smaller so it still fits."
  [["s-lg" 1.3] ["s-md" 1.15]])

(defn- trace-width
  "Widest flat width any step of the trace reaches (sampled, for long ones)."
  [t]
  (let [frames (:frames t)
        n (count frames)
        stride (max 1 (quot n 120))]
    (reduce max 0 (map #(layout/widest-form (:tree (:st (nth frames %)))) (range 0 n stride)))))

(defn- pick-scale! []
  (let [stage ($ "stage")
        cl (.-classList stage)
        t (:trace @app)]
    (doseq [[c] scales] (.remove cl c))
    (reset! ch-px nil)
    (when t
      (let [w (stage-width-ch)
            need (or (:width t) 0)]
        (when-let [[c] (first (filter (fn [[_ k]] (<= (* need k) w)) scales))]
          (.add cl c))
        (reset! ch-px nil)))))

(defn- current-frame []
  (let [{:keys [trace i]} @app]
    (get-in trace [:frames i])))

(declare error-kind-label)

(defn- render-stage! [frame]
  (let [tree ($ "tree")]
    (clear! tree)
    (when-let [failure (:failure @app)]
      (.appendChild tree (h "div" {:class "slip" :role "note"}
                            (h "p" {:class "slip-kind"} (error-kind-label failure))
                            (h "p" {:class "slip-msg"} (code-text (:message failure))))))
    (when frame
      (let [display (layout/display (:tree (:st frame)) (stage-width-ch))
            dom (render-el display (:redex frame))]
        (.appendChild tree dom)
        (when (= :done (:status frame))
          (when-let [last-form (.-lastElementChild dom)]
            (.add (.-classList last-form) "result")))))))

;; ---------------------------------------------------------------------------
;; Caption, controls, environment

(defn- render-caption! [frame]
  (let [cap ($ "caption")
        text ($ "caption-text")
        status (:status frame)]
    (clear! text)
    (set! (.-className cap)
          (str "caption"
               (case status
                 :done " done"
                 (:error :depth-cap :step-cap :size-cap) " stopped"
                 nil)))
    (when frame
      (.appendChild text (h "span" {} (code-text (:caption frame)))))
    (when (:failure @app)
      (set! (.-className cap) "caption stopped")
      (.appendChild text (h "span" {} "Fix the expression, then press Step through.")))))

(defn- render-controls! []
  (let [{:keys [trace i playing?]} @app
        n (max 0 (dec (count (:frames trace))))
        none? (nil? trace)
        at-start? (or none? (zero? i))
        at-end? (or none? (= i n))]
    (set! (.-disabled ($ "first")) at-start?)
    (set! (.-disabled ($ "back")) at-start?)
    (set! (.-disabled ($ "fwd")) at-end?)
    (set! (.-disabled ($ "last")) at-end?)
    (set! (.-disabled ($ "play")) (and (not playing?) at-end?))
    (set! (.-disabled ($ "share")) none?)
    (let [play ($ "play")]
      (.setAttribute play "aria-pressed" (str (boolean playing?)))
      (set! (.-textContent ($ "play-label")) (if playing? "Pause" "Play")))
    (let [scrub ($ "scrub")]
      (set! (.-max scrub) (str n))
      (set! (.-value scrub) (str i))
      (set! (.-disabled scrub) (or none? (zero? n)))
      (.setAttribute scrub "aria-valuetext" (str "step " i " of " n)))
    (set! (.-textContent ($ "counter"))
          (if none? "" (str "step " i " of " n)))))

(defn- render-env! [frame]
  (let [ol ($ "env-cards")]
    (clear! ol)
    (when frame
      (doseq [c (layout/env-cards frame)]
        (.appendChild
         ol
         (h "li" {:class (str "icard" (when (= :global (:fid c)) " global") (when (:remembered? c) " remembered"))}
            (h "div" {:class "icard-head"}
               (h "span" {:class "icard-label"} (:label c))
               (h "span" {:class "icard-kind"}
                  (cond (= :global (:fid c)) "def"
                        (:remembered? c) "kept by a closure"
                        (= "let" (:label c)) "let"
                        :else "call")))
            (if (seq (:rows c))
              (h "dl" {}
                 (map (fn [r]
                        (h "div" {:class (str "row" (when (:found? r) " found") (when (:shadowed? r) " shadowed"))}
                           (h "dt" {} (:sym r))
                           (h "dd" {:title (:title r)} (:value r))))
                      (:rows c)))
              (h "p" {:class "icard-empty"} "nothing defined yet"))
            (when (= :global (:fid c))
              (h "p" {:class "icard-note"} "plus the built-ins, such as + inc map"))))))))

(defn- render! []
  (let [frame (current-frame)]
    (render-stage! frame)
    (render-caption! frame)
    (render-controls!)
    (render-env! frame)))

;; ---------------------------------------------------------------------------
;; Motion: the redex folds shut into its value

(defonce ghosts (atom []))

(defn- clear-ghosts! []
  (doseq [g @ghosts]
    (when (.-parentNode g) (.remove g)))
  (reset! ghosts []))

(defn- find-anchor
  "The element for `path`, or its nearest rendered ancestor (a finished list
  collapses into a single value)."
  [path]
  (loop [p path]
    (when (seq p)
      (or (.querySelector ($ "tree") (str "[data-path=\"" (path-key p) "\"]"))
          (recur (pop p))))))

(defn- fold-ghost!
  "Leaves a copy of the old redex where it was and folds it shut."
  [old]
  (let [stage ($ "stage")
        layer ($ "ghosts")
        sr (.getBoundingClientRect stage)
        r (.getBoundingClientRect old)
        g (.cloneNode old true)
        style (.-style g)]
    (.add (.-classList g) "ghost")
    (set! (.-left style) (str (+ (- (.-left r) (.-left sr)) (.-scrollLeft stage)) "px"))
    (set! (.-top style) (str (+ (- (.-top r) (.-top sr)) (.-scrollTop stage)) "px"))
    (set! (.-width style) (str (.-width r) "px"))
    (.appendChild layer g)
    (swap! ghosts conj g)
    (let [anim (.animate g
                         (clj->js [{:transform "perspective(48em) rotateX(0deg)" :opacity 1}
                                   {:transform "perspective(48em) rotateX(-92deg)" :opacity 0.15}])
                         #js {:duration 260 :easing "cubic-bezier(.5,0,.75,.4)" :fill "forwards"})]
      (set! (.-onfinish anim)
            (fn []
              (.remove g)
              (swap! ghosts (fn [gs] (vec (remove #(identical? % g) gs)))))))))

(defn- settle! [el delay]
  (when el
    (.animate el
              (clj->js [{:transform "perspective(48em) rotateX(55deg) scale(.96)" :opacity 0}
                        {:transform "none" :opacity 1}])
              #js {:duration 240 :delay delay :easing "cubic-bezier(.2,.7,.3,1)" :fill "backwards"})))

(defn- go-to!
  "Shows step j. A single step forward folds the redex into its value; a
  single step back unfolds it again. Jumps are instant."
  [j]
  (let [{:keys [trace i]} @app
        n (dec (count (:frames trace)))
        j (max 0 (min n j))]
    (when (and trace (not= i j))
      (clear-ghosts!)
      (let [animate? (and (= 1 (js/Math.abs (- j i))) (not (reduced-motion?)))
            before (get-in trace [:frames i])
            old (when (and animate? (> j i)) (.querySelector ($ "tree") ".redex"))]
        (when old (fold-ghost! old))
        (swap! app assoc :i j)
        (render!)
        (when animate?
          (if (> j i)
            (settle! (find-anchor (:anchor before)) 120)
            (settle! (.querySelector ($ "tree") ".redex") 0)))
        (when-let [red (.querySelector ($ "tree") ".redex")]
          (.scrollIntoView red #js {:block "nearest" :inline "nearest"}))))))

;; ---------------------------------------------------------------------------
;; Playing

(defonce play-timer (atom nil))

(defn- stop-play! []
  (when-let [t @play-timer] (js/clearTimeout t))
  (reset! play-timer nil)
  (when (:playing? @app)
    (swap! app assoc :playing? false)
    (render-controls!)))

(declare write-hash!)

(defn- tick! []
  (let [{:keys [trace i]} @app
        n (dec (count (:frames trace)))]
    (if (< i n)
      (do (go-to! (inc i))
          (write-hash!)
          (if (< (inc i) n)
            (reset! play-timer (js/setTimeout tick! play-interval-ms))
            (stop-play!)))
      (stop-play!))))

(defn- toggle-play! []
  (if (:playing? @app)
    (stop-play!)
    (let [{:keys [trace i]} @app]
      (when (and trace (< i (dec (count (:frames trace)))))
        (swap! app assoc :playing? true)
        (render-controls!)
        (tick!)))))

;; ---------------------------------------------------------------------------
;; URL: #e=<expression>&s=<step>

(defn- read-hash []
  (let [params (js/URLSearchParams. (subs (.-hash js/location) 1))]
    {:src (.get params "e")
     :step (some-> (.get params "s") js/parseInt)}))

(defn share-url [src i]
  (let [params (js/URLSearchParams.)]
    (.set params "e" src)
    (when (pos? i) (.set params "s" (str i)))
    (str (.-origin js/location) (.-pathname js/location) "#" (.toString params))))

(defonce hash-timer (atom nil))
(defonce last-hash (atom nil))

(defn- write-hash!
  "Keeps the address bar shareable. Debounced, because browsers limit how
  often history.replaceState may be called."
  []
  (when-let [t @hash-timer] (js/clearTimeout t))
  (reset! hash-timer
          (js/setTimeout
           (fn []
             (let [{:keys [src i trace]} @app]
               (when trace
                 (let [url (share-url src i)]
                   (reset! last-hash (subs url (.indexOf url "#")))
                   (js/history.replaceState nil "" url)))))
           400)))

;; ---------------------------------------------------------------------------
;; Running a program

(defn- show-src-error! [msg]
  (let [e ($ "src-error")]
    (clear! e)
    (if msg
      (do (.appendChild e (h "span" {} (code-text msg))) (set! (.-hidden e) false))
      (set! (.-hidden e) true))))

(defn- update-count! []
  (let [n (count (.-value ($ "src")))
        e ($ "count")]
    (set! (.-textContent e) (str (reader/format-count n) " / " (reader/format-count reader/max-chars)))
    (.toggle (.-classList e) "over" (> n reader/max-chars))))

(defn- error-kind-label [t]
  (case (:kind t)
    :read "Reader error"
    :unsupported "Not in the subset"
    :too-long "Too long"
    :too-deep "Too deeply nested"
    :empty "Nothing to run"
    "Syntax error"))

(defn run-src!
  "Reads, checks and traces the source, then shows step `start`."
  ([src] (run-src! src 0))
  ([src start]
   (stop-play!)
   (clear-ghosts!)
   (let [t (s/run src)]
     (if (seq (:frames t))
       (let [n (dec (count (:frames t)))]
         (show-src-error! nil)
         (reset! app {:trace (assoc t :width (trace-width t)) :src src
                      :i (max 0 (min n (or start 0))) :playing? false})
         (pick-scale!)
         (render!)
         (write-hash!))
       (do
         (reset! app {:trace nil :failure t :src src :i 0 :playing? false})
         (pick-scale!)
         (show-src-error! (:message t))
         (render!))))
   (.setAttribute js/document.documentElement "data-state" "ready")))

(defn- load-src! [src start]
  (set! (.-value ($ "src")) src)
  (update-count!)
  (run-src! src start))

;; ---------------------------------------------------------------------------
;; Wiring

(defn- editable? [target]
  (let [tag (some-> target .-tagName str/lower-case)]
    (or (= tag "textarea") (= tag "input") (= tag "select")
        (and target (.-isContentEditable target)))))

(defn- on-key [e]
  (when-not (or (editable? (.-target e)) (.-altKey e) (.-ctrlKey e) (.-metaKey e))
    (let [{:keys [trace i]} @app
          go (fn [j] (.preventDefault e) (stop-play!) (go-to! j) (write-hash!))]
      (when trace
        (case (.-key e)
          "ArrowRight" (go (inc i))
          "ArrowLeft" (go (dec i))
          "Home" (go 0)
          "End" (go (count (:frames trace)))
          nil)))))

(defn- copy-link! []
  (let [{:keys [src i trace]} @app
        status ($ "share-status")]
    (when trace
      (let [url (share-url src i)
            done #(set! (.-textContent status) %)]
        (if-let [clip (.-clipboard js/navigator)]
          (-> (.writeText clip url)
              (.then #(done "Link copied."))
              (.catch #(done (str "Copy this link: " url))))
          (done (str "Copy this link: " url)))))))

(defn- listen! [id ev f]
  (.addEventListener ($ id) ev f))

(defonce resize-timer (atom nil))

(defn- build-static! []
  (let [box ($ "presets")]
    (doseq [{:keys [id label]} presets/presets]
      (.appendChild box (h "button" {:type "button" :class "preset" :data-preset id} label))))
  (set! (.-textContent ($ "subset-fns")) (str/join " " b/names))
  (set! (.-textContent ($ "caps"))
        (str "Caps: " (reader/format-count reader/max-chars) " characters of input, "
             reader/max-nesting " levels of brackets, "
             (reader/format-count s/max-steps) " steps, " s/max-depth " nested calls, "
             (reader/format-count s/max-nodes) " boxes on the stage.")))

(defn- step-button! [id f]
  (listen! id "click" (fn [] (stop-play!) (go-to! (f)) (write-hash!))))

(defn init []
  (build-static!)
  (listen! "presets" "click"
           (fn [e]
             (when-let [id (some-> (.-target e) (.closest "[data-preset]") (.getAttribute "data-preset"))]
               (load-src! (:src (presets/by-id id)) 0))))
  (listen! "run" "click" (fn [] (run-src! (.-value ($ "src")) 0)))
  (listen! "src" "input" update-count!)
  (listen! "src" "keydown"
           (fn [e]
             (when (and (= "Enter" (.-key e)) (or (.-metaKey e) (.-ctrlKey e)))
               (.preventDefault e)
               (run-src! (.-value ($ "src")) 0))))
  (step-button! "first" (constantly 0))
  (step-button! "back" #(dec (:i @app)))
  (step-button! "fwd" #(inc (:i @app)))
  (step-button! "last" #(count (:frames (:trace @app))))
  (listen! "play" "click" toggle-play!)
  (listen! "scrub" "input" (fn [e] (stop-play!) (go-to! (js/parseInt (.. e -target -value))) (write-hash!)))
  (listen! "share" "click" copy-link!)
  (.addEventListener js/document "keydown" on-key)
  (.addEventListener js/window "hashchange"
                     (fn []
                       (when (not= (.-hash js/location) @last-hash)
                         (let [{:keys [src step]} (read-hash)]
                           (when src (load-src! src (or step 0)))))))
  (.addEventListener js/window "resize"
                     (fn []
                       (when-let [t @resize-timer] (js/clearTimeout t))
                       (reset! resize-timer
                               (js/setTimeout (fn [] (pick-scale!) (render-stage! (current-frame))) 120))))
  ;; Re-measure once the web font arrives (the fallback font's width differs).
  (when-let [fonts (.-fonts js/document)]
    (-> (.-ready fonts)
        (.then (fn [] (pick-scale!) (render-stage! (current-frame))))))
  (let [{:keys [src step]} (read-hash)]
    (load-src! (or src (:src (first presets/presets))) (or step 0))))
