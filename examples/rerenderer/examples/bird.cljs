(ns rerenderer.examples.bird)
;  (:require-macros [cljs.core.async.macros :refer [go-loop go]])
;  (:require [cljs.core.async :refer [<! chan timeout]]
;            [cljs.core.match :refer-macros [match]]
;            [rerenderer.core :as r :include-macros true]
;            [rerenderer.browser :refer [browser]]))
;
;; Consts:
;
;(def bird-start-x 50)
;(def bariers-start 200)
;(def bariers-screens 2)
;(def barier-min-w 30)
;(def barier-max-w 60)
;(def barier-min-h 50)
;(def barier-max-h 350)
;(def barier-min-offset 50)
;(def barier-max-offset 250)
;(def tick 10)
;(def jump-high 200)
;(def fly-down-high 5)
;
;; Drawing:
;
;(defn draw-background
;  [ctx atlas w h bird-x]
;  (let [dw (* (/ h 512) 286)
;        offset (- 0 (mod (/ bird-x 2) dw))]
;    (doseq [x (range offset w dw)]
;      (r/call! ctx (drawImage atlas 0 0 286 512 x 0 dw h)))))
;
;(defn is-visible?
;  [view-start view-end {:keys [x w]}]
;  (and (<= view-start (+ x w))
;       (>= view-end x)))
;
;(defn get-barier-sprite-x
;  [pos color]
;  (match [pos color]
;    [:down :red] 56
;    [:up :red] 0
;    [:down :green] 168
;    [:up :green] 112))
;
;(defn draw-barier
;  [ctx atlas view-start view-h {:keys [x pos w h color]}]
;  (r/set! (.. ctx -fillStyle) color)
;  (let [sx (get-barier-sprite-x pos color)
;        dx (- x view-start)
;        dy (if (= pos :up) 0 (- view-h h))]
;    (r/call! ctx (drawImage atlas sx 645 52 320 dx dy w h))))
;
;(defn draw-terrain
;  [ctx atlas terrain view-start view-end view-h]
;  (doseq [barier terrain
;          :when (is-visible? view-start view-end barier)]
;    (draw-barier ctx atlas view-start view-h barier)))
;
;(defn draw-bird
;  [ctx atlas y]
;  (r/call! ctx (drawImage atlas 4 980 36 36
;                       (- bird-start-x 18) (- y 18) 36 36)))
;
;(defn draw-score
;  [ctx score w]
;  (r/set! (.. ctx -fillStyle) "red")
;  (r/set! (.. ctx -font) "32px monospace")
;  (let [text (str "SCORE: " score)
;        offset (* 20 (count text))]
;    (r/call! ctx (fillText text (- w offset) 30))))
;
;(defn draw-click-to-start
;  [ctx w h]
;  (r/set! (.. ctx -font) "48px mononspace")
;  (r/set! (.. ctx -fillStyle) "rgba(0,0,0,0.5)")
;  (r/call! ctx (fillRect 150 200 500 120))
;  (r/set! (.. ctx -fillStyle) "white")
;  (r/call! ctx (fillText "CLICK TO START!"
;                         (- (/ w 2) 200)
;                         (+ (/ h 2) 20))))
;
;(defn flappy-bird-root
;  [ctx {:keys [terrain bird-y bird-x score started]} {:keys [atlas w h]}]
;  (r/call! ctx (clearRect 0 0 w h))
;  (draw-background ctx atlas w h bird-x)
;  (let [view-start (- bird-x bird-start-x)]
;    (draw-terrain ctx atlas terrain view-start (+ w view-start) h))
;  (draw-bird ctx atlas (- h bird-y))
;  (draw-score ctx score w)
;  (when-not started
;    (draw-click-to-start ctx w h)))
;
;; Work with state:
;
;(defn rand-from-range
;  [from to]
;  (+ from (rand-int (- to from))))
;
;(defn generate-terrain
;  [from to]
;  (loop [x from
;         result []]
;    (if (< x to)
;      (let [pos (rand-nth [:up :down])
;            color (rand-nth [:red :green])
;            w (rand-from-range barier-min-w barier-max-w)
;            h (rand-from-range barier-min-h barier-max-h)
;            next (+ x w (rand-from-range barier-min-offset
;                                         barier-max-offset))]
;        (recur next (conj result {:w w
;                                  :h h
;                                  :color color
;                                  :pos pos
;                                  :x x})))
;      result)))
;
;(defn reset-state!
;  [state {:keys [w]}]
;  (let [{:keys [initial]} @state]
;    (reset! state initial)
;    (swap! state assoc
;           :initial initial
;           :terrain (generate-terrain bariers-start (* w bariers-screens)))))
;
;(defn jump
;  [platform state {:keys [h sounds]}]
;  (swap! state update-in [:bird-y]
;         #(if (> h %) (+ jump-high %) %))
;  (r/play! platform (:jump sounds)))
;
;(defn handle-clicks!
;  [platform state options]
;  (let [clicks (chan)]
;    (r/listen! platform "click" clicks)
;    (r/listen! platform "keydown" clicks)
;    (go-loop []
;      (<! clicks)
;      (cond
;        (:finished @state) (reset-state! state options)
;        (not (:started @state)) (swap! state assoc :started true)
;        :else (jump platform state options))
;      (recur))))
;
;(defn renew-terrain
;  [terrain w bird-x]
;  (if (zero? (mod bird-x w))
;    (->> terrain
;         (remove #(< (- (:x %) bird-x) (- 0 bird-start-x)))
;         (concat (generate-terrain (+ w bird-x)
;                                   (+ w w bird-x))))
;    terrain))
;
;(defn has-cord?
;  [cx cy scene-h {:keys [x w h pos]}]
;  (let [y (if (= :up pos) (- scene-h h) 0)]
;    (and (<= x cx (+ x w))
;         (<= y cy (+ y h)))))
;
;(defn is-lose?
;  [{:keys [bird-y bird-x terrain]} h]
;  (cond
;    (<= bird-y 0) true
;    (some #(has-cord? bird-x bird-y h %) terrain) true
;    :else false))
;
;(defn fly-down
;  [bird-y]
;  (if (pos? bird-y)
;    (- bird-y fly-down-high)
;    bird-y))
;
;(defn update-state-on-timer
;  [state {:keys [w h]}]
;  (-> state
;      (update-in [:bird-y] fly-down)
;      (update-in [:bird-x] inc)
;      (update-in [:terrain] renew-terrain w (:bird-x state))
;      (update-in [:score] inc)
;      (assoc :finished (is-lose? state h))))
;
;(defn handle-timer!
;  [platform state options]
;  (go-loop []
;    (<! (timeout tick))
;    (when-not (or (:finished @state) (not (:started @state)))
;      (swap! state update-state-on-timer options)
;      (when (:finished @state)
;        (r/play! platform (get-in options [:sounds :die]))))
;    (recur)))
;
;; Init:
;
;(defn init!
;  [canvas]
;  (go (let [platform (browser canvas)
;            w (.-width canvas)
;            state (atom {:terrain (generate-terrain bariers-start (* w bariers-screens))
;                         :bird-y (/ w 2)
;                         :bird-x bird-start-x
;                         :score 0
;                         :finished false
;                         :started false})
;            options {:sounds {:die (<! (r/sound platform "assets/bird_die.ogg"))
;                              :jump (<! (r/sound platform "assets/bird_jump.ogg"))}
;                     :atlas (<! (r/image platform "/assets/bird.png"))
;                     :w w
;                     :h (.-height canvas)}]
;        (swap! state assoc :initial (assoc @state :started true))
;        (r/init! platform flappy-bird-root state options)
;        (handle-clicks! platform state options)
;        (handle-timer! platform state options))))