(ns revert-backtester.backtester
  (:require
   [cljs-http.client :as http]
   [bignumber.core :as bn]
   ["@uniswap/sdk-core" :as sdk-core]
   ["@uniswap/v3-sdk" :as univ3]
   [clojure.string :as string]
   [cljs.core.async :refer [go]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [revert-backtester.utils :as u]))


(defn tick-spacing-by-fee
  [fee-tier]
  (case (str fee-tier)
    "10000" 200
    "3000" 60
    "500" 10
    "100" 1))


(defn floor-tick
  [tick tick-spacing]
  (- tick (mod tick tick-spacing)))


(defn make-token
  [chain-id token-address token-decimals]
  (sdk-core/Token.
   chain-id token-address token-decimals))


(defn make-pool
  [token0 token1 fee-tier sqrt-price pool-liquidity pool-tick]
  (univ3/Pool.
   token0 token1 fee-tier sqrt-price pool-liquidity pool-tick))


(defn make-position-params
  [pool tick-lower tick-upper liquidity]
  (clj->js {:pool pool
            :tickLower tick-lower
            :tickUpper tick-upper
            :liquidity liquidity}))


(defn make-position
  [pool tick-lower tick-upper liquidity]
  (univ3/Position.
   (make-position-params pool tick-lower tick-upper liquidity)))


(defn position-amount0
  [position]
  (u/bn (. (. position -amount0)
           toFixed)))


(defn position-amount1
  [position]
  (u/bn (. (. position -amount1)
           toFixed)))



(defn get-chain-id-for-network [network]
  (case network
    "polygon" 137
    1))


(defn tick->price
  [network tick-idx token0-info token1-info]
  (try
    (if (and (some? token0-info) (some? token1-info))
      (let [chain-id (get-chain-id-for-network network)
            token0 (make-token
                    chain-id (:address token0-info) (:decimals token0-info))
            token1 (make-token
                    chain-id (:address token1-info) (:decimals token1-info))]
        (. (univ3/tickToPrice token0 token1 (js/parseInt tick-idx))
           toFixed 20))
      0)
    (catch js/Error err (js/console.log "tick->price:" err 0))))



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






(defn <v3-pool-hours-
  "For a Uniswap v3 `pool-address` and its underlying
  assets `token0-address` and `token1-address` get
  the pool states for the latest 1000 recorded periods."
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
              (<! (<v3-pool-hours- network pool-address token0-address token1-address :retries (dec retries))))
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


(defn increment-hour
  [pool-hour]
  (into pool-hour
        {:timestamp (u/ts->datetime
                     (+ (u/datetime->ts (:timestamp pool-hour))
                        3600))}))



(defn densify-periods
  "The results from getting the graph's poolHours is sparse, that is to say
  some hours are missing, probably because there were no changes
  in the pool values for those hours. This does not affect calculations
  except for time-in-range. This fn takes the sparse `periods`
  and returns them with the missing hours filled with the previos hour's
  values."
  ([periods]
   (densify-periods periods []))
  ([periods res]
   (if (empty? periods)
     res
     (let [current-ts (:timestamp (first periods))
           next-ts (:timestamp (second periods))]
       (if (> (- next-ts current-ts) 3600000)
         (recur (concat [(increment-hour (first periods))]
                        (rest periods))
                (conj res (first periods)))
         (recur (rest periods)
                (conj res (first periods))))))))



(defn <historic-states
  "For a Uniswap v3 `pool-address` and its underlying
  assets `token0-address` and `token1-address` get
  the pool states for the latest 1000 recorded periods, and drop
  any record that does not have recored ticks/prices"
  [network pool-address token0-address token1-address]
  (go
    (let [pool-hours
          (<! (<v3-pool-hours-
               network pool-address token0-address token1-address))
          pool-periods (filter #(and
                                 (not (bn/= (:close %) (u/bn "0")))
                                 (not (bn/= (:low %) (u/bn "0")))
                                 (not (bn/= (:high %) (u/bn "0")))
                                 (not (nil? (:tick %))))
                               (densify-periods (reverse pool-hours)))]
      pool-periods)))


