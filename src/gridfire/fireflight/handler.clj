(ns gridfire.fireflight.handler
  (:require [clojure.core.matrix          :as m]
            [clojure.core.async           :refer [>! chan go alts!]]
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
            [ring.middleware.defaults     :refer [wrap-defaults site-defaults api-defaults]]
            [ring.middleware.json         :refer [wrap-json-body wrap-json-response]]
            [ring.util.response           :refer [response]]
            [ring.middleware.cors         :refer [wrap-cors]])
  (:import java.util.TimeZone))


(defn matrix-to-vectors
    [{:keys [simulation 
            spread-rate-matrix 
            fire-type-matrix 
            fire-line-intensity-matrix
            fire-spread-matrix
            spot-count
            global-clock 
            flame-length-matrix 
            heat-density-matrix
            crown-fire-count 
            burn-time-matrix
            ignited-cells
            num-rows
            num-cols
            ] :as inputs}]
    {
        :simulation simulation
        :spot-count spot-count
        :global-clock global-clock
        :crown-fire-count crown-fire-count
        :num-rows num-rows
        :num-cols num-cols
        :newly-ignited-cells (map :cell ignited-cells)
        :spread-rate-matrix (m/to-nested-vectors spread-rate-matrix)
        :fire-type-matrix (m/to-nested-vectors fire-type-matrix)
        :fire-line-intensity-matrix (m/to-nested-vectors fire-line-intensity-matrix)
        :fire-spread-matrix (m/to-nested-vectors fire-spread-matrix)
        :flame-length-matrix (m/to-nested-vectors flame-length-matrix)
        :heat-density-matrix (m/to-nested-vectors heat-density-matrix)
        :burn-time-matrix (m/to-nested-vectors burn-time-matrix)
    })

(def config-file "fireflight/config_cali2/fireflight_geotiff_config.edn")

(defn format-simulation-response
    [output]
    (->> (output :summary-stats)
        (map matrix-to-vectors)
        (response)))

(defn handle-simulation-request
    [request]
    (if-let [[runtime config-name ignition-points] 
            [(get-in request [:body :runtime]) 
             (get-in request [:body :config])
             (get-in request [:body :initial-ignition-points])]]
        ;; (try
         (assoc (format-simulation-response (cli/propagate-until (
             if (int? runtime) 
             runtime 
             (Long/valueOf runtime)) 
             (str "fireflight/" config-name "/fireflight_geotiff_config.edn")
             ignition-points))
                    :success "true")
        ;;  (catch Exception e
        ;;     (response {:msg (ex-message e) :success "false"})))
        (response {:msg "did not supply field 'runtime' or 'config' or 'initial-ignition-points'" :success "false"})))

(defn format-rasters-response
    [{:keys [
        landfire-rasters
        weather-layers
        cell-size
        ignition-row
        ignition-col
    ] :as inputs}]
    {
        :cell-size cell-size
        :ignition-row ignition-row
        :ignition-col ignition-col
        :aspect (m/to-nested-vectors (:aspect landfire-rasters))
        :canopy-base-height (m/to-nested-vectors (:canopy-base-height landfire-rasters))
        :canopy-cover (m/to-nested-vectors (:canopy-cover landfire-rasters))
        :canopy-height (m/to-nested-vectors (:canopy-height landfire-rasters))
        :crown-bulk-density (m/to-nested-vectors (:crown-bulk-density landfire-rasters))
        :elevation (m/to-nested-vectors (:elevation landfire-rasters))
        :fuel-model (m/to-nested-vectors (:fuel-model landfire-rasters))
        :wind-speeds (m/to-nested-vectors (m/slice (:matrix (:wind-speed-20ft weather-layers)) 0))
        :wind-directions (m/to-nested-vectors (m/slice (:matrix (:wind-from-direction weather-layers)) 0))
        :slope (m/to-nested-vectors (:slope landfire-rasters))
    })

(defn handle-rasters-request
    [request]
    (if-let [config-name (get-in request [:body :config])]
        (response (format-rasters-response (cli/get-inputs (str "fireflight/" config-file "/fireflight_geotiff_config.edn")))))

(defroutes app-routes
    (GET "/simulate" request (handle-simulation-request request))
    (POST "/simulate" request (handle-simulation-request request))
    (GET "/rasters" request (handle-rasters-request request))
    (route/not-found "Not Found"))

(def app
    (->
        app-routes
        (wrap-cors :access-control-allow-origin [#".*"]
                   :access-control-allow-methods [:get, :post])
        (wrap-json-body {:keywords? true :bigdecimals? true})
        (wrap-json-response)
        (wrap-defaults api-defaults)))


