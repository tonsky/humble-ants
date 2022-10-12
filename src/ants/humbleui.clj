(ns ants.humbleui
  (:require
    [ants.core :refer :all]
    [io.github.humbleui.canvas :as canvas]
    [io.github.humbleui.core :as core]
    [io.github.humbleui.paint :as paint]
    [io.github.humbleui.ui :as ui]
    [io.github.humbleui.window :as window]
    [nrepl.cmdline :as nrepl])
  (:import
    [io.github.humbleui.skija Color]))

(def ^:dynamic *scale*)

(defonce *window
  (atom nil))

(defn redraw []
  (some-> *window deref window/request-frame))

(defn fill-cell [canvas x y color]
  (with-open [fill (paint/fill color)]
    (canvas/draw-rect canvas (core/irect-xywh (* x *scale*) (* y *scale*) *scale* *scale*) fill)))

(defn render-ant [canvas ant x y]
  (let [half (/ 2 5)
        full (/ 4 5)
        [hx hy tx ty] ({0 [half 0    half full] 
                        1 [full 0    0    full] 
                        2 [full half 0    half] 
                        3 [full full 0    0] 
                        4 [half full half 0] 
                        5 [0    full full 0] 
                        6 [0    half full half] 
                        7 [0    0    full full]}
                       (:dir ant))
        color (if (:food ant)
                0xFFFF0000
                0xFF000000)]
    (with-open [stroke (paint/stroke color 1)]
      (canvas/draw-line canvas
        (* *scale* (+ hx x))
        (* *scale* (+ hy y))
        (* *scale* (+ tx x))
        (* *scale* (+ ty y))
        stroke))))

(defn render-place [canvas p x y]
  (when (pos? (:pher p))
    (fill-cell canvas x y
      (Color/makeARGB (int (min 255 (* 255 (/ (:pher p) pher-scale)))) 0 255 0)))
                          
  (when (pos? (:food p))
    (fill-cell canvas x y (Color/makeARGB (int (min 255 (* 255 (/ (:food p) food-scale)))) 255 0 0)))

  (when (:ant p)
    (render-ant canvas (:ant p) x y)))

(defn render-home [canvas]
  (with-open [stroke (paint/stroke 0xFF0000FF 1)]
    (canvas/draw-rect canvas
      (core/irect-xywh
        (* *scale* home-off)
        (* *scale* home-off)
        (* *scale* nants-sqrt)
        (* *scale* nants-sqrt))
      stroke)))

(defn paint [ctx canvas size]
  (let [field (min (:width size) (:height size))]
    (canvas/translate canvas
      (-> (:width size) (- field) (/ 2))
      (-> (:height size) (- field) (/ 2)))
    
    (binding [*scale* (/ field dim)]
      
      (with-open [bg (paint/fill 0xFFFFFFFF)]
        (canvas/draw-rect canvas (core/irect-xywh 0 0 (* *scale* dim) (* *scale* dim)) bg))
      
      (let [v (dosync
                (vec
                  (for [x (range dim)
                        y (range dim)]
                    @(place [x y]))))]
        (doseq [x (range dim)
                y (range dim)]
          (render-place canvas (v (+ (* x dim) y)) x y)))
      
      (render-home canvas)))
  
  ;; schedule redraw on next vsync
  (redraw))

(def app
  (ui/default-theme
    (ui/center
      (ui/canvas
        {:on-paint paint}))))

(defn -main [& args]  
  (ui/start-app!
    (reset! *window
      (ui/window
       {:title "🐜🐜🐜"}
       #'app)))
  (def ants (setup))
  (dorun (map #(send-off % behave) ants))
  (send-off evaporator evaporation)
  (redraw)
  (apply nrepl/-main args))
