(ns ants.core)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Ant sim ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Common Public License 1.0 (http://opensource.org/licenses/cpl.php)
;   which can be found in the file CPL.TXT at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

;dimensions of square world
(def dim 80)
;number of ants = nants-sqrt^2
(def nants-sqrt 7)
;number of places with food
(def food-places 35)
;range of amount of food at a place
(def food-range 100)
;scale factor for pheromone drawing
(def pher-scale 20.0)
;scale factor for food drawing
(def food-scale 30.0)
;evaporation rate
(def evap-rate 0.99)

(def ant-sleep-ms 40)
(def evap-sleep-ms 1000)

(def running true)

(defstruct cell :food :pher) ;may also have :ant and :home

;world is a 2d vector of refs to cells
(def world 
  (mapv
    (fn [_] 
      (mapv
        (fn [_]
          (ref (struct cell 0 0))) 
        (range dim)))
    (range dim)))

(defn place [[x y]]
  (-> world (nth x) (nth y)))

(defn places []
  (dosync
    (vec
      (for [x (range dim)
            y (range dim)]
        @(place [x y])))))

(defstruct ant :dir) ;may also have :food

(defn create-ant 
  "create an ant at the location, returning an ant agent on the location"
  [loc dir]
  (sync nil
    (let [p (place loc)
          a (struct ant dir)]
      (alter p assoc :ant a)
      (agent loc))))

(def home-off (/ dim 4))
(def home-range (range home-off (+ nants-sqrt home-off)))

(defn setup 
  "places initial food and ants, returns seq of ant agents"
  []
  (sync nil
    (dotimes [i food-places]
      (let [p (place [(rand-int dim) (rand-int dim)])]
        (alter p assoc :food (rand-int food-range))))
    (doall
      (for [x home-range y home-range]
        (do
          (alter (place [x y]) 
            assoc :home true)
          (create-ant [x y] (rand-int 8)))))))

(defn bound 
  "returns n wrapped into range 0-b"
  [b n]
  (let [n (rem n b)]
    (if (neg? n) 
      (+ n b) 
      n)))

(defn wrand 
  "given a vector of slice sizes, returns the index of a slice given a
  random spin of a roulette wheel with compartments proportional to
  slices."
  [slices]
  (let [total (reduce + slices)
        r (rand total)]
    (loop [i 0 sum 0]
      (if (< r (+ (slices i) sum))
        i
        (recur (inc i) (+ (slices i) sum))))))

;dirs are 0-7, starting at north and going clockwise
;these are the deltas in order to move one step in given dir
(def dir-delta {0 [0 -1]
                1 [1 -1]
                2 [1 0]
                3 [1 1]
                4 [0 1]
                5 [-1 1]
                6 [-1 0]
                7 [-1 -1]})

(defn delta-loc 
  "returns the location one step in the given dir. Note the world is a torus"
  [[x y] dir]
  (let [[dx dy] (dir-delta (bound 8 dir))]
    [(bound dim (+ x dx)) (bound dim (+ y dy))]))

;(defmacro dosync [& body]
;  `(sync nil ~@body))

;ant agent functions
;an ant agent tracks the location of an ant, and controls the behavior of 
;the ant at that location

(defn turn 
  "turns the ant at the location by the given amount"
  [loc amt]
  (dosync
    (let [p (place loc)
          ant (:ant @p)]
      (alter p assoc :ant (assoc ant :dir (bound 8 (+ (:dir ant) amt))))))
  loc)

(defn move 
  "moves the ant in the direction it is heading. Must be called in a
  transaction that has verified the way is clear"
  [loc]
  (let [oldp (place loc)
        ant (:ant @oldp)
        newloc (delta-loc loc (:dir ant))
        p (place newloc)]
    ;move the ant
    (alter p assoc :ant ant)
    (alter oldp dissoc :ant)
    ;leave pheromone trail
    (when-not (:home @oldp)
      (alter oldp assoc :pher (inc (:pher @oldp))))
    newloc))

(defn take-food [loc]
  "Takes one food from current location. Must be called in a
  transaction that has verified there is food available"
  (let [p (place loc)
        ant (:ant @p)]    
    (alter p assoc 
      :food (dec (:food @p))
      :ant (assoc ant :food true))
    loc))

(defn drop-food [loc]
  "Drops food at current location. Must be called in a
  transaction that has verified the ant has food"
  (let [p (place loc)
        ant (:ant @p)]    
    (alter p assoc 
      :food (inc (:food @p))
      :ant (dissoc ant :food))
    loc))

(defn rank-by 
  "returns a map of xs to their 1-based rank when sorted by keyfn"
  [keyfn xs]
  (let [sorted (sort-by (comp float keyfn) xs)]
    (reduce (fn [ret i] (assoc ret (nth sorted i) (inc i)))
      {} (range (count sorted)))))

(defn behave 
  "the main function for the ant agent"
  [loc]
  (let [p (place loc)
        ant (:ant @p)
        ahead (place (delta-loc loc (:dir ant)))
        ahead-left (place (delta-loc loc (dec (:dir ant))))
        ahead-right (place (delta-loc loc (inc (:dir ant))))
        places [ahead ahead-left ahead-right]]
    (. Thread (sleep ant-sleep-ms))
    (dosync
      (when running
        (send-off *agent* #'behave))
      (if (:food ant)
        ;going home
        (cond 
          (:home @p)                              
          (-> loc drop-food (turn 4))
         
          (and (:home @ahead) (not (:ant @ahead))) 
          (move loc)
         
          :else
          (let [ranks (merge-with + 
                        (rank-by (comp #(if (:home %) 1 0) deref) places)
                        (rank-by (comp :pher deref) places))]
            (([move #(turn % -1) #(turn % 1)]
              (wrand [(if (:ant @ahead) 0 (ranks ahead)) 
                      (ranks ahead-left) (ranks ahead-right)]))
             loc)))
        ;foraging
        (cond 
          (and (pos? (:food @p)) (not (:home @p))) 
          (-> loc take-food (turn 4))
         
          (and (pos? (:food @ahead)) (not (:home @ahead)) (not (:ant @ahead)))
          (move loc)
         
          :else
          (let [ranks (merge-with + 
                        (rank-by (comp :food deref) places)
                        (rank-by (comp :pher deref) places))]
            (([move #(turn % -1) #(turn % 1)]
              (wrand [(if (:ant @ahead) 0 (ranks ahead)) 
                      (ranks ahead-left) (ranks ahead-right)]))
             loc)))))))

(defn evaporate 
  "causes all the pheromones to evaporate a bit"
  []
  (dorun 
    (for [x (range dim) y (range dim)]
      (dosync 
        (let [p (place [x y])]
          (alter p assoc :pher (* evap-rate (:pher @p))))))))

(def evaporator (agent nil))

(defn evaporation [x]
  (when running
    (send-off *agent* #'evaporation))
  (evaporate)
  (. Thread (sleep evap-sleep-ms))
  nil)
