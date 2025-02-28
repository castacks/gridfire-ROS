;; [[file:../../org/GridFire.org::sardoy-firebrand-dispersal][sardoy-firebrand-dispersal]]
(ns gridfire.spotting
  (:require [clojure.core.matrix :as m]
            [gridfire.common :refer [distance-3d
                                     get-fuel-moisture
                                     get-value-at
                                     in-bounds?
                                     burnable?
                                     fuel-moisture-from-raster]]
            [gridfire.crown-fire :refer [ft->m]]
            [gridfire.utils.random :refer [random-float my-rand-range]]
            [gridfire.conversion :as convert]
            [kixi.stats.distribution :as distribution]))

;;-----------------------------------------------------------------------------
;; Formulas
;;-----------------------------------------------------------------------------

(defn- sample-spotting-params
  ^double
  [param rand-gen]
  (if (map? param)
    (let [{:keys [lo hi]} param
          l               (if (vector? lo) (my-rand-range rand-gen lo) lo)
          h               (if (vector? hi) (my-rand-range rand-gen hi) hi)]
      (my-rand-range rand-gen [l h]))
    param))

(defn- mean-variance
  "Returns mean spotting distance and it's variance given:
  fire-line-intensity: (kWm^-1)
  wind-speed-20ft: (ms^-1)"
  [{:keys [^double mean-distance ^double flin-exp ^double ws-exp ^double normalized-distance-variance]}
   rand-gen ^double fire-line-intensity ^double wind-speed-20ft]
  (let [a (sample-spotting-params mean-distance rand-gen)
        b (sample-spotting-params flin-exp rand-gen)
        c (sample-spotting-params ws-exp rand-gen)
        m (* a (Math/pow fire-line-intensity b) (Math/pow wind-speed-20ft c))]
    {:mean m :variance (* m (sample-spotting-params normalized-distance-variance rand-gen))}))

(defn- standard-deviation
  "Returns standard deviation for the lognormal distribution given:
  mean spotting distance and it's variance"
  ^double
  [^double m ^double v]
  (Math/sqrt (Math/log (+ 1 (/ v (Math/pow m 2))))))

(defn- normalized-mean
  "Returns normalized mean for the lognormal distribution given:
  mean spotting distance and it's variance"
  ^double
  [^double m ^double v]
  (Math/log (/ (Math/pow m 2)
               (Math/sqrt (+ v (Math/pow m 2))))))

(defn- sample-wind-dir-deltas
  "Returns a sequence of [x y] distances (meters) that firebrands land away
  from a torched cell at i j where:
  x: parallel to the wind
  y: perpendicular to the wind (positive values are to the right of wind direction)"
  [{:keys [spotting rand-gen random-seed]}
   fire-line-intensity-matrix
   wind-speed-20ft [i j]]
  (let [num-firebrands          (sample-spotting-params (:num-firebrands spotting) rand-gen)
        intensity               (convert/Btu-ft-s->kW-m (m/mget fire-line-intensity-matrix i j))
        {:keys [mean variance]} (mean-variance spotting rand-gen intensity wind-speed-20ft)
        mu                      (normalized-mean mean variance)
        sd                      (standard-deviation mean variance)
        parallel                (distribution/log-normal {:mu mu :sd sd})
        perpendicular           (distribution/normal {:mu 0 :sd 0.92})
        parallel-values         (distribution/sample num-firebrands parallel {:seed random-seed})
        perpendicular-values    (distribution/sample num-firebrands perpendicular {:seed random-seed})]
    (mapv (fn [x y] [(convert/m->ft x) (convert/m->ft y)])
          parallel-values
          perpendicular-values)))
;; sardoy-firebrand-dispersal ends here
;; [[file:../../org/GridFire.org::convert-deltas][convert-deltas]]
(defn- hypotenuse ^double
  [x y]
  (Math/sqrt (+ (Math/pow x 2) (Math/pow y 2))))

(defn- deltas-wind->coord
  "Converts deltas from the torched tree in the wind direction to deltas
  in the coordinate plane"
  [deltas ^double wind-direction]
  (mapv (fn [[d-paral d-perp]]
          (let [d-paral (double d-paral)
                d-perp  (double d-perp)
                H  (hypotenuse d-paral d-perp)
                t1 wind-direction
                t2 (convert/rad->deg (Math/atan (/ d-perp d-paral)))
                t3 (+ t1 t2)]
            [(* -1 H (Math/cos (convert/deg->rad t3)))
             (* H (Math/sin (convert/deg->rad t3)))]))
        deltas))

