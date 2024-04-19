# Hybrid wins
Instead of trying teach LLM with examples, we can get the desired behavior much more efficiently using patterns we already know.

However, intead of trying to use rules and a simple classifier to extract entities the right way, so committing to an interpretation, that may or may not be correct, this version, balut, try to do less instead, by only providing signal so other NLU modules does not need to recognize the pattern.

# For developer
1. Install leiningen on your platform
2. type 'lein uberjar' at the root to build the jar so that you can use it in down stream project.

# Duckegg

[![Clojars Project](https://clojars.org/wit/duckling/latest-version.svg)](http://clojars.org/wit/duckling)

Duckegg is a Clojure library that parses text into structured data:

    “the first Tuesday of October” => {:value "2014-10-07T00:00:00.000-07:00"
                                       :grain :day}


You can try it out at https://duckling.wit.ai

See our [blog post announcement](https://wit.ai/blog/2014/10/01/open-source-parser-duckling) for more context.

## Getting started

To use Duckling in your project, you just need two functions: `load!` to load the default configuration, and `parse` to parse a string.

```clojure
(ns myproject.core
  (:require [duckling.core :as p]))

(p/load!) ;; Load all languages

(p/parse :en$core ;; core configuration for English ; see also :fr$core, :es$core, :zh$core
         "wake me up the last Monday of January 2015 at 6am"
         [:time]) ;; We are interested in :time expressions only ; see also :duration, :temperature, etc.

;; => [{:label :time
;;        :start 15
;;        :end 49
;;        :value {:type "value", :value "2015-01-26T06:00:00.000-02:00", :grain :hour}
;;        :body "last Monday of January 2015 at 6am"}]
```

See the [documentation](https://duckling.wit.ai) for more information.
