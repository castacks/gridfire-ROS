(ns gridfire.fireflight.handler
  (:require [clojure.core.async           :refer [>! chan go alts!]]
            [clojure.data.json            :as json]
            [clojure.edn                  :as edn]
            [clojure.java.shell           :as sh]
            [clojure.string               :as str]
            [clojure.tools.cli            :refer [parse-opts]]
            [gridfire.active-fire-watcher :as active-fire-watcher]
            [gridfire.simple-sockets      :as sockets]
            [gridfire.cli                 :as cli]
            [gridfire.config              :as config]
            [gridfire.utils.server        :refer [nil-on-error]]
            [gridfire.spec.server         :as spec-server]
            [triangulum.logging           :refer [log-str set-log-path! log]]
            [triangulum.utils             :refer [parse-as-sh-cmd]]
            [clojure.spec.alpha           :as spec]
            [compojure.core               :refer :all]
            [compojure.route              :as route]
            [ring.middleware.defaults     :refer [wrap-defaults site-defaults]]
            [ring.middleware.json         :refer [wrap-json-body wrap-json-response]]
            [ring.util.response           :refer [response]]
            [ring.middleware.cors         :refer [wrap-cors]])
  (:import java.util.TimeZone))

(defn format-response
    [& args]
    (response {:test-body 2})
)

(defn handle-simulation-request
    [request]
    (if-let [runtime (get-in request [:body :runtime])]
        (try
         (assoc (format-response (cli/propagate-until (Long/valueOf runtime) "resources/sample_geotiff_config.edn"))
                    :success "true")
         (catch Exception e
            (response {:msg (ex-message e) :success "false"})))
        (response {:msg "did not supply field 'runtime'":success "false"})))

(defroutes app-routes
    (GET "/simulate" request (handle-simulation-request request)))

(def app
    (->
        app-routes
        (wrap-cors :access-control-allow-origin [#".*"]
                   :access-control-allow-methods [:get])
        (wrap-json-body {:keywords? true :bigdecimals? true})
        (wrap-json-response)
        (wrap-defaults site-defaults)))