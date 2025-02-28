(ns gridfire.build-test-db
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [triangulum.logging :refer [log-str]]))

(def path-env (System/getenv "PATH"))

(defn parse-as-sh-cmd
  "Split string into an array for use with clojure.java.shell/sh."
  [s]
  (loop [chars (seq s)
         acc   []]
    (if (empty? chars)
      acc
      (if (= \` (first chars))
        (recur (->> chars (rest) (drop-while #(not= \` %)) (rest))
               (->> chars (rest) (take-while #(not= \` %)) (apply str) (str/trim) (conj acc)))
        (recur (->> chars (drop-while #(not= \` %)))
               (->> chars (take-while #(not= \` %)) (apply str)
                    (str/trim) (#(str/split % #" ")) (remove str/blank?) (into acc)))))))

(defn sh-wrapper [dir env verbose & commands]
  (sh/with-sh-dir dir
    (sh/with-sh-env (merge {:PATH path-env} env)
      (reduce (fn [acc cmd]
                (let [{:keys [out err]} (apply sh/sh (parse-as-sh-cmd cmd))]
                  (str acc (when verbose out) err)))
              ""
              commands))))

(defn load-default-data [verbose]
  (log-str "Loading landfire rasters...")
  (log-str "Please enter the gridfire_test user's password:")
  (flush)
  (let [password (String/valueOf (.readPassword (System/console)))]
    (->> (sh-wrapper "./test/gridfire/resources"
                     {:PGPASSWORD password}
                     verbose
                     "sh ../../../resources/import_landfire_rasters.sh gridfire_test landfire 900914"
                     "sh ../../../resources/import_ignition_rasters.sh gridfire_test ignition 900914")
         (log-str))
    (->> (sh-wrapper "./test/gridfire/resources/weather-test"
                     {:PGPASSWORD password}
                     verbose
                     "sh ../../../../resources/import_weather_rasters.sh gridfire_test weather 900914 800x800")
         (log-str))))

(defn build-everything [verbose]
  (log-str "Building database...")
  (log-str "Please enter the postgres user's password:")
  (flush)
  (let [password (String/valueOf (.readPassword (System/console)))]
    (->> (sh-wrapper "./src/sql"
                     {:PGPASSWORD password}
                     verbose
                     "psql -h localhost -U postgres -f create_test_db.sql")
         (log-str)))
  (load-default-data verbose))

(defn -main [& args]
  (build-everything true)
  (shutdown-agents))
