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


(defn v3-token-hours-query-string
  [token-address]
  (str "{tokenHourDatas(orderBy: periodStartUnix,
                   orderDirection: desc,
                   where: {token:\"" token-address "\"}
                   first: 1000) {
    periodStartUnix,
    token {id},
    priceUSD
      }}"))


(defn v3-pool-hours-query-string
  [pool-address token0-address token1-address]
  (str "{
      poolHourDatas(orderBy: periodStartUnix,
                   orderDirection: desc,
                   where: {pool: \"" pool-address "\"}
                   first: 1000) {
         id
         periodStartUnix,
         pool {id,
         token0 {
             id,
            symbol,
            name,
            decimals

         },
         token1 {
             id,
            symbol,
            name,
            decimals
         }
         feeTier},
         liquidity,
         sqrtPrice,
         token0Price,
         token1Price,
         tick,
         tvlUSD,
         volumeToken0,
         volumeToken1
         volumeUSD,
         open,
         high,
         low,
         close
         txCount,
         feeGrowthGlobal0X128,
         feeGrowthGlobal1X128
       }
   }"))


(defn get-univ3-subgraph-url [network]
  (case network
    "polygon" "https://api.thegraph.com/subgraphs/name/ianlapham/uniswap-v3-polygon"
    "https://api.thegraph.com/subgraphs/name/ianlapham/uniswap-v3-subgraph"))


(defn <v3-token-hour-prices
  "Gets hourly prices for `token0-address` and `token1-adddress`
  in `network` ('mainnet', 'polygon') from the graph  and returns
  a map with shape:
  {token0-address {timestamp usd-price..,}
   token1-address {timestamp usd-price..,}}"
  [network token0-address token1-address]
  (go
    (let [data-token0
          (:data (:body
                  (<! (http/post
                       (get-univ3-subgraph-url network)
                       {:with-credentials? false
                        :body (js/JSON.stringify
                               (clj->js
                                {:query
                                 (v3-token-hours-query-string token0-address)}))}))))
          data-token1
          (:data (:body
                  (<! (http/post
                       (get-univ3-subgraph-url network)
                       {:with-credentials? false
                        :body (js/JSON.stringify
                               (clj->js
                                {:query
                                 (v3-token-hours-query-string token1-address)}))}))))
          token-prices (apply u/deep-merge
                              (map (fn [x]
                                     {(string/lower-case (:id (:token x)))
                                      {(:periodStartUnix x) (:priceUSD x)}})
                                   (concat (:tokenHourDatas data-token0)
                                           (:tokenHourDatas data-token1))))]
      token-prices)))


(defn <v3-pool-hours
  "For a Uniswap v3 `pool-address` and its underlying
  assets `token0-address` and `token1-address` get
  the hourly states for the pool for the latest 1000 hours."
  [network pool-address token0-address token1-address
   & {:keys [retries] :or {retries 3}}]
  (go (let [pool-address (string/lower-case pool-address)
            token0-address (string/lower-case token0-address)
            token1-address (string/lower-case token1-address)
            hour-data
            (<! (http/post
                 (get-univ3-subgraph-url network)
                 {:with-credentials? false
                  :body (js/JSON.stringify
                         (clj->js {:query
                                   (v3-pool-hours-query-string
                                    pool-address token0-address token1-address)}))}))
            pool-hours (:poolHourDatas (:data (:body hour-data)))
            token-prices (<! (<v3-token-hour-prices network token0-address token1-address))]
        (if (and (zero? (count pool-hours)) (> retries 0))
          ;;thegraph can get unreliable
          (do (js/console.log "Retrying graph dailys for:" pool-address)
              (<! (<v3-pool-hours network pool-address token0-address token1-address :retries (dec retries))))
          (map (fn [pool-hour]
                 (let [date-ts (:periodStartUnix pool-hour)
                       token0 (string/lower-case (:id (:token0 (:pool pool-hour))))
                       token1 (string/lower-case (:id (:token1 (:pool pool-hour))))
                       price0-usd (u/bn (get-in token-prices [token0 date-ts]))
                       price1-usd (u/bn (get-in token-prices [token1 date-ts]))]
                   {:network network
                    :exchange "uniswapv3"
                    :address (string/lower-case (:id (:pool pool-hour)))
                    :fee-tier (:feeTier (:pool pool-hour))
                    :date date-ts
                    :timestamp (u/ts->datetime date-ts)
                    :token0-decimals (u/str->int (:decimals (:token0 (:pool pool-hour))))
                    :token0-symbol (:symbol (:token0 (:pool pool-hour)))
                    :token0-address token0-address
                    :token0-name (:name (:token0 (:pool pool-hour)))
                    :token1-decimals (u/str->int (:decimals (:token1 (:pool pool-hour))))
                    :token1-symbol (:symbol (:token1 (:pool pool-hour)))
                    :token1-address token1-address
                    :token1-name (:name (:token1 (:pool pool-hour)))
                    :reserves-usd (u/bn (:tvlUSD pool-hour))
                    :volume-usd (u/bn (:volumeUSD pool-hour))
                    :volume0 (u/bn (:volumeToken0 pool-hour))
                    :volume1 (u/bn (:volumeToken1 pool-hour))
                    :fee-growth-global0 (u/bn (:feeGrowthGlobal0X128 pool-hour))
                    :fee-growth-global1 (u/bn (:feeGrowthGlobal1X128 pool-hour))
                    :open (u/bn (:open pool-hour))
                    :high (u/bn (:high pool-hour))
                    :low (u/bn (:low pool-hour))
                    :close (u/bn (:close pool-hour))
                    :liquidity (u/bn (:liquidity pool-hour))
                    :token0-price (u/bn (:token0Price pool-hour))
                    :token1-price (u/bn (:token1Price pool-hour))
                    :token0-price-usd price0-usd
                    :token1-price-usd price1-usd
                    :sqrt-price (:sqrtPrice pool-hour)
                    :tick (u/str->int (:tick pool-hour))}))
               pool-hours)))))



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
          pool-hours
          (<! (<v3-pool-hours
               "mainnet" (:pool position) (:token0 position) (:token1 position)))
          pool-periods (filter #(and
                                 (not (bn/= (:close %) (u/bn "0")))
                                 (not (bn/= (:low %) (u/bn "0")))
                                 (not (bn/= (:high %) (u/bn "0")))
                                 (>= (:timestamp %)
                                     (u/ts->datetime (:first-mint-ts position)))
                                 (<= (:timestamp %)
                                     (u/ts->datetime (:now-ts position)))
                                 (not (nil? (:tick %))))
                               (backtester/densify-results (reverse pool-hours)))
          nil-prices? (has-nil-prices? pool-periods)
          backtest-options {:tick-lower (:tick-lower position)
                            :tick-upper (:tick-upper position)
                            :liquidity (:liquidity position)
                            :first-ts (:timestamp (first pool-periods))
                            :dilute-fees? false
                            :reference-price :hodl}
          backtest-results (backtester/backtest-position
                            pool-periods backtest-options pool-ticks)
          time-in-range (backtester/time-in-range backtest-results)
          position-active? (:position-active? (last backtest-results))
          last-update (:timestamp (last pool-periods))]
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


