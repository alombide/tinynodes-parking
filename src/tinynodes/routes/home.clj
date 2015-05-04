(ns tinynodes.routes.home
  (:require [tinynodes.layout :as layout]
            [compojure.core :refer :all]
            [liberator.core :refer [resource defresource]]
            [clojure.java.io :as io]
            [clojure.tools.reader.edn :as edn]
            [clj-time.coerce :as timecoerce]
            [tinynodes.util.hiccuptable :as hiccuptable]
            [hiccup.core :as hiccup]
            [hiccup.element :as hiccupelement]
            [hiccup.page :as hiccuppage]
            [cheshire.core :as json]
            [cheshire.generate :refer [add-encoder encode-str remove-encoder]]))


; Add json encoder for joda dates
(add-encoder org.joda.time.DateTime
                  (fn [c jsonGenerator]
                    (.writeString jsonGenerator (str c))))

(def get-page-body-url "http://tinynodes.herokuapp.com/body")

; Keeps associations of the following layout:
; { :nodeID nodeID {:nodeID nodeID :occupied true/false :timestamp timestamp} }
; Allows for fast lookup based on node ID, but when getting the vals of the map also a simple list of nodes.
(def node-occupancy-map (ref {}))

; Set the occupancy of a node in the occupancy map.
; @param node-data: map version of JSON input (see tinynodes-routes)
(defn set-node-occupancy! [node-data]
  (ref-set
   node-occupancy-map
    (assoc (deref node-occupancy-map) (edn/read-string (:nodeID node-data))
      {:nodeID     (edn/read-string (:nodeID node-data))
       :occupied   (= (edn/read-string (:occ node-data)) 1)
       :locationID (edn/read-string (:locationID node-data))
       :timestamp  (timecoerce/from-long (edn/read-string (str (:nodeTS node-data) "000"))) })))

(defn clear-data! []
  (ref-set node-occupancy-map {})
  node-occupancy-map)


(def free-image "/img/parking_free.png")
(def occupied-image "/img/parking_occupied.png")

; Create HTML div showing node ID and free/occupied image.
; id of the div in the image is the node ID.
(defn show-occupancy-image [node]
  (hiccup/html
   [:div
    [:h2 (:locationID node) " -- " (:nodeID node)]
    (hiccupelement/image
     {:id (str (:nodeID node))}
     (if (:occupied node) occupied-image free-image))]))


; Use Pollymer lib in client javascript for long polling the server.
(def include-pollymer-tag (hiccup/html (hiccuppage/include-js "/js/pollymer.js")))

; Some javascript to include in the returned HTML to long poll the server.
; The polled endpoint returns the HTML body containing the free/occupied overview of the TinyNodes.
(def node-state-long-polling-javascript
  (hiccup/html (hiccupelement/javascript-tag
   (str
    "var req = new Pollymer.Request();
     req.on('finished', function(code, result, headers) {
       if (code == 200) {
         document.body.innerHTML = result;
      }
    });
    req.maxTries = 2; // try twice
    req.recurring = true;
    req.start('GET', '" get-page-body-url "');"))))


; Returns HTML table with the contents of the node occupancy map.
(defn print-nodes-as-table []
 (hiccup/html
  (hiccuptable/to-table1d
    ;(map
    ; (fn [node]
    ;   [:nodeID (:nodeID node)
    ;    :occupied (show-occupancy-image (:occupied node))
    ;    :timestamp (:timestamp node)])
     (vals (deref node-occupancy-map))
    [:nodeID "Node ID" :occupied "Occupied?" :timestamp "Timestamp"])))


; Return an HTML body visually showing the occupancy of the nodes.
(defn print-nodes-as-images []
  (hiccup/html [:body
   (map
    (fn [node] (show-occupancy-image node))
    (vals (deref node-occupancy-map)))]))

; Returns a JSON structure with the contents of the node occupancy map.
(defn node-map-to-json []
   (json/generate-string (deref node-occupancy-map)))


; Homepage: show the node occupancy visually (HTML).
(defroutes home-routes
  (GET "/" []
        (str
             include-pollymer-tag                ; src tag to include pollymer.js
             node-state-long-polling-javascript  ; script tag to include node state long polling js
             "<h1>TinyNodes:</h1>"
             ;(print-nodes-as-table)
             (print-nodes-as-images)))           ; actual HTML body showing node state visually
  ; End point for long polling the body showing the occupancy of the nodes visually.
  (GET "/body" []
        (str
             "<h1>TinyNodes:</h1>"
             (print-nodes-as-images))))


; REST API to interpret TinyNode Gateway sensor data and returning the interpreted data.
(defroutes tinynodes-routes
; /add-data
; Adds sent params as node occupancies.
; @params:
; ?table=DATA
; &nodeID=1894
; &locationID=1
; &nodeTS=1331444035
; &serverTS=1331444061
; &amrX=-172
; &amrY=584
; &amrZ=-614
; &centerX=-169
; &centerY=599
; &centerZ=-586
; &occ=0
; &temp=16
  (GET "/add-data" {params :params}
       (let [inputType (:table params)]
         (if (= inputType "DATA")
           (dosync
            (set-node-occupancy! params)   ; Update node occupancy with received data from gateway
            (node-map-to-json)))))         ; Return updated node map as json.
  ; /data get node data
  (GET "/data" []
       (node-map-to-json))                 ; Return node map as json.
  (GET "/clear" []
       (dosync
         (clear-data!)
         (node-map-to-json))))