(defn- firebrands
  "Returns a sequence of cells that firebrands land in"
  [deltas wind-towards-direction cell ^double cell-size]
  (let [step         (/ cell-size 2)
        [x y]        (mapv #(+ step (* ^double % step)) cell)
        x            (double x)
        y            (double y)
        coord-deltas (deltas-wind->coord deltas wind-towards-direction)]
    (mapv (fn [[dx dy]]
            (let [dx (double dx)
                  dy (double dy)]
              [(int (Math/floor (/ (+ dx x) step)))
               (int (Math/floor (/ (+ dy y) step)))]))
          coord-deltas)))
;; convert-deltas ends here
;; [[file:../../org/GridFire.org::firebrand-ignition-probability][firebrand-ignition-probability]]
(defn- specific-heat-dry-fuel
  "Returns specific heat of dry fuel given:
  initiial-temp: (Celcius)
  ignition-temp: (Celcius)"
  ^double
  [^double initial-temp ^double ignition-temp]
  (+ 0.266 (* 0.0016 (/ (+ ignition-temp initial-temp) 2))))

(defn- heat-of-preignition
  "Returns heat of preignition given:
  init-temperature: (Celcius)
  ignition-temperature: (Celcius)
  moisture content: (Percent)"
  ^double
  [^double init-temperature ^double ignition-temperature ^double moisture]
  (let [T_o init-temperature
        T_i ignition-temperature
        M   moisture
        c_f (specific-heat-dry-fuel T_o T_i)

        ;; heat required to reach ignition temperature
        Q_a (* (- T_i T_o) c_f)

        ;; heat required to raise moisture to reach boiling point
        Q_b (* (- 100 T_o) M)

        ;; Heat of desorption
        Q_c (* 18.54 (- 1 (Math/exp (* -15.1 M))))

        ;; Heat required to vaporize moisture
        Q_d (* 540 M)]
    (+ Q_a Q_b Q_c Q_d)))

(defn- schroeder-ign-prob
  "Returns the probability of ignition as described in Shroeder (1969) given:
  relative-humidity: (%)
  temperature: (Farenheit)"
  ^double
  [fuel-moisture temperature]
  (let [ignition-temperature 320 ;;FIXME should this be a constant?
        moisture             (-> fuel-moisture :dead :1hr)
        Q_ig                 (heat-of-preignition (convert/F->C temperature) ignition-temperature moisture)
        X                    (/ (- 400 Q_ig) 10)]
    (/ (* 0.000048 (Math/pow X 4.3)) 50)))

(defn- spot-ignition-probability
  [{:keys [cell-size landfire-rasters]}
   {:keys [decay-constant]}
   fuel-moisture
   temperature
   firebrand-count
   torched-origin
   here]
  (let [ignition-probability (schroeder-ign-prob fuel-moisture temperature)
        distance             (ft->m (distance-3d (:elevation landfire-rasters)
                                                 cell-size
                                                 here
                                                 torched-origin))
        decay-factor         (Math/exp (* -1 ^double decay-constant distance))]
    (- 1 (Math/pow (- 1 (* ignition-probability decay-factor)) firebrand-count))))
;; firebrand-ignition-probability ends here
;; [[file:../../org/GridFire.org::firebrands-time-of-ignition][firebrands-time-of-ignition]]
(defn- spot-ignition?
  [rand-gen ^double spot-ignition-probability]
  (let [random-number (random-float 0 1 rand-gen)]
    (> spot-ignition-probability random-number)))

(defn- spot-ignition-time
  "Returns the time of spot ignition in minutes given:
  global-clock: (min)
  flame-length: (m)
  wind-speed-20ft: (ms^-1)"
  [^double global-clock ^double flame-length ^double wind-speed-20ft]
  (let [a              5.963
        b              (- a 1.4)
        D              0.003 ;firebrand diameter (m)
        z-max          (* 0.39 D (Math/pow 10 5))
        t-steady-state 20    ;period of building up to steady state from ignition (min)
        t_o            1     ;period of steady burning of tree crowns (min)
        t-max-height   (+ (/ t_o (/ (* 2 flame-length) wind-speed-20ft))
                          1.2
                          (* (/ a 3.0)
                             (- (Math/pow (/ (+ b (/ z-max flame-length)) a) (/ 3.0 2.0)) 1)))]
    (+ global-clock (* 2 t-max-height) t-steady-state)))
;; firebrands-time-of-ignition ends here
;; [[file:../../org/GridFire.org::spread-firebrands][spread-firebrands]]
(defn- update-firebrand-counts!
  [{:keys [num-rows num-cols landfire-rasters]}
   firebrand-count-matrix
   fire-spread-matrix
   source
   firebrands]
  (doseq [[x y :as here] firebrands
          :when          (and (in-bounds? num-rows num-cols [x y])
                              (burnable? fire-spread-matrix
                                         (:fuel-model landfire-rasters)
                                         source
                                         here))
          :let           [new-count (inc ^double (m/mget firebrand-count-matrix x y))]]
    (m/mset! firebrand-count-matrix x y new-count)))

(defn- in-range?
  [[min max] fuel-model-number]
  (<= min fuel-model-number max))

(defn- surface-spot-percent
  ^double
  [fuel-range-percents fuel-model-number rand-gen]
  (reduce (fn [acc [fuel-range percent]]
            (if (in-range? fuel-range fuel-model-number)
              (if (vector? percent)
                (my-rand-range rand-gen percent)
                percent)
              acc))
          0.0
          fuel-range-percents))

(defn- surface-fire-spot-fire?
  "Expects surface-fire-spotting config to be a sequence of tuples of
  ranges [lo hi] and spottting percent. The range represents the range (inclusive)
  of fuel model numbers that the spotting percent is set to.
  [[[1 140] 0.0]
  [[141 149] 1.0]
  [[150 256] 1.0]]"
  [{:keys [spotting rand-gen landfire-rasters]} [i j] ^double fire-line-intensity]
  (let [{:keys [surface-fire-spotting]} spotting]
    (when (and
           surface-fire-spotting
           (> fire-line-intensity ^double (:critical-fire-line-intensity surface-fire-spotting)))
      (let [fuel-range-percents (:spotting-percent surface-fire-spotting)
            fuel-model-raster   (:fuel-model landfire-rasters)
            fuel-model-number   (int (m/mget fuel-model-raster i j))
            spot-percent        (surface-spot-percent fuel-range-percents fuel-model-number rand-gen)]
        (>= spot-percent (random-float 0.0 1.0 rand-gen))))))

(defn- crown-spot-fire? [{:keys [spotting rand-gen]}]
  (when-let [spot-percent (:crown-fire-spotting-percent spotting)]
    (let [^double p (if (vector? spot-percent)
                      (let [[lo hi] spot-percent]
                        (random-float lo hi rand-gen))
                      spot-percent)]
      (>= p (random-float 0.0 1.0 rand-gen)))))

(defn- spot-fire? [inputs crown-fire? here fire-line-intensity]
  (if crown-fire?
    (crown-spot-fire? inputs)
    (surface-fire-spot-fire? inputs here fire-line-intensity)))

(defn spread-firebrands
  "Returns a sequence of key value pairs where
  key: [x y] locations of the cell
  val: [t p] where:
  t: time of ignition
  p: ignition-probability"
  [{:keys
    [num-rows num-cols cell-size landfire-rasters global-clock spotting rand-gen
     multiplier-lookup perturbations temperature relative-humidity wind-speed-20ft
     wind-from-direction] :as inputs}
   {:keys [firebrand-count-matrix fire-spread-matrix fire-line-intensity-matrix flame-length-matrix]}
   {:keys [cell fire-line-intensity crown-fire?]}]
  (when (spot-fire? inputs crown-fire? cell fire-line-intensity)
    (let [tmp               (get-value-at cell
                                          global-clock
                                          temperature
                                          (:temperature multiplier-lookup)
                                          (:temperature perturbations))
          rh                (get-value-at cell
                                          global-clock
                                          relative-humidity
                                          (:relative-humidity multiplier-lookup)
                                          (:relative-humidity perturbations))
          ws                (get-value-at cell
                                          global-clock
                                          wind-speed-20ft
                                          (:wind-speed-20ft multiplier-lookup)
                                          (:wind-speed-20ft perturbations))
          ^double
          wd                (get-value-at cell
                                          global-clock
                                          wind-from-direction
                                          (:wind-from-direction multiplier-lookup)
                                          (:wind-from-direction perturbations))
          fuel-moisture     (or (fuel-moisture-from-raster inputs cell global-clock)
                                (get-fuel-moisture rh temperature))
          deltas            (sample-wind-dir-deltas inputs
                                                    fire-line-intensity-matrix
                                                    (convert/mph->mps ws)
                                                    cell)
          wind-to-direction (mod (+ 180 wd) 360)
          firebrands        (firebrands deltas wind-to-direction cell cell-size)]
      (update-firebrand-counts! inputs firebrand-count-matrix fire-spread-matrix cell firebrands)
      (->> (for [[x y] firebrands
                 :when (and (in-bounds? num-rows num-cols [x y])
                            (burnable? fire-spread-matrix (:fuel-model landfire-rasters) cell [x y]))
                 :let  [firebrand-count (m/mget firebrand-count-matrix x y)
                        spot-ignition-p (spot-ignition-probability inputs
                                                                   spotting
                                                                   fuel-moisture
                                                                   tmp
                                                                   firebrand-count
                                                                   cell
                                                                   [x y])]]
             (when (spot-ignition? rand-gen spot-ignition-p)
               (let [[i j] cell
                     t     (spot-ignition-time global-clock
                                               (ft->m (m/mget flame-length-matrix i j))
                                               (convert/mph->mps ws))]
                 [[x y] [t spot-ignition-p]])))
           (remove nil?)))))
;; spread-firebrands ends here
