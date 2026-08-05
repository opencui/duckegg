(ns duckling.core
  (:use [clojure.tools.logging :exclude [trace]]
        [plumbing.core])
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [duckling.corpus :as corpus]
            [duckling.engine :as engine]
            [duckling.resource :as res]
            [duckling.time.obj :as time]
            [duckling.util :as util]))

(defonce rules-map (atom {}))
(defonce corpus-map (atom {}))

(defn default-context
  "Build a default context for testing. opt can be either :corpus or :now"
  [opt]
  {:reference-time (case opt
                     :corpus (time/t -2 2013 2 12 4 30)
                     :now (time/now))})

(defn- get-rules
  [id]
  (when id
    (get @rules-map (keyword id))))

(defn- resolve-candidates
  "Normalizes candidates and discards those that cannot be resolved to values."
  [resolve-fn candidates]
  (->> candidates
       (mapcat resolve-fn)
       (filter :value)))

(defn- detect*
  "Parse a sentence and return every normalized rule match.
   Targets is a collection of {:dim dim :label label}. When targets are
   provided, only candidates in those dimensions are returned."
  [s context module targets base-stash]
  {:pre [s context module]}
  (let [rules (get-rules module)
        stash (engine/pass-all s rules base-stash)
        ; add an index to tokens in the stash
        stash (map #(if (map? %1) (assoc %1 :index %2) %1)
                   stash
                   (iterate inc 0))
        dim-label (when (seq targets) (into {} (for [{:keys [dim label]} targets]
                                           [(keyword dim) label])))
        candidates (->> stash
                        (filter :pos)
                        ; just keep the dims we want, and add the label key
                        (?>> dim-label (keep #(when-let [label (get dim-label (:dim %))]
                                                (assoc % :label label))))
                        (resolve-candidates
                          #(engine/resolve-token % context module))
                        ; adapt the keys for the outside world
                        (map (fn [{:keys [pos end text] :as token}]
                               (merge token {:start pos
                                             :end end
                                             :body text}))))]
    {:stash stash :candidates candidates}))


;--------------------------------------------------------------------------
; REPL utilities
;--------------------------------------------------------------------------

