(ns revert-backtester.utils
  (:require [cljs.reader :as reader]
            [clojure.walk :as walk]
            [bignumber.core]
            [clojure.string :as string]
            [goog.uri.utils :as uri]
            [bignumber.js :as BigNumber]
            [bignumber.core :as bn]
            [cljs.core.async :refer [go <!]]
            ["ethers" :as ethers]))


(defn isnan?
  [n]
  (js/Number.isNaN n))


(defn str->int
  [s]
  (reader/read-string s))


(defn _max-bn
  [a b]
  (.max BigNumber a b))

(defn max-bn
  [numbers]
  (reduce _max-bn numbers))

(defn _min-bn
  [a b]
  (.min BigNumber a b))

(defn min-bn
  [numbers]
  (reduce _min-bn numbers))

(defn bn?
  [n]
  (.isBigNumber BigNumber n))



(defn bn [n] (BigNumber. (str n)))

(defn make-unix-ts
  []
  (. js/Math floor (/ (js/Date.now) 1000)))

(defn seconds->days
  [seconds]
  (/ seconds (* 60 60 24)))


(defn ts->datetime
  [ts]
  (js/Date. (* 1000 ts)))

(defn datetime->ts
  [dt]
  (/ (.getTime dt) 1000))

(defn seconds-ago
  [unix-ts]
  (- (make-unix-ts) unix-ts))


(defn ethfu
  [n u]
  (. ethers/utils formatUnits n u))

(defn ethpu
  [n u]
  (. ethers/utils parseUnits n u))

(defn ebn->bn
  [n u]
  (bn (ethfu n u)))


(defn eth->dec
  [n u]
  (let [units (bignumber.core/pow (BigNumber. 10) u)]
    (bignumber.core// n units)))

(defn json->keyword [json]
  (keyword (clojure.string/replace json "_" "-")))


(defn convert-keys
  "Converts JS type keywords to Clojure keywords"
  [params]
  (into {} (for [[k v] params] [(json->keyword (name k)) v])))


;;https://dnaeon.github.io/recursively-merging-maps-in-clojure/
(defn deep-merge
  "Recursively merges maps."
  [& maps]
  (letfn [(m [& xs]
            (if (some #(and (map? %) (not (record? %))) xs)
              (apply merge-with m xs)
              (last xs)))]
    (reduce m maps)))


(defn dejs
  "for cider/repl priting without js obj nastyness"
  [form]
  (clojure.walk/postwalk
   (fn [x] (if (bn? x) (str x) x)) form))
