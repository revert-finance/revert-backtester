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
