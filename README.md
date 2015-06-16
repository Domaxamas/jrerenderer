# rerenderer

Simple platform agnostic react-like library for drawing on canvas,
handling events and playing sounds.

Supported platforms:

- Browser (full support);
- Android (only primitive drawing)

## How it works?

When state (atom) changes `rerenderer` calls a rendering function,
inside the function we work with shadow canvas (like shadow dom in React).
And applies changes to real canvas only when shadow canvas has difference
with shadow canvas of the previous call of the rendering function.
 
And as a real canvas we can use browser canvas, android canvas
(not fully implemented) or even iOS canvas (not implemented).

## Usage in browser

Renders rectangle that changes colors on click:

```clojure
(ns rerenderer.examples.simple
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [chan <!]]
            [rerenderer.core :as r :include-macros true]
            [rerenderer.browser :refer [browser]]))

(defn root
  [ctx {:keys [color]} {:keys [colors]}]
  (r/set! (.. ctx -fillStyle) (get colors color))
  (r/call! ctx (fillRect 50 50 100 100)))

(defn handle-clicks!
  [platform state {:keys [colors]}]
  (let [clicks (chan)]
    (r/listen! platform "click" clicks)
    (go-loop []
      (<! clicks)
      (swap! state update-in [:color]
             #(-> % inc (mod (count colors))))
      (recur))))

(defn init!
  [canvas]
  (let [platform (browser canvas)
        state (atom {:color 0})
        options {:colors ["red" "green" "blue"]}]
    (r/init! platform root state options)
    (handle-clicks! platform state options)))
    

```

## TODO: Usage on android
