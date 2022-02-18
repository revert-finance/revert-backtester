(ns revert-backtester.backtester-tester
  (:require
   [reagent.core :as reagent]
   [reagent.dom :as rdom]
   [oz.core :as oz]
   [cljs-http.client :as http]
   [bignumber.core :as bn]
   [clojure.string :as string]
   [cljs.core.async :refer [go <!]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [revert-backtester.utils :as u]
   [revert-backtester.backtester :as backtester]))



(defn parse-testing-position
  [p]
  (into
   p {:nft-id (js/parseInt (:nft-id p))
      :total-fees0 (u/bn (:total-fees0 p))
      :total-fees1 (u/bn (:total-fees1 p))
      :first-mint-ts (js/parseInt (:first-mint-ts p))
      :now-ts (js/parseInt (:now-ts p))
      :age (js/parseFloat (:age p))
      :apr (u/bn (:apr p))
      :roi (u/bn (:roi p))
      :tick-lower (js/parseInt (:tick-lower p))
      :tick-upper (js/parseInt (:tick-upper p))}))


(defn <fetch-testing-set
  "Gets a testing set from the revert api of v3 positions
  that match the following criteria:
  - Was minted in the last 30 days
  - Only has one mint (deposit)
  - Has no withdrawals"
  []
  (go (let [res (<! (http/get
                     "https://api.revert.finance/v1/backtester_helper"
                     {:with-credentials? false}))
            parsed (map parse-testing-position
                        (map u/convert-keys (:data (:body res))))]
        parsed)))




(defn fee-prediction-accuracy
  "Takes a `position` map as those returned
  by `<fetch-testing-set` and `results` as returned by `backtester/backtest-position`
  and computes the backtester prediction accuracy for each asset as:
  (* (/ predicted-fees actual-fees) 100)
  The total accuracy is computed in terms of current pool price."
  [position results]
  (try
    (let [position-fees0 (u/bn (:total-fees0 position))
          position-fees1 (u/bn (:total-fees1 position))
          current-price (:current-price results)
          predicted-fees0 (:accum-fees0 results)
          predicted-fees1 (:accum-fees1 results)
          position-fee-value (bn/+ position-fees0 (bn/* current-price position-fees1))
          prediction-fee-value (bn/+ predicted-fees0 (bn/* current-price predicted-fees1))
          fees-accuracy0 (bn/* (u/bn "100") (bn// predicted-fees0 position-fees0))
          fees-accuracy1 (bn/* (u/bn "100") (bn// predicted-fees1 position-fees1))
          value-accuracy (bn/* (u/bn "100") (bn// prediction-fee-value position-fee-value))]
      {:accuracy0 fees-accuracy0
       :accuracy1 fees-accuracy1
       :accuracy (. value-accuracy dp 0)})
    (catch js/Error err (js/console.log "err:" err)
           {:accuracy0 (u/bn "0")
            :accuracy1 (u/bn "0")
            :accuracy (u/bn "0")})))


(defn increment-hour
  [pool-hour]
  (into pool-hour
        {:timestamp (u/ts->datetime
                     (+ (u/datetime->ts (:timestamp pool-hour))
                        3600))}))

(defn has-nil-prices?
  "Check if the pool ever dips below the min TVL
  required for subgraph usd prices (resulting in a price of $0 for
  either token)."
  [pool-periods]
  (not (zero? (count (filter #(or (bn/= (:token0-price-usd %) (u/bn "0"))
                                  (bn/= (:token1-price-usd %) (u/bn "0")))
                             pool-periods)))))


(defn <backtest-position
  "Takes a `position` map as those returned
   by `<fetch-testing-set` and runs a backtest that
  replicates the positions' parameters.
  Returns a map with the og position and the last period
  of the backtested results."
  [position]
  (go
    (let [pool-ticks []
          pool-periods
          (<! (backtester/<historic-states
               "mainnet" (:pool position) (:token0 position) (:token1 position)))
          pool-periods' (filter #(and
                                  (>= (:timestamp %)
                                      (u/ts->datetime (:first-mint-ts position)))
                                  (<= (:timestamp %)
                                      (u/ts->datetime (:now-ts position))))
                                pool-periods)
          nil-prices? (has-nil-prices? pool-periods')
          backtest-options {:tick-lower (:tick-lower position)
                            :tick-upper (:tick-upper position)
                            :liquidity (:liquidity position)
                            :first-ts (:timestamp (first pool-periods'))
                            :dilute-fees? false
                            :reference-price :hodl}
          backtest-results (backtester/backtest-position
                            pool-periods' backtest-options pool-ticks)
          time-in-range (backtester/time-in-range backtest-results)
          position-active? (:position-active? (last backtest-results))
          last-update (:timestamp (last pool-periods'))]
      {:position position
       :num-results (count backtest-results)
       ;;:og-results backtest-results
       :last-update last-update
       :position-active? position-active?
       :nil-prices? nil-prices?
       :results (into (last backtest-results)
                      {:time-in-range time-in-range})})))


(defn add-accuracy
  [tested-position]
  (into tested-position
        {:accuracy (fee-prediction-accuracy
                    (:position tested-position)
                    (:results tested-position))}))


(defn <backtest-positions
  [positions]
  (->> (map (fn [p] (go (<! (<backtest-position p))))
            positions)
       (cljs.core.async/merge)
       (cljs.core.async/reduce conj [])))


(defn <backtest-positions-chunked
  "Backtest a large amount of `positions`` in chunks
  of max `chink-size`.
  `update-message-fn` gets called on each chunk, can be
  e.g. js/console.log"
  [positions chunk-size update-msg-fn]
  (let [chunks (partition chunk-size positions)
        many-chunks (count chunks)
        start-time (u/make-unix-ts)]
    (cljs.core.async/go-loop [current-chunks chunks results []]
      (let [current-chunk (first current-chunks)
            iter-res (<! (<backtest-positions current-chunk))
            prop-complete (* (/ (/ (count results) chunk-size)
                                many-chunks) 100)
            current-time (u/make-unix-ts)
            running-time (- current-time start-time)
            _ (update-msg-fn (str "Fetching position data ("
                                  prop-complete "% done) " running-time " secs."))
            results' (concat results iter-res)]
        (if (empty? current-chunks)
          results'
          (recur (rest current-chunks) results'))))))


;;;;;;;;;;;;;;;;;;;;;;;;;
;;; views / charts
;;;;;;;;;;;;;;;;;;;;;;;


(defn accuracy-scatterplot
  [data-points x-key y-key]
  {:data {:values data-points}
   :config {:background "#031116"
            :concat {:spacing 2}
            :style {:cell {:stroke "transparent"}}
            :axis {:tickColor "#111"
                   ;;:ticks nil
                   :gridColor "#657b83"
                   :gridOpacity 0.25
                   :labelColor "#657b83"
                   :titleColor "#657b83"}}
   :width 900
   :height 600
   :title {:text "Backtester Accuracy"
           :color "#657b83"}
   :layer [ {:mark {:type "point" :color "#14F46F"
                    :shale "circle" :filled true
                    :opacity 0.5}
             :encoding {:y {:field x-key
                            ;;:scale {:domain [0, 10]}
                            :title "(Fees Predicted / Actual Fees) * 100"
                            :type "quantitative"}
                        :x {:field y-key
                            :scale {:domain [51, 100]}
                            :title "Time In Range (%)"
                            :type "quantitative"}}}]})


(defn accuracy-histogram
  [data-points]
  {:data {:values data-points}
   :config {:background "#031116"
            :concat {:spacing 2}
            :style {:cell {:stroke "transparent"}}
            :axis {:tickColor "#111"
                   ;;:ticks nil
                   :gridColor "#657b83"
                   :gridOpacity 0.25
                   :labelColor "#657b83"
                   :titleColor "#657b83"}}
   :width 900
   :height 600
   :title {:text "Backtester Accuracy"
           :color "#657b83"}
   :mark {:type "bar" :color "#14F46F"}
   :encoding {:x {:field :accuracy
                  :title "(Fees Predicted / Actual Fees) * 100"
                  :bin {:binned false :step 1}}
              :y {:aggregate "count"
                  :title "Count of Positions"}}})



(defn chartify
  [tested-positions]
  (map (fn [p]
         (into (:accuracy p)
               {:nft-id (:nft-id (:position p))
                :age (:age (:position p))
                :accuracy (js/parseFloat (. (:accuracy (:accuracy p)) toFixed 0))
                :time-in-range (:time-in-range (:results p))}))
       tested-positions))



(defn plot-shell [tested-positions]
  [:div {:class ""}
   [:div {:class ""}
    [:div {:style {:min-height "400px"
                   :margin-bottom "0px"}}
     [oz/vega (accuracy-scatterplot
               (chartify tested-positions)
               :accuracy :time-in-range)]]]])

(defn histogram-shell [tested-positions]
  [:div {:class ""}
   [:div {:class ""}
    [:div {:style {:min-height "400px"
                   :margin-bottom "0px"}}
     [oz/vega (accuracy-histogram
               (chartify tested-positions))]]]])


(defn mount-view [tested-positions chart-shell]
  (let [root-el (.getElementById js/document "app")]
    (rdom/unmount-component-at-node root-el)
    (rdom/render [#(chart-shell tested-positions)] root-el)))


(defn render-accuracy-chart-
  [chart-shell tested-positions]
  (mount-view (filter #(and
                        #_(not (contains? #{185013 185033 193511}
                                        (:nft-id (:position %)))) ;; TODO attend outliers
                        (> (:num-results %) 48)
                        (not (:nil-prices? %))
                        ;; filter where latest date is recent
                        (< (/ (u/seconds-ago (u/datetime->ts (:last-update %))) (* 60 60))
                           2)
                        (bn/> (:time-in-range (:results %)) (u/bn "51"))
                        (not (u/isnan? (:accuracy (:accuracy %)))))
                      (map add-accuracy tested-positions))
              chart-shell))


(defn render-accuracy-plot
  [tested-positions]
  (render-accuracy-chart- plot-shell tested-positions))

(defn render-accuracy-histogram
  [tested-positions]
  (render-accuracy-chart- histogram-shell tested-positions))


