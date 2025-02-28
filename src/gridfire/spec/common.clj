(ns gridfire.spec.common
  (:require [clojure.spec.alpha :as s]))

;;-----------------------------------------------------------------------------
;; Regex
;;-----------------------------------------------------------------------------

(def postgis-sql-regex #"[a-z0-9]+(\.[a-z0-9]+)? WHERE rid=[0-9]+")

(def path-to-geotiff-regex #"[a-z_\-\s0-9\.\/]+(\/[a-z_\-\s0-9\.]+)*\.tif")

;;-----------------------------------------------------------------------------
;; Macros
;;-----------------------------------------------------------------------------

(defmacro one-or-more-keys [ks]
  (let [keyset (set (map (comp keyword name) ks))]
    `(s/and (s/keys :opt-un ~ks)
            #(some ~keyset (keys %)))))

;;-----------------------------------------------------------------------------
;; Spec
;;-----------------------------------------------------------------------------

(s/def ::sql (s/and string? #(re-matches postgis-sql-regex %)))

(s/def ::path (s/and string? #(re-matches path-to-geotiff-regex %)))

(s/def ::source (s/or :file-path ::path
                      :sql       ::sql))

(s/def ::type #(contains? #{:geotiff :postgis} %))

(s/def ::unit #(contains? #{:imperial :metric} %))

(s/def ::multiplier (s/or :int int? :float float?))

(s/def ::postgis-or-geotiff
  (s/keys :req-un [::type ::source]
          :opt-un [::cell-size ::unit ::multiplier]))
