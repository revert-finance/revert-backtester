(ns revert-backtester.core
  (:require
   [bignumber.core :as bn]
   ["@uniswap/sdk-core" :as sdk-core]
   ["@uniswap/v3-sdk" :as univ3]
   [clojure.string :as string]
   [cljs.core.async :refer [go]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [revert-backtester.utils :as u]
   [revert-backtester.backtester :as backtester]
   [revert-backtester.backtester-tester :as backtester-tester]))


(defn main
  [])



(defn <test-pos- [nft-id tracked-positions]
  (go (backtester-tester/add-accuracy
       (<! (backtester-tester/<backtest-position
            (first (filter #(= (:nft-id %) nft-id)
                           tracked-positions)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; for testing on a browser-connected repl
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;usdc/eth @ 0.05%
#_(def pool-address "0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640")
;; usdc
#_(def token0-address "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
;; weth
#_(def token1-address "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")

;; get historic pool states
#_(go (def pool-periods
        (<! (backtester/<historic-states
             "mainnet" pool-address token0-address token1-address))))

#_(def backtest-options {:tick-lower 193380
                         :tick-upper 200310
                         :liquidity "326453009134297"
                         :first-ts (:timestamp (first pool-periods))
                         :dilute-fees? false
                         :reference-price :hodl})


#_(def backtest-results (backtester/backtest-position
                         pool-periods backtest-options []))

#_(u/dejs (last backtest-results))
#_{:price1 "2800.561447581661804800445923843118",
   :position-active? true,
   :date 1645203600,
   :apr "40.798756877089499992439067416587414203",
   :accum-fees0 "166.08025426813868469377",
   :fees0 "0.05728121020264660731",
   :amount1 "1.013650509408661188",
   :pnl "259.307971699411288081104982934500459025162314463603570623",
   :liquidity "326453009134297",
   :price0 "0.9999999999999999999999999999999999",
   :accum-fees1 "0.06097927761789486278",
   :il "-77.548496566783063288340422500588600828351985579876",
   :amount0 "2656.898529",
   :timestamp #inst "2022-02-18T17:00:00.000-00:00",
   :fees1 "0.00001831200180145388"}


;; get the full testing set
#_(go (def tracked-positions (<! (backtester-tester/<fetch-testing-set))))


;; test the first 100 positions of the testing set (this will take a minute)
#_(go (def tested-positions100 (<! (backtester-tester/<backtest-positions-chunked
                                    (take 100 tracked-positions) 20 println))))


;; test the full testing set (this will take a few minutes)
#_(go (def tested-positions (<! (backtester-tester/<backtest-positions-chunked
                                 (identity tracked-positions) 20 println))))


;; render accuarcy plot
#_(render-accuracy-plot tested-positions100)

;; render accuracy histogram for position 100% time in range
#_(render-accuracy-histogram (filter #(bn/= (:time-in-range (:results %)) 100)
                                     tested-positions100))
