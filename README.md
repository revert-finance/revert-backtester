# revert-backtester

A fast backtester for Uniswap v3 positions.

![scatterplot-full png](https://user-images.githubusercontent.com/21986/154587965-2ae05fc1-b562-4bf7-96d3-1b84f018d621.png)

## Using the backtester on a browser-connected REPL
```
(def pool-address "0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640")

(def token0-address "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")

(def token1-address "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2")

(go (def pool-periods
        (<! (backtester/<historic-states
             "mainnet" pool-address token0-address token1-address))))
             
             
(def backtest-options {:tick-lower 193380
                       :tick-upper 200310
                       :liquidity "326453009134297"
                       :first-ts (:timestamp (first pool-periods))
                       :dilute-fees? false
                       :reference-price :hodl})
                         
                         
                         
(def backtest-results (backtester/backtest-position
                         pool-periods backtest-options []))
                         
                                     
                                     
(u/dejs (last backtest-results))

{:price1 "2800.561447581661804800445923843118",
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
   ```
   

## Testing the backtester against a set of relevant open positions

Testing against the full set of open mainnet positions that meet the following criteria:
 - Were minted in the last 30 days
 - Only have one mint (deposit)
 - Have no withdrawals

![scatterplot-full png](https://user-images.githubusercontent.com/21986/154587965-2ae05fc1-b562-4bf7-96d3-1b84f018d621.png)

Histogram of a subset from the above set that also match the following criteria:
- Were in range 100% of the time since they were minted.

![histogram-full](https://user-images.githubusercontent.com/21986/154588209-e1d59e65-56f3-4316-82cd-2294bbffe53d.png)




## Usage



### for testing on a browser-connected repl


##### get the full testing set
`(go (def tracked-positions (<! (backtester-tester/<fetch-testing-set))))`


##### backrtest a specific position
`(go (def p0 (<! (<test-pos- 191651 tracked-positions))))`


##### test the first 100 positions of the testing set (this will take a minute)
```
(go (def tested-positions100 (<! (backtester-tester/<backtest-positions-chunked
                                    (take 100 tracked-positions) 20 println))))
```


##### test the full testing set (this will take a few minutes)
```
(go (def tested-positions (<! (backtester-tester/<backtest-positions-chunked
                                 (identity tracked-positions) 20 println))))
```


##### render accuarcy plot
`(render-accuracy-plot tested-positions100)`

##### render accuracy histogram for position 100% time in range
```
(render-accuracy-histogram (filter #(bn/= (:time-in-range (:results %)) 100)
                                     tested-positions100))
```
                                     

## License

Copyright © 2022 Revert Labs Inc

Distributed under the MIT License
