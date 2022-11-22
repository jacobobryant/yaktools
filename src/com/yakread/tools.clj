(ns com.yakread.tools
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pprint :refer [pprint]]
            [clj-xpath.core :as xpath]
            [hickory.core :as hickory]
            [hickory.render :as hickr]))

(defn- split-dom* [{:keys [begin end state] :as opts} dom]
  (let [state (case state
                :pre (if (begin dom)
                       :active
                       :pre)
                :active (if (end dom)
                          :post
                          :active)
                :post)
        opts (assoc opts :state state)
        [opts content] (if (= :head (:tag dom))
                         [opts (:content dom)]
                         (reduce (fn [[opts content] item]
                                   (let [[opts item] (cond
                                                      (map? item) (split-dom* opts item)
                                                      (= :active (:state opts)) [opts item]
                                                      :else [opts nil])]
                                     [opts (cond-> content item (conj item))]))
                                 [opts []]
                                 (:content dom)))
        dom (assoc dom :content content)]
    [opts (when (or (not-empty content) (= :active (:state opts)))
            dom)]))

(defn- split-dom [opts dom]
  (second (split-dom* (merge {:state :pre
                              :begin (constantly true)
                              :end (constantly false)}
                             (when-some [id (:begin-id opts)]
                               {:begin #(= id (-> % :attrs :id))})
                             (when-some [id (:end-id opts)]
                               {:end #(= id (-> % :attrs :id))})
                             opts)
                      dom)))


(defn- html->dom [html]
  (hickory/as-hickory (hickory/parse html)))

(defn- dom->html [dom]
  (hickr/hickory-to-html dom))

(defn- parent-dir [& fs]
  (.getParentFile (io/file (.getCanonicalPath (apply io/file fs)))))

(defn- xml->doc [xml]
  (xpath/xml->doc xml {:disallow-doctype-decl false}))

(defn- points->sections [package-dir points]
  (->> points
       (partition-by :path)
       (mapcat (fn [points]
                 (let [html (slurp (io/file package-dir (:path (first points))))
                       dom (html->dom html)
                       all-ids (->> (tree-seq :content :content dom)
                                    (map (comp :id :attrs))
                                    set)
                       points (->> points
                                   (map #(update % :node-id all-ids))
                                   (map-indexed vector)
                                   (filter (fn [[i point]]
                                             (or (zero? i) (:node-id point))))
                                   (map second))]
                   (for [[point next-point] (map vector
                                                 points
                                                 (concat (rest points) [nil]))]
                     (assoc point :html (dom->html
                                         (split-dom
                                          {:begin-id (:node-id point)
                                           :end-id (:node-id next-point)}
                                          dom)))))))
       not-empty))

(defn- toc->sections [package-dir toc-doc]
  (->> (xpath/$x "//navMap//navPoint" toc-doc)
       (map (fn [node]
              (let [href (:src (xpath/$x:attrs "./content" node))]
                {:title (xpath/$x:text "./navLabel" node)
                 :path (str/replace href #"#.*" "")
                 :node-id (not-empty (str/replace href #".*#" ""))})))
       (points->sections package-dir)))

(defn- toc->sections* [package-dir toc-doc]
  (->> (xpath/$x "//a" toc-doc)
       (keep (fn [node]
               (let [href (get-in node [:attrs :href])
                     title (:text node)]
                 (when (every? some? [href title])
                   {:title title
                    :path (str/replace href #"#.*" "")
                    :node-id (not-empty (str/replace href #".*#" ""))}))))
       (points->sections package-dir)))

(defn parse-epub-dir
  "Given a path to a directory containing an unzipped epub file, returns a map
  containing the epub's contents and metadata.

  For example:

  (parse-epub-dir \"downloads/some-ebook/\")
  =>
  {:title \"Some Title\"
   :author \"Some Author\"
   :sections [{:title \"Chapter 1\"
               :html \"<html>...\"
               ...}
             ...]}"
  [path]
  (let [dir (io/file path)
        package-file (->> (slurp (io/file dir "META-INF/container.xml"))
                          xml->doc
                          (xpath/$x:attrs "//rootfile[@media-type='application/oebps-package+xml']")
                          :full-path
                          (io/file dir))
        package-dir (parent-dir package-file)
        nsmap {"" "http://www.idpf.org/2007/opf",
               "dc" "http://purl.org/dc/elements/1.1/"}
        parse-package #(xml->doc (slurp package-file))
        package-doc (parse-package)
        package-doc-with-ns (xpath/with-namespace-context nsmap (parse-package))

        title (xpath/with-namespace-context nsmap
                (-> (xpath/$x "//dc:title" package-doc-with-ns)
                    first
                    :text))
        author (xpath/with-namespace-context nsmap
                 (-> (xpath/$x "//dc:creator" package-doc-with-ns)
                     first
                     :text))

        id->href (->> (xpath/$x:attrs+ "//manifest/item" package-doc)
                      (map (juxt :id :href))
                      (into {}))
        toc-href (or (id->href (:toc (xpath/$x:attrs "//spine" package-doc)))
                     (id->href "ncx")
                     "toc.xhtml")
        toc-doc (xml->doc (slurp (io/file package-dir toc-href)))
        sections (some #(% package-dir toc-doc)
                       [toc->sections toc->sections*])]
    {:title title
     :author author
     :sections sections}))

(comment

 (let [{:keys [title author sections]} (parse-epub-dir "test-epub")]
   (println title)
   (println author)
   (println (count sections) "sections")
   (println)
   (doseq [section sections]
     (-> section
         (assoc :html-truncated (subs (:html section) 0 30)
                :size (count (:html section)))
         (dissoc :html)
         pprint)))

 )