(defn make-pool-from-state
  "Takes a `pool-state` map as the ones
  passed to `backtest-positioin` and instantiates a
  v3 pool js object`"
  [pool-state]
  (let [token0-decimals (:token0-decimals pool-state)
        token1-decimals (:token1-decimals pool-state)
        token0-address (:token0-address pool-state)
        token1-address (:token1-address pool-state)
        fee-tier (js/parseInt (:fee-tier pool-state))
        sqrt-price (:sqrt-price pool-state)
        pool-liquidity (. (:liquidity pool-state) toFixed)
        pool-tick (:tick pool-state)
        chain-id (get-chain-id-for-network (:network pool-state))
        token0 (make-token
                chain-id token0-address token0-decimals)
        token1 (make-token
                chain-id token1-address token1-decimals)]
    (make-pool
     token0 token1 fee-tier sqrt-price pool-liquidity pool-tick)))


(defn dilution-factor
  "This returns the factor for which fee dilution would
   occur when adding the given amount of liquidity in the pool.
  NOTE: This is just an approximation, as we are taking the current active
  liquidity instead of the active liquidity at each historic state."
  [pool-ticks pool-state-prev position-liquidity]
  (let [tick-spacing (tick-spacing-by-fee (:fee-tier pool-state-prev))
        active-tickidx (str (floor-tick (:tick pool-state-prev)
                                                tick-spacing))
        active-tick (first (filter #(= (str (:tickidx %))
                                       active-tickidx)
                                   pool-ticks))
        ;; liquidity-active is all the liquidity referencing
        ;; the tick in question. that is to say, it is all
        ;; the liquidity that would be awarded fees if
        ;; swaps happen in that tick.
        active-liquidity (:liquidity-active active-tick)]
    (bn// active-liquidity
          (bn/+ position-liquidity active-liquidity))))



(defn unbounded-fees
  "Returns the unbounded fee growths for a pool given
  the current state `pool-state` and the previous state `pool-state-prev`
  where the pool-states are maps as those passed to `backtest-position`"
  [pool-state prev-pool-state pool-dilution]
  (let [fee-unbounded0 (bn/* pool-dilution
                             (bn/- (:fee-growth-global0 pool-state)
                                   (:fee-growth-global0 prev-pool-state)))
        fee-unbounded1 (bn/* pool-dilution
                             (bn/- (:fee-growth-global1 pool-state)
                                   (:fee-growth-global1 prev-pool-state)))]
    [fee-unbounded0 fee-unbounded1]))


(defn within-range?
  [limit-lower limit-upper low-price high-price]
  (and (bn/> high-price limit-lower)
       (bn/< low-price limit-upper)))


(defn active-ratio
  "We get a position's `limit-lower` and `limit-upper`` and the
  `low-price` and `high-price` for that (hour) period. We then
  assume an even distribution of volume for all the ticks
  contined between the low and high prices, and
  compute the proportion (0 to 1) in which the position would
  have been active."
  [limit-lower limit-upper low-price high-price]
  (if (bn/= high-price low-price)
    (if (within-range? limit-lower limit-upper low-price high-price)
      (u/bn "1")
      (u/bn "0"))
    (let [price-range (bn/- high-price low-price)
          ratio (bn// (bn/- (u/min-bn [high-price limit-upper])
                            (u/max-bn [low-price limit-lower]))
                      price-range)]
      (if (or (not (within-range? limit-lower limit-upper low-price high-price))
              (. ratio isNaN))
        (u/bn "0")
        ratio))))


(defn proportional-fees
  [fee-unbounded0 fee-unbounded1
   liquidity active-portion]
  (let [
        fees0 (bn// (reduce bn/* [fee-unbounded0 liquidity active-portion])
                    (u/bn (Math/pow 2 128)))
        fees1 (bn// (reduce bn/* [fee-unbounded1 liquidity active-portion])
                    (u/bn (Math/pow 2 128)))]
    [fees0 fees1]))


(defn position-active?
  [pool-state tick-lower tick-upper]
  (and (>= (:tick pool-state) tick-lower)
       (<= (:tick pool-state) tick-upper)))


