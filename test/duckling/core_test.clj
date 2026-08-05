(ns duckling.core-test
  (:use [duckling.core]
        [clojure.test])
  (:require [duckling.engine :as engine]
            [plumbing.core :refer [map-from-keys map-vals]]
            [duckling.resource :as res]))

(deftest detect-returns-all-normalizable-candidates
  (let [raw-candidates [{:pos 0 :end 3 :text "may" :dim :time
                         :candidate {:interpretation :month}}
                        {:pos 0 :end 3 :text "may" :dim :time
                         :candidate {:interpretation :verb}}
                        {:pos 0 :end 3 :text "may" :dim :number
                         :candidate {:interpretation :number}}
                        {:pos 0 :end 3 :text "may" :dim :time}]
        expected [{:dim :time :body "may" :value {:interpretation :month}
                   :start 0 :end 3}
                  {:dim :time :body "may" :value {:interpretation :verb}
                   :start 0 :end 3}]]
    (with-redefs [engine/pass-all (fn [_ _ _] raw-candidates)
                  engine/resolve-token (fn [token _ _]
                                         [(cond-> token
                                            (:candidate token)
                                            (assoc :value (:candidate token)))])
                  engine/export-value (fn [token _] (:value token))]
      (is (= expected
             (vec (detect :en$core "may" [:time] (default-context :corpus))))))))

(defn diag-corpus
  "Returns :ok if the corpus runs well, or a string with list of failures otherwise."
  [run-corpus-output]
  (if (= 0 (reduce #(+ %1 (first %2)) 0 run-corpus-output)) ;; no fail
    :ok
    (->> run-corpus-output
         (filter #(not= 0 (first %)))
         (map second)
         (reduce #(str %1 "\n" %2)))))

(defmacro with-timeout [millis res & body]
  `(let [future# (future ~@body)]
     (try
       (.get future# ~millis java.util.concurrent.TimeUnit/MILLISECONDS)
       (catch java.util.concurrent.TimeoutException x#
         (do
           (future-cancel future#)
           ~res)))))

(deftest datetime-corpus-runs-without-failure
  (load! ["fr" "en"])
  (testing "fr and en"
    (are [lang] (let [module (format "%s$core" lang)]
                  (= :ok (-> (get @corpus-map module)
                             (run-corpus module)
                             diag-corpus)))
      "fr"
      "en"))
  (testing "Public API (detect)"
    (is (some #(and (= :time (:dim %))
                    (= "january 2014" (:body %))
                    (= :month (get-in % [:value :grain])))
              (detect :en$core "january 2014" [:time] (default-context :corpus)))))
  (testing "Very big one"
    (is (< 1
           (count
             (with-timeout 10000 "TIMEOUT!!"
                           (detect :en$core
                                   "Oct. 12 from 2 to 5 p.m. Monday-Friday, Sept. 7-March 1, 8 a.m.-noon, 1-5 p.m."
                                   [:time]
                                   (default-context :corpus))))))))

(deftest load!-api-test
  (with-redefs [get-dims (constantly [:number :time])]
    (let [check (fn [arg exp]
                  (= (map-vals set (map-from-keys get-dims exp))
                     (map-vals set (load! arg))))]
      (testing "load! should load all languages by default"
        (with-redefs [res/get-files (constantly ["numbers.clj"])
                      res/get-subdirs (constantly ["en" "zh" "pt"])]
          (are [arg] (check arg [:en$core :zh$core :pt$core])
            nil
            []
            #{})))
      (testing "load! should accept a list of languages"
        (are [arg exp] (check arg exp)
          ["fr"] [:fr$core]
          ["foo" "bar"] []
          ["bar" "es" "pt" "ru"] [:es$core :pt$core :ru$core]))
      (testing "load! should return a map of loaded modules with dimensions"
        (are [arg exp] (check arg exp)
          ["fr"] [:fr$core]
          ["fr" "foo" "en"] [:fr$core :en$core])))))
