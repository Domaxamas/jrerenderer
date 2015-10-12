(ns rerenderer.optimizer
  (:require [cljs.core.match :refer-macros [match]]
            [cljs.pprint :refer [pprint]]))

(defn expand-var
  "Converts vars to vals."
  [tree arg]
  (match arg
    [:var x] [:val (tree x)]
    _ arg))

(defn add-leaf
  "Adds new leaf to tree."
  [tree line]
  (let [prepaer-args (fn [args]
                       (mapv #(expand-var tree %) args))]
    (match line
      [:new result-var cls args]
      (assoc tree
        result-var [:new cls (prepaer-args args)])
      [:set var attr value]
      (update tree var conj [:set attr value])
      [:get result-var var attr]
      (assoc tree
        result-var [:get (get tree var) attr])
      [:call result-var var method args]
      (-> tree
          (assoc result-var [:call (get tree var) method
                             (prepaer-args args)])
          (update var conj [:call method (prepaer-args args)])))))

(defn build-tree
  "Builds tree for identifying unique items."
  [script]
  (reduce add-leaf {} script))

(defn ordered-vars
  "Returns list of vars by creation order."
  [script]
  (let [creational? #{:new :get :call}]
    (for [[method result-var] script
          :when (creational? method)]
      result-var)))

(defn get-new-cache
  "Updates cache with only new entries."
  [tree order]
  (->> order
       reverse
       (map #(vector (tree %) %))
       (into {})))

(defn can-be-removed?
  [[_ var & _] created cache tree]
  (and (cache tree var) (not (created var))))

(defn replace-with-cached
  [script created cache tree]
  (let [try-cache #(if (created %)
                    %
                    (get cache (tree %) %))
        try-cache-args #(for [arg %]
                         (match arg
                           [:var x] [:var (try-cache x)]
                           arg arg))]
    (for [line script
          :when (not (can-be-removed? line created cache tree))]
      (match line
        [:get result-var var attr]
        [:get result-var (try-cache var) attr]

        [:call result-var var method args]
        [:call result-var (try-cache var) method (try-cache-args args)]

        [:new result-var cls args]
        [:new result-var cls (try-cache-args args)]

        line line))))

(defn ids-from-args
  [args]
  (for [[type value] args
        :when (= :var type)]
    value))

(defn get-used-ids
  [script]
  (set (flatten (for [line script]
                  (match line
                    [:get result-var var & _] [result-var var]
                    [:set var & _] var
                    [:new result-var _ args] [result-var (ids-from-args args)]
                    [:call result-var var _ args] [result-var var (ids-from-args args)])))))

(defn get-non-used-ids
  [used? cache]
  (->> cache
       vals
       (remove used?)
       set))

(defn clean-cache
  [used? cache]
  (into {} (for [[k v] cache
                 :when (used? v)]
             [k v])))

(defn get-created
  [cache new-cache]
  (set (for [[k v] new-cache
             :when (not (get cache k))]
         v)))

(defn add-gc-stage
  [script non-used-ids]
  (concat script
          (mapv #(vector :free %) non-used-ids)))

(defn reuse
  [cache script root-id]
  (let [tree (build-tree script)
        new-cache (get-new-cache tree (ordered-vars script))
        created (get-created cache new-cache)
        cache (merge new-cache cache)
        root-id (get cache (get tree root-id) root-id)
        script (replace-with-cached script created cache tree)
        used-ids (get-used-ids script)
        non-used-ids (get-non-used-ids used-ids cache)
        script (add-gc-stage script non-used-ids)
        cache (clean-cache used-ids cache)]
    (pprint script)
    [cache script root-id]))
