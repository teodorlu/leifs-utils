(ns leifs-utils.git
  (:require [babashka.fs :as file]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [babashka.process :as process]))

(def settings (edn/read-string (slurp "settings.edn")))

(defn sh->out
  [opts & args]
  (println opts (flatten args))
  (-> (apply process/sh opts (flatten args))
      :out))

(defn sh-out->json
  [opts & args]
  (-> (sh->out opts args)
      (json/parse-string true)))

(defn extract-key [key collection]
  (->> collection
       (tree-seq coll? identity)
       (keep key)))

(defn get-devops-project-data
  [org-url]
  (sh-out->json "az" "devops" "project" "list"
                "--org" org-url))

(defn get-devops-project-repo-data
  [project-id]
  (sh-out->json "az" "repos" "list"
                "--project" project-id))

(defn get-devops-repo-data
  []
  (->> (:azure/devops-org-url settings)
       (get-devops-project-data)
       (extract-key :id)
       (pmap get-devops-project-repo-data)))

(defn get-github-repo-data
  []
  (sh-out->json "gh" "repo" "list" (:github/org-name settings)
                "--language" (:github/repo-language-filter settings)
                "--source"
                "--no-archived"
                "--limit" "1000"
                "--json" "sshUrl"))

(defn clone-repo
  [repo-url]
  (let [dest-path (str (file/home) (:local/repo-root-dir settings))]
    (if-not (file/exists? dest-path) (file/create-dir dest-path) nil)
    (sh->out {:dir dest-path} "git" "clone" repo-url)))

(defn clone-all-repos
  [repos]
  (->> repos
       (extract-key :sshUrl)
       (pmap clone-repo)))

; TODO: Create a Babashka task from this function.
(defn run []
  (clone-all-repos (get-devops-repo-data))
  (clone-all-repos (get-github-repo-data)))

(defn find-repo-paths
  [root-path]
  (->> (file/glob root-path "**.git" {:hidden true})
       (map file/parent)
       (map str)))

; TODO: Create one or more Babashka tasks to run various Git command "workflows."
(->> (find-repo-paths (str (babashka.fs/home) (:local/repo-root-dir settings)))
     (map #(sh->out {:dir %} "git" "status")))