(defn period-fees
  [pool-ticks pool-state-current pool-state-prev
   liquidity price-lower price-upper low-price high-price dilute-fees?]
  (let [pool-dilution (if dilute-fees?
                        (dilution-factor pool-ticks pool-state-prev liquidity)
                        (u/bn "1.0"))
        [fee-unbounded0 fee-unbounded1] (unbounded-fees
                                         pool-state-current pool-state-prev pool-dilution)
        active-portion (active-ratio price-lower price-upper low-price high-price)]
    (proportional-fees
     fee-unbounded0 fee-unbounded1 liquidity active-portion)))


(defn compute-apr
  [pool-state pnl ref-value first-ts]
  (let [days-num (u/seconds->days
                  (- (u/datetime->ts (:timestamp pool-state))
                     (u/datetime->ts first-ts)))
        year-portion (bn// (u/bn (str days-num)) (u/bn "365"))
        multiplier (bn// (u/bn "1") year-portion)]
    (if (bn/= (u/bn 0) ref-value)
      (u/bn "0")
      (bn/* (bn/* (bn// pnl ref-value)
                  multiplier)
            (u/bn "100.0")))))


(defn compute-pnl
  [current-value ref-value]
  (if (bn/= (u/bn 0) ref-value)
    (u/bn "0")
    (bn/- current-value ref-value)))


(defn get-period-price0
  [pool-state reference-price]
  (case reference-price
    :hodl (:token0-price-usd pool-state)
    :token0 (u/bn "1")
    :token1 (:token1-price pool-state)))


(defn get-period-price1
  [pool-state reference-price]
  (case reference-price
    :hodl (:token1-price-usd pool-state)
    :token0 (:token0-price pool-state)
    :token1 (u/bn "1")))


(defn period-prices
  [pool-state-current options]
  (let [token0-decimals (:token0-decimals pool-state-current)
        token1-decimals (:token1-decimals pool-state-current)
        network (:network pool-state-current)
        token0 {:decimals token0-decimals
                :address (:token0-address pool-state-current)}
        token1 {:decimals token1-decimals
                :address (:token1-address pool-state-current)}
        ;; prices in terms of token1
        price-lower (u/bn (tick->price
                           network
                           (:tick-upper options)
                           token1
                           token0))
        price-upper (u/bn (tick->price
                           network
                           (:tick-lower options)
                           token1
                           token0))]
    [price-lower price-upper]))


(defn backtest-position
  "Takes `historic-states` which are derived from
  thegraphs poolHours entities (see backtester-tester).
  `options` which is a map with the following requried keys:
  :tick-lower
  :tick-upper
  :liquidity
  :first-ts timstamp from which to start backtesting
  :dilute-fees? (bool) if true will simulate fee dilution given `pool-ticks`
  reference-price (enum) :hodl :token0 :token1"
  ([historic-states options pool-ticks]
   (backtest-position historic-states options pool-ticks []))
  ([historic-states options pool-ticks res]
   (let [{:keys [tick-lower tick-upper liquidity
                 dilute-fees? reference-price first-ts]} options]
     (if (= (count historic-states) 1)
       res
       (let [pool-state-prev (first historic-states)
             pool-state-current (second historic-states)
             pool (make-pool-from-state pool-state-current)
             position (make-position pool tick-lower tick-upper liquidity)
             liquidity-bn (u/ebn->bn (str (. position -liquidity)) 0)
             amount0 (position-amount0 position)
             amount1 (position-amount1 position)
             high (:high pool-state-current)
             low (:low pool-state-current)
             token0-decimals (:token0-decimals pool-state-current)
             token1-decimals (:token1-decimals pool-state-current)
             init-amount0 (get (first res) :amount0 (u/bn "0"))
             init-amount1 (get (first res) :amount1 (u/bn "0"))
             init-price0 (get (first res) :price0 (u/bn "0"))
             init-price1 (get (first res) :price1 (u/bn "0"))
             [price-lower price-upper] (period-prices pool-state-current options)
             [fees0 fees1] (period-fees pool-ticks pool-state-current pool-state-prev
                                        liquidity-bn price-lower price-upper
                                        low high dilute-fees?)
             current-fees0 (u/eth->dec fees0 token0-decimals)
             current-fees1 (u/eth->dec fees1 token1-decimals)
             prev-accum-fees0 (:accum-fees0 (last res))
             prev-accum-fees1 (:accum-fees1 (last res))
             accum-fees0 (bn/+ (or prev-accum-fees0 (u/bn "0"))
                               current-fees0)
             accum-fees1 (bn/+ (or prev-accum-fees1 (u/bn "0"))
                               current-fees1)
             current-total0 (bn/+ amount0 accum-fees0)
             current-total1 (bn/+ amount1 accum-fees1)
             period-price0 (get-period-price0 pool-state-current reference-price)
             period-price1 (get-period-price1 pool-state-current reference-price)
             ref-value (bn/+ (bn/* init-amount1 (if (= reference-price :hodl)
                                                  period-price1
                                                  init-price1))
                             (bn/* init-amount0 (if (= reference-price :hodl)
                                                  period-price0
                                                  init-price0)))
             current-value (bn/+ (bn/* amount1 period-price1)
                                 (bn/* amount0 period-price0))
             current-value-with-fees (bn/+ (bn/* current-total1 period-price1)
                                           (bn/* current-total0 period-price0))
             il (bn/- current-value ref-value)
             pnl (compute-pnl current-value-with-fees ref-value)
             apr (compute-apr pool-state-current pnl ref-value first-ts)
             is-active? (position-active? pool-state-prev tick-lower tick-upper)
             res' (conj res {:timestamp (:timestamp pool-state-current)
                             :date (:date pool-state-current)
                             :amount0 amount0
                             :amount1 amount1
                             :price0 period-price0
                             :price1 period-price1
                             :liquidity liquidity-bn
                             ;;:current-price current-price
                             :il il
                             :pnl pnl
                             :apr apr
                             :position-active? is-active?
                             :accum-fees0 accum-fees0
                             :accum-fees1 accum-fees1
                             :fees0 current-fees0
                             :fees1 current-fees1})]
         (recur (rest historic-states) options pool-ticks res'))))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The functions below take the results of `backtest-position`
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(defn time-in-range
  [computed-fees]
  (try
    (let [computed-fees' (densify-periods computed-fees)
          total-periods (count computed-fees')
          active-periods (count (filter #(true? (:position-active? %)) computed-fees'))
          active-fraction (if (= total-periods 0)
                            total-periods
                            (/ active-periods total-periods))]
      (* active-fraction 100))
    (catch js/Error err (js/console.log "time-in-range err:" err) 0)))



(defn initial-amounts
  [computed-fees]
  (let [initial-pos (first computed-fees)]
    {:amount0 (:amount0 initial-pos)
     :amount1 (:amount1 initial-pos)
     :price0 (:price0 initial-pos)
     :price1 (:price1 initial-pos)}))


(defn final-amounts
  [computed-fees]
  (let [last-pos (last computed-fees)]
    {:amount0 (:amount0 last-pos)
     :amount1 (:amount1 last-pos)
     :price0 (:price0 last-pos)
     :price1 (:price1 last-pos)}))


(defn initial-date
  [computed-fees]
  (let [initial-pos (first computed-fees)]
    (:timestamp initial-pos)))


(defn final-date
  [computed-fees]
  (let [last-pos (last computed-fees)]
    (:timestamp last-pos)))


(defn average-daily-fees
  [computed-fees]
  (try
    (let [initial-ts (u/datetime->ts (:timestamp (first computed-fees)))
          final-ts (u/datetime->ts (:timestamp (last computed-fees)))
          num-days (u/bn (u/seconds->days (- final-ts initial-ts)))
          avg-fees0 (bn// (:accum-fees0 (last computed-fees))
                          num-days)
          avg-fees1 (bn// (:accum-fees1 (last computed-fees))
                          num-days)]
      {:amount0 avg-fees0
       :amount1 avg-fees1})
    (catch js/Error err (js/console.log "avg-daily-fees:" err))))


(defn total-fees
  [computed-fees]
  {:amount0 (:accum-fees0 (last computed-fees))
   :amount1 (:accum-fees1 (last computed-fees))
   :price0 (:price0 (last computed-fees))
   :price1 (:price1 (last computed-fees))})


