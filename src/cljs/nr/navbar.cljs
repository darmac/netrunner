(ns nr.navbar
  (:require [nr.appstate :refer [app-state]]
            [nr.history :refer [history]]
            [reagent.core :as r]))

(def navbar-links
  [["聊天" "/" 0 nil]
   ["卡牌" "/cards" 1 nil]
   ["卡组构建" "/deckbuilder" 2 nil]
   ["游戏" "/play" 3 nil]
   ["帮助" "/help" 4 nil]
   ["设置" "/account" 5 #(:user %)]
   ["状态" "/stats" 6 #(:user %)]
   ["关于" "/about" 7 nil]
   ["Tournaments" "/tournament" 8 #(:tournament-organizer (:user %))]
   ["管理员" "/admin" 9 #(:isadmin (:user %))]
   ["用户" "/users" 10 #(:isadmin (:user %))]
   ["特性" "/features" 11 #(:isadmin (:user %))]])

(defn navbar []
  (r/with-let [active (r/cursor app-state [:active-page])]
    [:ul.carousel-indicator
     (doall
       (for [[name route ndx show-fn?] navbar-links]
         (when (or (not show-fn?)
                   (show-fn? @app-state))
           [:li {:class (if (= (first @active) route) "active" "")
                 :id (str (clojure.string/lower-case name) "-nav")
                 :key name
                 :on-click #(.setToken history route)
                 :data-target "#main"
                 :data-slide-to ndx}
            [:a {:href route} name]])))]))
