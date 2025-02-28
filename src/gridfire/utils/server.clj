(ns gridfire.utils.server
  (:require [clojure.string :as s]))

;; TODO extend to more timezones
(def time-regex
  "regex for timestamp with the form YYYY-MM-DD HH:MM ZZZ
  (i.e. 2021-03-15 05:00 PST)"
  #"[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[1-2][0-9]|3[0-1]) (2[0-3]|[01][0-9]):[0-5][0-9] (PST|UTC|EST)")

(defmacro nil-on-error
  [& body]
  (let [_ (gensym)]
    `(try ~@body (catch Exception ~_ nil))))

(defn non-empty-string?
  [x]
  (and (string? x)
       (pos? (count x))))

(defn hostname?
  [x]
  (or (= x "localhost")
      (and (non-empty-string? x)
           (s/includes? x ".")
           (not (s/starts-with? x "."))
           (not (s/ends-with? x ".")))))

(defn port?
  [x]
  (and (integer? x)
       (< 0 x 0x10000)))

(defn fire-name?
  [x]
  (string? x))

(defn time?
  [x]
  (some? (re-matches time-regex x)))
