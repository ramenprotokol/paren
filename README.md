# paren

**Watch a Lisp expression evaluate, one substitution at a time.**

paren is a stepper for a small teaching subset of Clojure. Paste an expression (or pick one of five examples) and step through its evaluation. Each sub-expression is a paper card, nested inside the one that contains it. The card about to reduce (the *redex*) is outlined in vermilion. When you step, it folds shut into its value. The environment sits beside it as a stack of index cards, and every step has a one-line caption such as ``look up `n` → 3``.

![paren stepping through a recursive fib, three calls deep](docs/screenshot.png)

## The 30-second experience

1. Open the page. The recursive `fib` example is loaded at step 0.
2. Press → (or the step button). `(fib 4)` becomes the body of `fib` on a card tabbed **fib n = 4**. `n` is looked up (`look up n → 4`), `(< 4 2)` becomes `false`, the `if` takes its else-branch, and so on.
3. Scrub the slider to jump anywhere. ← steps back, Home and End jump to either end, and Play steps on its own.
4. Try **a closure**: when `add5` runs, the environment shows the `make-adder` card, marked *kept by a closure*, still holding `n = 5` after `make-adder` has returned.
5. Copy the link. It encodes the expression and the current step, so it opens the same moment for someone else.

The examples are recursive `fib`, `(reduce + (map inc [1 2 3]))`, `let` scoping, a closure, and `cond`.

## How it works

Everything that matters is ClojureScript under `src/paren/`.

- **Reading.** `reader.cljs` uses `cljs.tools.reader`, the real Clojure reader ported to ClojureScript, to turn the text into Clojure data: lists, vectors, maps, symbols. Code is data from here on. Input is capped at 2,000 characters.
- **Checking the subset.** `syntax.cljs` walks that data and builds an expression tree. Anything outside the subset is refused before a single step runs, with a message that names it. Examples: ``loop` isn't in paren's teaching subset (write it as plain recursion instead)``, and ``Destructuring ([a b]) in `let` isn't in paren's teaching subset``. Malformed special forms get their own messages, such as ``let` bindings come in pairs``.
- **Stepping.** `stepper.cljs` is a small-step evaluator.
  - Each step finds the redex under Clojure's order: the operator first, then the arguments left to right, innermost first.
  - It replaces the redex with what it reduces to: a value, the chosen branch of an `if`, or a function body.
  - A function call becomes a `:scope` node that holds the call's bindings and the closure's captured environment. Lookups walk that chain of frames, then the globals made by `def`, then the built-ins.
  - `map`, `filter` and `reduce` unfold into the calls they will make, so a user function passed to `map` shows every call as its own steps.
  - Each step returns a caption, the redex's path, the environment at that point, and (for lookups) which frame answered.
- **Tracing.** `trace` runs the whole program once and keeps every state. Clojure's persistent data structures share structure between states, so keeping thousands of snapshots is cheap, and stepping back or scrubbing is just indexing.
- **Drawing.** `layout.cljs` turns a tree into cards and decides line breaks like a Clojure pretty-printer. A card stays on one line if it fits the stage width (measured in `ch`); otherwise it breaks the way Clojure is usually indented. `cond` pairs always get a line each. `app.cljs` renders that with plain DOM calls (no framework, to keep the bundle small) and runs the fold animation with the Web Animations API.

### The subset

| | |
|---|---|
| Data | numbers, strings, keywords, `nil`, `true`/`false`, vectors, maps, quoted lists (`'(1 2 3)`) |
| Special forms | `def` `defn` `fn` `let` `if` `cond` `do` `and` `or` `quote`, plus `#(…)` short functions |
| Built-ins | `+ - * / inc dec mod rem quot max min = not= < > <= >= zero? pos? neg? even? odd? nil? not empty? count first rest cons conj get assoc nth vector list range str map filter reduce` |
| Also | recursion, closures, rest parameters (`[x & more]`), keywords, maps and vectors used as functions |

Semantics follow ClojureScript: numbers are JavaScript numbers, so `(/ 1 3)` is `0.3333333333333333` and `(/ 1 0)` is `##Inf`.

### Caps, each with its own message

- 2,000 characters of input, nested at most 50 brackets deep.
- 5,000 steps. The steps up to the cap stay scrubbable.
- 100 function calls in progress at once (the recursion cap).
- 2,500 boxes on the stage.
- `str` results up to 10,000 characters, and `range` up to 1,000 numbers.

## Why ClojureScript

The stepper *is* the thing the language is good at:

- The input is read by the language's own reader, and the evaluator pattern-matches on the same lists and vectors a Clojure programmer writes.
- Immutable, structurally shared data makes "keep every state of the evaluation" a design choice rather than a memory problem.
- The Closure Compiler's `:advanced` mode keeps the result small enough for a static page.

## Build

Needs **Node 22+** and **Java 21+**. shadow-cljs compiles ClojureScript on the JVM, and it downloads ClojureScript itself from Maven Central/Clojars on the first build.

```sh
npm ci
npm run build        # shadow-cljs release (:advanced) → dist/
npm run serve        # optional: serve dist/ on a random free port
```

`scripts/postbuild.mjs` assembles `dist/`. It copies the content-hashed bundle, hashes the stylesheet, fills in `index.html`, writes `_headers`, and prints the sizes, measured at build time. From the latest build:

