# Hybrid wins
Instead of teaching an LLM every known language pattern through examples, we can
apply those patterns directly and pass their signals to downstream NLU modules.

Duckegg deliberately does not rank ambiguous matches or commit to a single
interpretation. It returns every rule match that can be normalized, leaving the
final decision to its caller.

# For developer
1. Install leiningen on your platform
2. type 'lein uberjar' at the root to build the jar so that you can use it in down stream project.

# Duckegg
Duckegg is a Clojure library that parses text into structured data:

    “the first Tuesday of October” => {:value "2014-10-07T00:00:00.000-07:00"
                                       :grain :day}


## Getting started

To use Duckegg in your project, call `load!` to load the languages you need and
`detect` to find normalized matches in a string.

```clojure
(ns myproject.core
  (:require [duckling.core :as p]))

(p/load! ["en"]) ;; Omit the argument to load all languages

(p/detect :en$core ;; core configuration for English; see also :fr$core, :es$core, :zh$core
          "wake me up the last Monday of January 2015 at 6am"
          [:time]) ;; Return :time matches; see also :duration, :temperature, etc.

;; includes {:dim :time
;;        :start 15
;;        :end 49
;;        :value {:type "value", :value "2015-01-26T06:00:00.000-02:00", :grain :hour}
;;        :body "last Monday of January 2015 at 6am"}
```
