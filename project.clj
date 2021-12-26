(defproject sig-gis/gridfire "1.4.0"
    :description "SIG's Raster-based Fire Behavior Model"
    :plugins [[lein-git-deps "0.0.1-SNAPSHOT"]
    [lein-ring "0.12.5"]]
    :dependencies [[org.clojure/clojure "1.10.1"]
    [org.clojure/data.csv "1.0.0"]
    [org.clojure/java.jdbc "0.7.11"]
    [org.clojure/spec.alpha "0.2.187"]
    [org.clojure/core.specs.alpha "0.2.44"]
    [org.postgresql/postgresql "42.2.16"]
    [net.mikera/core.matrix "0.62.0"]
    [net.mikera/vectorz-clj "0.48.0"]
    [sig-gis/magellan "20210401" :exclusions [org.eclipse.emf/org.eclipse.emf.common org.eclipse.emf/org.eclipse.emf.ecore]]
    [org.clojars.lambdatronic/matrix-viz "0.1.7"]
    [org.clojure/tools.cli "1.0.194"]
    [kixi/stats "0.5.4"]
    [ring-cors "0.1.13"]
    [ring/ring-json "0.5.1"]
    [org.clojure/data.json "1.1.0"]
    [ring/ring-core "1.9.4"]
    [org.clojure/core.async "1.3.610"]
    [com.cognitect/transit-clj "1.0.324"]
    [com.taoensso/tufte "2.2.0"]
    [compojure "1.6.2"]
    [ring/ring-defaults "0.3.2"]
    [com.nextjournal/beholder "1.0.0"]]
    :git-dependencies [["https://github.com/sig-gis/triangulum.git" "main"]]
    :source-paths [".lein-git-deps/triangulum/src" "src/clj" "src/cljc" "src"]
    :repositories [["java.net" "https://download.java.net/maven/2"]
    ["osgeo.org" "https://download.osgeo.org/webdav/geotools/"]]
    :manifest {"Specification-Title" "Java Advanced Imaging Image I/O Tools"
    "Specification-Version" "1.1"
    "Specification-Vendor" "Sun Microsystems, Inc."
    "Implementation-Title" "com.sun.media.imageio"
    "Implementation-Version" "1.1"
    "Implementation-Vendor" "Sun Microsystems, Inc."}
    :min-lein-version "2.5.2"
    :aot [gridfire.server-fireflight]
    :main gridfire.server-fireflight
    :repl-options {:init-ns gridfire.server}
    :global-vars {*warn-on-reflection* true}
    :ring {:handler gridfire.fireflight.handler/app}
    :profiles
    {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                          [ring/ring-mock "0.3.2"]]}})