(defn- print-stash
  "Print stash to STDOUT"
  [stash candidates]
  (let [width (count (:text (first stash)))
        candidate-indices (map :index candidates)]
    (doseq [[tok i] (reverse (map vector stash (iterate inc 0)))]
      (let [pos (:pos tok)
            end (:end tok)]
        (if pos
          (printf "%s %s%s%s %2d | %-9s | %-25s | %.20s\n"
                  (if (some #{(:index tok)} candidate-indices) "C" " ")
                  (apply str (repeat pos \space))
                  (apply str (repeat (- end pos) \-))
                  (apply str (repeat (- width end -1) \space))
                  i
                  (when-let [x (:dim tok)] (name x))
                  (when-let [x (-> tok :rule :name)] (name x))
                  (string/join " + " (mapv #(get-in % [:rule :name]) (:route tok))))
          (printf "  %s\n" (:text tok)))))))

(defn- print-tokens
  "Recursively prints a tree representing a route"
  ([tokens]
    {:pre [(coll? tokens)]}
    (let [tokens (if (vector? tokens)
                   tokens
                   [tokens])
          tokens (if (= 1 (count tokens))
                   tokens
                   [{:route tokens :rule {:name "root"}}])]
      (print-tokens tokens 0)))
  ([tokens depth]
    (print-tokens tokens depth ""))
  ([tokens depth prefix]
    (doseq [[token i] (map vector tokens (iterate inc 1))]
      (let [;; determine name to display
            name (if-let [name (get-in token [:rule :name ])]
                   name
                   (str "text: " (:text token)))
            ;; prepare children prefix
            last? (= i (count tokens))
            new-prefix (if last? \space \|)
            new-prefix (str prefix new-prefix \space \space \space)]
        (when (pos? depth)
          (print (format "%s%s-- "
                   prefix
                   (if last? \` \|))))
        (println name)
        (print-tokens (:route token)
          (inc depth)
          (if (pos? depth) new-prefix ""))))))

(defn play
  "Show processing details for one sentence. Defines a 'details' function."
  ([module-id s]
   (play module-id s nil))
  ([module-id s targets]
   (play module-id s targets (default-context :corpus)))
  ([module-id s targets context]
   (let [targets (when targets (map (fn [dim] {:dim dim :label dim}) targets))
         {stash :stash
          candidates :candidates} (detect* s context module-id targets nil)]

     ;; 1. print stash
     (print-stash stash candidates)

     ;; 2. print candidates
     (printf "\n%d candidates:\n" (count candidates))
     (doseq [candidate candidates]
       (printf "%-25s %s %s\n" (str (name (:dim candidate))
                                     (if (:latent candidate) " (latent)" ""))
                               (engine/export-value candidate {:date-fn str})
                               (dissoc candidate :value :route :rule :pos :text :end :index
                                               :dim :start :latent :body :pred :timezone :values)))

     ;; 3. ask for details
     (printf "For further info: (details idx) where 1 <= idx <= %d\n" (dec (count stash)))
     (def details (fn [n]
                    (print-tokens (nth stash n))))
     (def token (fn [n]
                  (nth stash n))))))

;--------------------------------------------------------------------------
; Configuration loading
;--------------------------------------------------------------------------

(defn- gen-config-for-lang
  "Generates the full config for a language from directory structure."
  [lang]
  (->> ["corpus" "rules"]
       (map (fn [dir]
              (let [files (->> (format "languages/%s/%s" lang dir)
                               res/get-files
                               (remove #(clojure.string/starts-with? % "_"))
                               (map #(subs % 0 (- (count %) 4)))
                               vec)]
                [(keyword dir) files])))
       (into {})))

(defn- gen-config-for-langs
  "Generates the full config for langs from directory structure."
  [langs]
  (->> langs
       (map (fn [lang]
              [(keyword (format "%s$core" lang)) (gen-config-for-lang lang)]))
       (into {})))

(defn- read-rules
  [lang new-file]
  (-> (format "languages/%s/rules/%s.clj" lang new-file)
      io/resource
      slurp
      read-string
      engine/rules))

(defn- read-corpus
  [lang new-file]
  (-> (format "languages/%s/corpus/%s.clj" lang new-file)
      io/resource
      corpus/read-corpus))

(defn- make-corpus
  [lang corpus-files]
  (->> corpus-files
       (pmap (partial read-corpus lang))
       (reduce (partial util/merge-according-to {:tests concat :context merge}))))

(defn- make-rules
  [lang rules-files]
  (->> rules-files
       (pmap (partial read-rules lang))
       (apply concat)))

(defn- get-dims-for-test
  [context module {:keys [text]}]
  (mapcat (fn [text]
            (try
              (->> (detect* text context module nil nil)
                   :stash
                   (keep :dim))
              (catch Exception e
                (warnf "Error while analyzing module=%s context=%s text=%s"
                       module context text)
                [])))
          text))

(defn get-dims
  "Retrieves all available dimensions for module by running its corpus."
  [module {:keys [context tests]}]
  (->> tests
       (pmap (partial get-dims-for-test context module))
       (apply concat)
       distinct))

(defn load!
  "(Re)loads rules and corpora for the requested languages.
   If no language list is provided, loads all languages.
   Returns a map of loaded modules with available dimensions."
  ([] (load! nil))
  ([languages]
   (let [available-langs (set (res/get-subdirs "languages"))
         langs (if (seq languages)
                 (set/intersection available-langs (set languages))
                 available-langs)
         config (gen-config-for-langs langs)]
     (reset! rules-map {})
     (reset! corpus-map {})
     (let [data (->> config
                     (pmap (fn [[config-key {corpus-files :corpus rules-files :rules}]]
                             (let [lang (-> config-key name (string/split #"\$") first)
                                   corpus (make-corpus lang corpus-files)
                                   rules (make-rules lang rules-files)]
                               [config-key {:corpus corpus :rules rules}])))
                     (into {}))]
       (doseq [[config-key {:keys [corpus rules]}] data]
         (swap! corpus-map assoc config-key corpus)
         (swap! rules-map assoc config-key rules)))
     (->> @corpus-map
          (pmap (fn [[module corpus]]
                  [module (get-dims module corpus)]))
          (into {})))))


;--------------------------------------------------------------------------
; Corpus running
;--------------------------------------------------------------------------

(defn run-corpus
  "Run the corpus given in parameter for the given module.
  Returns a list of vectors [0|1 text error-msg]"
  [{context :context, tests :tests} module]
  (for [test tests
        text (:text test)]
    (try
      (let [{:keys [candidates]} (detect* text context module nil nil)
            check (first (:checks test)) ; only one test is supported now
            check-results (map (partial check context) candidates)] ; return nil if OK, [expected actual] if not OK
        (if (some #(or (nil? %) (false? %)) check-results)
          [0 text nil]
          [1 text [(ffirst check-results) (map second check-results)]]))
      (catch Exception e
        [1 text (.getMessage e)]))))

(defn run
  "Runs the corpus and prints the results to the terminal."
  ([]
   (run (keys @corpus-map)))
  ([module-id]
   (loop [[mod & more] (if (seq? module-id) module-id [module-id])
          line 0
          acc []]
     (if mod
       (let [output (run-corpus (mod @corpus-map) mod)
             failed (remove (comp (partial = 0) first) output)]
         (doseq [[[error-count text error-msg] i] (map vector failed (iterate inc line))]
           (printf "%d FAIL \"%s\"\n    Expected %s\n" i text (first error-msg))
           (doseq [got (second error-msg)]
             (printf "    Got      %s\n" got)))
         (printf "%s: %d examples, %d failed.\n" mod (count output) (count failed))
         (recur more (+ line (count failed)) (concat acc (map (fn [[_ t _]] [mod t]) failed))))
       (defn c [n]
         (let [[mod text] (nth acc n)]
           (printf "(play %s \"%s\")\n" mod text)
           (play mod text)))))))


;--------------------------------------------------------------------------
; Public API
;--------------------------------------------------------------------------
(defn detect
  "Returns every normalized rule match for text in the given module.
   If dims are provided as a list of token-dimension keywords, only those
   dimensions are returned. Context is a map with a :reference-time key. If it
   is not provided, the current system date and time is used."
  ([module text]
   (detect module text []))
  ([module text dims]
   (detect module text dims (default-context :now)))
  ([module text dims context]
   (->> (detect* text context module (map (fn [dim] {:dim dim :label dim}) dims) nil)
        :candidates
        (map #(assoc % :value (engine/export-value % {})))
        (map #(select-keys % [:dim :body :value :start :end :latent])))))
