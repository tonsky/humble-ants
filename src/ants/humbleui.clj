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
    [io.github.humbleui.skija Canvas Color PaintMode Path]))

(defonce *window
  (atom nil))

(defn fill-cell [canvas x y paint opacity] 
  (.setAlpha paint opacity)
  (canvas/draw-rect canvas (core/rect-xywh x y 1 1) paint))

(def path-ant
  (doto (Path.)
    (.moveTo  0   -0.5)
    (.lineTo  0.4  0.5)
    (.lineTo -0.4  0.5)
    (.closePath)))

(def paint-ant
  (paint/stroke 0xFF000000 0.1))
  
(def paint-ant-full
  (doto (paint/stroke 0xFF000000 0.1)
    (.setMode PaintMode/STROKE_AND_FILL)))
    
(defn render-ant [^Canvas canvas ant x y]
  (canvas/with-canvas canvas
    (canvas/translate canvas (+ x 0.5) (+ y 0.5))
    (canvas/rotate canvas (-> (:dir ant) (/ 8) (* 360)))
    (.drawPath canvas path-ant (if (:food ant) paint-ant-full paint-ant))))

(def paint-food
  (paint/fill 0xFF33CC33))

(def paint-pher
  (paint/fill 0xFFFFCC33))

(defn render-place [canvas place x y]
  (let [{:keys [pher food ant]} place]
    (when (pos? pher)
      (fill-cell canvas x y paint-pher (-> pher (/ pher-scale) (* 255) (min 255) (int))))
                            
    (when (pos? food)
      (fill-cell canvas x y paint-food (-> food (/ food-scale) (* 255) (min 255) (int))))

    (when ant
      (render-ant canvas ant x y))))

(def paint-home
  (paint/stroke 0xFFFFCC33 0.2))

(defn paint [ctx canvas size]
  (let [field (min (:width size) (:height size))
        scale (/ field dim)]
    ; center canvas
    (canvas/translate canvas
      (-> (:width size) (- field) (/ 2))
      (-> (:height size) (- field) (/ 2)))
    
    ; scale to fit full width/height but keep square aspect ratio
    (canvas/scale canvas scale scale)
      
    ; erase background
    (with-open [bg (paint/fill 0xFFFFFFFF)]
      (canvas/draw-rect canvas (core/rect-xywh 0 0 dim dim) bg))
    
    ; places
    (let [v (places)]
      (doseq [x (range dim)
              y (range dim)]
        (render-place canvas (v (+ (* x dim) y)) x y)))
    
    ; home
    (canvas/draw-rect canvas
      (core/rect-xywh home-off home-off nants-sqrt nants-sqrt)
      paint-home)
  
    ;; schedule redraw on next vsync
    (window/request-frame (:window ctx))))

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
  (apply nrepl/-main args))