| file | raw | gzip |
|---|---|---|
| `js/main.<hash>.js` (everything: reader, evaluator, UI, cljs.core) | 285.7 KiB | 69.0 KiB |
| `css/styles.<hash>.css` | 17.1 KiB | 4.8 KiB |
| `index.html` | 5.7 KiB | 2.1 KiB |

About 65% of the bundle (by optimised size) is `cljs.core` itself; the whole first load of our own files is 75.8 KiB gzipped. Google Fonts (IBM Plex Mono and Instrument Serif) load separately.

## Test

```sh
npm test
```

This runs three things:

1. **ClojureScript tests** (`shadow-cljs` `:node-test`, `test/paren/`), 46 tests with 571 assertions:
   - the stepper on each supported form, with its exact captions and values;
   - **golden caption sequences** for all five examples, every step in order, plus their final values;
   - the step cap, the recursion cap, the size cap, and the `str`/`range` caps;
   - an exact message for each unsupported form and each malformed special form;
   - the reader error path (unbalanced input, EOF, oversized, over-nested and empty input, syntax-quote);
   - layout: line breaking, the redex always present in the display tree, and the environment cards;
   - 58 odd or hostile inputs, each of which must end in a clear status and never an internal error.
2. **The build.**
3. **Node tests** (`tests/`):
   - a `dist/` smoke test: hashed files, `_headers`, a 100 KiB gzip budget, and serving with the production headers;
   - a **headless Chrome** check over the DevTools protocol, run against `dist/` with the production Content-Security-Policy. It steps, scrubs, plays, opens shared links and feeds in bad input. It checks for no horizontal scroll at a true 400 px width (device emulation) on every step of `fib`, WCAG AA contrast in light and dark, and instant steps under `prefers-reduced-motion`. It fails on any console error or exception.

If Chrome isn't found, the browser tests are skipped. `REQUIRE_BROWSER=1` makes that a failure, and `CHROME_PATH` points at a specific browser.

## Cloudflare (free tier, static only)

`dist/` is five static files. There is no Worker, KV, D1 or server code, and no API calls. That is far inside Cloudflare Pages' free static limits: unlimited requests, 20,000 files per site, 25 MiB per file.

It is **deploy-ready, not deployed**. To deploy your own copy:

```sh
npm run build
npx wrangler pages deploy dist --project-name paren
```

There is deliberately no `npm run deploy` script and no `account_id` in `wrangler.toml`. The plain command above uses whatever Cloudflare login is active, which is fine for your own copy. The owner deploys this project through a separate, guarded script that checks which account it is about to use.

`dist/_headers` sets:

- a strict Content-Security-Policy: `script-src 'self'`, and no inline scripts or styles;
- `nosniff`, `no-referrer`, and a locked-down Permissions-Policy;
- a one-year immutable cache **only** on `/js/*` and `/css/*`, whose file names carry a content hash. `index.html` is `no-cache`.

## Privacy

- Everything runs in the browser. There are no analytics, no cookies and no storage.
- The expression lives in the URL fragment (`#e=…&s=…`), which browsers do not send to the server.
- The only third-party request is Google Fonts. If it fails, the page falls back to system fonts.

## Honest limitations

- **It is a subset, not Clojure.** There are no macros, lazy sequences, destructuring, `loop`/`recur`, sets, multi-arity functions, atoms or printing. Each is refused by name rather than half-supported.
- **`map`, `filter` and `reduce` are eager.** Every call they make becomes a visible step. Clojure's `map` and `filter` are lazy (and chunked), so an infinite sequence that works in Clojure cannot work here. `range` is capped at 1,000 numbers for the same reason.
- **Some steps are folded together, on purpose.**
  - A built-in such as `+` in call position isn't a separate "look up" step, unless a local or `def` shadows it.
  - A named function (`fib`) in call position is resolved when the call happens, after its arguments. An unknown name still fails before the arguments run, as in Clojure. In this pure subset the order can't change a result.
  - `defn` takes one step, not a macro expansion into `def` + `fn`.
- **Numbers are JavaScript numbers** (ClojureScript semantics). There are no ratios or big integers, and division by zero gives `##Inf` instead of throwing as JVM Clojure does.
- **Printing differs from a REPL.** A function prints as `‹fn fib›` or `‹fn [x]›`, where a REPL would print an opaque object.
- **The whole trace is computed when you press Step through**, on the main thread, up to the 5,000-step cap. Long traces take a moment. No timing is claimed.
- **Layout is estimated in `ch`.** Very deep nesting can make the stage scroll sideways inside its own frame; the page itself does not scroll sideways.
- **Contrast is checked by the automated test** at a particular step in each theme, not for every possible program.

## Next

- A cross-check of final values against the self-hosted ClojureScript compiler (`cljs.js`), lazy-loaded because it is several MB.
- Macros, with macro expansion shown as its own step (`when`, `->`, `defn` → `def` + `fn`).
- Lazy sequences (`lazy-seq`, lazy `map`/`filter`, `take`, `iterate`) with realisation shown as steps.
- Destructuring in `let` and `fn` beyond single names.
- `loop`/`recur` drawn without growing the stack, and a "collapse finished frames" view for deep recursion.
- A coarser step mode that hides lookups, for longer programs.

## Credits

Built by Ramen Protocol with AI assistance (Claude). Typography: IBM Plex Mono and Instrument Serif (Google Fonts).

## License

MIT. See [LICENSE](LICENSE).
