# revert-backtester

A fast backtester for Uniswap v3 positions.

                              
                                     
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
`(go (def tested-positions100 (<! (backtester-tester/<backtest-positions-chunked
                                    (take 100 tracked-positions) 20 println))))`


##### test the full testing set (this will take a few minutes)
`(go (def tested-positions (<! (backtester-tester/<backtest-positions-chunked
                                 (identity tracked-positions) 20 println))))`


##### render accuarcy plot
`(render-accuracy-plot tested-positions100)`

##### render accuracy histogram for position 100% time in range
`(render-accuracy-histogram (filter #(bn/= (:time-in-range (:results %)) 100)
                                     tested-positions100))`
                                     

## License

Copyright © 2022 Revert Labs Inc

Distributed under the MIT License
