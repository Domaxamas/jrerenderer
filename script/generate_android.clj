(ns generate-android
  (:require [clojure.string :refer [split trim join] :as string]
            [clojure.java.io :refer [file writer]]
            [net.cgrand.enlive-html :as html]
            [clj-http.client :as http])
  (:import (java.io StringReader)))

(def types {"int" "Int"
            "float" "Float"
            "long" "Long"
            "double" "Double"
            "char" "Char"
            "boolean" "Boolean"
            "short" "Short"
            "int[]" "IntArray"
            "float[]" "FloatArray"
            "long[]" "LongArray"
            "double[]" "DoubleArray"
            "char[]" "CharArray"
            "short[]" "ShortArray"
            "Object" "Any"})

(def numeric ["Byte" "Short" "Int" "Long" "Float" "Double"])

(defn get-links
  [content]
  (as-> content $
        (html/select $ [:div.jd-descr])
        (second $)
        (html/select $ [:a])
        (html/select $ [(html/but (html/attr-contains :href "#"))])
        (html/select $ [(html/attr-starts :href "/reference/android/graphics/")])
        (map #(get-in % [:attrs :href]) $)
        (set $)))

(defn make-url
  [url]
  (str "https://developer.android.com" url))

(defn fetch
  [url]
  (let [full-url (make-url url)
        {:keys [body]} (http/get full-url)
        content (html/html-resource (StringReader. body))]
    [content (get-links content)]))

(defn fetch-all
  [start-url]
  (loop [urls [start-url]
         contents {}]
    (let [[url & urls] urls]
      (if url
        (let [[content new-urls] (fetch url)
              contents (assoc contents url content)
              urls (->> (concat new-urls urls)
                        (remove contents)
                        set)]
          (recur (vec urls) contents))
        (vals contents)))))

(defn get-api-trs
  [content id]
  (as-> content $
        (html/select $ [id])
        (first $)
        (html/select $ [:tr.api])
        (remove #(re-find #"apilevel-23" (get-in % [:attrs :class])) $)))

(defn prepare-type
  [type]
  (let [type (last (split type #" "))]
    (get types type type)))

(defn get-constants
  [content]
  (for [tr (get-api-trs content :#constants)
        :let [[type-td name-td & _] (html/select tr [:td])]]
    {:type (-> type-td :content first prepare-type)
     :name (-> name-td :content first :content first)}))

(defn parse-args
  [nobr]
  (let [args-els (drop-while #(not (and (string? %) (.startsWith % "(")))
                             (:content nobr))
        args-str (reduce #(str %1 (if (string? %2)
                                    %2
                                    (-> %2 :content first)))
                         args-els)
        trimmed (subs args-str 1 (dec (count args-str)))]
    (if (pos? (count trimmed))
      (->> (split trimmed #", ")
           (map #(split % #" "))
           (map (fn [[type name]] {:name name
                                   :type (prepare-type type)})))
      [])))

(defn parse-name
  [td]
  (let [[name-a] (html/select td [:span.sympad :a])]
    (-> name-a :content first)))

(defn get-constructors
  [content]
  (for [tr (get-api-trs content :#pubctors)
        :let [[nobr] (html/select tr [:td.jd-linkcol :nobr])]]
    {:name (parse-name nobr)
     :args (parse-args nobr)}))

(defn get-methods
  [content]
  (for [tr (get-api-trs content :#pubmethods)
        :let [[type-td descr-td] (html/select tr [:td])
              [nobr] (html/select descr-td [:nobr])
              type (-> type-td :content first :content first trim)]]
    {:name (parse-name nobr)
     :args (parse-args nobr)
     :type (prepare-type type)
     :static? (re-find #"static" type)}))

(defn get-name
  [content]
  (-> (html/select content [:h1])
      first
      :content
      first))

(defn get-imports
  [content]
  (as-> content $
        (html/select $ [:div#jd-content])
        (first $)
        (html/select $ [:a])
        (html/select $ [(html/but (html/attr-contains :href "#"))])
        (html/select $ [(html/attr-starts :href "/reference/")])
        (map #(get-in % [:attrs :href]) $)
        (filter #(not (re-find #"/(\w+)\.(\w+)\.html" %)) $)
        (map #(string/replace % "/reference/" "") $)
        (map #(string/replace % ".html" "") $)
        (map #(string/replace % "/" ".") $)
        (remove #(re-find #"java\.lang" %) $)))

(defn parse-class
  [content]
  (let [name (get-name content)
        imports (if (re-find #"\." name)
                  (get-imports content)
                  (conj (get-imports content)
                        (format "android.graphics.%s" name)))]
    {:constants (get-constants content)
     :constructors (get-constructors content)
     :methods (get-methods content)
     :imports imports
     :name name
     :interop-name (string/replace name "." "\\$")}))

(defn parse-all
  [start-url]
  (->> (fetch-all start-url)
       (map parse-class)))

(defn make-imports
  [parsed]
  (->> parsed
       (map :imports)
       flatten
       set
       (map #(format "import %s" %))))

(defn kt-arg
  [n {:keys [type]}]
  (if (some #{type} numeric)
    (format "anyTo%s(args[%d])" type n)
    (format "args[%d] as %s" n type)))

(defn kt-arg-checker
  [n {:keys [type]}]
  (when-not (some #{type} numeric)
    (format "args[%d] is %s" n type)))

(defn kt-args-checkers
  [args]
  (let [checker (->> args
                     (map-indexed kt-arg-checker)
                     (remove nil?))]
    (if (seq checker)
      (str " && " (join " && " checker))
      "")))

(defn make-methods
  [{:keys [name interop-name methods]}]
  (for [{:keys [args static?] :as method} methods
        :let [kt-args (map-indexed kt-arg args)
              kt-args-check (kt-args-checkers args)
              method (:name method)]]
    (if static?
      (format "(objVar == \"%s\" && method == \"%s\" && args.count() == %d %s) -> %s.%s(%s)"
              interop-name method (count args) kt-args-check
              name method (join ", " kt-args))
      (format "(objVar is %s && method == \"%s\" && args.count() == %d %s) -> objVar.%s(%s)"
              name method (count args) kt-args-check
              method (join ", " kt-args)))))

(defn make-constans
  [{:keys [constants name interop-name]}]
  (for [const constants
        :let [const (:name const)]]
    (format "(objVar == \"%s\" && attr == \"%s\") -> %s.%s"
            interop-name const name const)))

(defn get-numbers-convertor
  [to]
  (for [from numeric
        :when (not= from to)]
    (format "is %s -> x.to%s()" from to)))

(defn get-numbers-convertors
  []
  (for [to numeric]
    (format "fun anyTo%s(x: Any?): %s = when (x) {
    %s
    else -> x as %s
}" to to (join "\n    " (get-numbers-convertor to)) to)))

; Constructors rendering:

(defn make-constructors
  [constructors]
  (if (> (count constructors) 1)
    (let [match-leafs (for [{:keys [name args]} constructors
                            :let [kt-args (map-indexed kt-arg args)]]
                        (format "(true%s) -> %s(%s)"
                                (kt-args-checkers args) name (join ", " kt-args)))
          {:keys [name]} (first constructors)]
      (format "when {
      %s
      else -> throw Exception(\"Can't create %s wtih $args\")
      }" (join "\n    " match-leafs) name))
    (let [{:keys [name args]} (first constructors)
          kt-args (map-indexed kt-arg args)]
      (format "%s(%s)" name (join ", " kt-args)))))

(defn make-contructors-map
  [classes]
  (let [constructors (flatten (for [{:keys [interop-name constructors]} classes
                                    constructor constructors]
                                (assoc constructor :interop-name interop-name)))
        grouped (group-by (fn [{:keys [interop-name args]}]
                            [interop-name (count args)])
                          constructors)]
    (for [[[interop-name args-count] constructors] grouped]
      (format "NewGroup(\"%s\", %d) to {args: List<Any?> ->  %s
      }" interop-name args-count (make-constructors constructors)))))

; Methods rendering:

(defn make-methods
  [methods]
  (if (> (count methods) 1)
    (let [match-leafs (for [{:keys [name args type]} methods
                            :let [kt-args (map-indexed kt-arg args)]]
                        (format "(true%s) -> (objVar as %s).%s(%s)"
                                (kt-args-checkers args)
                                type name
                                (join ", " kt-args)))
          {:keys [name type]} (first methods)]
      (format "when {
      %s
      else -> throw Exception(\"Can't call %s.%s wtih $args\")
      }" (join "\n    " match-leafs) type name))
    (let [{:keys [name args type]} (first methods)
          kt-args (map-indexed kt-arg args)]
      (format "(objVar as %s).%s(%s)" type name (join ", " kt-args)))))

(defn make-static-methods
  [methods]
  (if (> (count methods) 1)
    (let [match-leafs (for [{:keys [name args type interop-name]} methods
                            :let [kt-args (map-indexed kt-arg args)]]
                        (format "((objVar as String) == \"%s\"%s) -> %s.%s(%s)"
                                interop-name
                                (kt-args-checkers args)
                                type name
                                (join ", " kt-args)))
          {:keys [name type]} (first methods)]
      (format "when {
      %s
      else -> throw Exception(\"Can't call %s.%s wtih $args\")
      }" (join "\n    " match-leafs) type name))
    (let [{:keys [type name args]} (first methods)
          kt-args (map-indexed kt-arg args)]
      (format "%s.%s(%s)" type name (join ", " kt-args)))))

(defn make-methods-map
  [classes]
  (let [methods (flatten (for [{:keys [interop-name name methods]} classes
                               method methods]
                           (assoc method :interop-name interop-name
                                         :type name)))
        grouped (group-by (fn [{:keys [type name args static?]}]
                            [(if static? "java.lang.String" type) name (count args)])
                          methods)]
    (for [[[type name args-count] methods] grouped]
      (format "CallGroup(%s::class.java, \"%s\", %d) to {objVar: Any?, args: List<Any?> -> %s}"
              type name args-count
              (if (= type "java.lang.String")
                (make-static-methods methods)
                (make-methods methods))))))

(defn render
  [imports constructors methods constants convertors]
  (format "package com.nvbn.tryrerenderer

%s


%s

fun getNewMap(): Map<NewGroup, (args: List<Any?>) -> Any?> = mapOf(
    %s
)

fun getCallMap(): Map<CallGroup, (objVar: Any?, args: List<Any?>) -> Any?> = mapOf(
    %s,
    CallGroup(java.lang.String::class.java, \"bitmapFromUrl\", 1) to {objVar: Any?, args: List<Any?> ->
      RerendererLoader.bitmapFromUrl(args[0] as String)
    }
)

fun doGet(vars: Map<String, Any?>, objVar: String, attr: String): Any = when {
    %s
    else -> throw Exception(\"Can't get non-constant ${attr}\")
}
"
          (join "\n" imports) (join "\n\n" convertors)
          (join ",\n    " constructors) (join ",\n    " methods)
          (join "\n    " constants)))

(defn get-output-path
  [path]
  (str path "/app/src/main/kotlin/com/nvbn/tryrerenderer/gen.kt"))

(defn -main
  [path & args]
  (let [url "/reference/android/graphics/Canvas.html"
        parsed (parse-all url)
        imports (make-imports parsed)
        constructors (make-contructors-map parsed)
        methods (make-methods-map parsed)
        constants (flatten (mapv make-constans parsed))
        convertors (get-numbers-convertors)
        rendered (render imports constructors methods constants
                         convertors)
        path (get-output-path path)]
    (with-open [out (writer path)]
      (.write out rendered))))
