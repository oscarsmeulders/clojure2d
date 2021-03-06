;; # Namespace scope
;;
;; This namespace provides functions which cover: diplaying windows, events, canvases, image files and session management.
;; In brief:
;;
;; * Image file read and write, backed by Java ImageIO API. You can read and write BMP, JPG and PNG files. I didn't test WBMP and GIF. Image itself is Java `BufferedImage` in integer ARGB mode. Each pixel is represented as 32bit unsigned integer and 8 bits per channel. See `clojure2d.pixels` namespace for pixels operations.
;; * Canvas with functions to draw on it, represented as Canvas type with Graphics2d, BufferedImage, quality settings, primitive classes and size
;; * Display (JFrame) with events handlers (multimethods) + associated autorefreshing canvas, and optionally Processiing style `draw` function with context management
;; * Session management: unique identifier generation, logging (different file per session) and unique, sequenced filename creation.
;; * Some general helper functions

(ns clojure2d.core
  "Main Clojure2d entry point for Canvas, Window and drawing generatively.

  Basic concepts:

  * Image - `BufferedImage` java object used to store ARGB color information.
  * Canvas - Image which contains graphical context. You draw on it. Similar to processing Graphics object.
  * Window - Window which can display canvas, process events, keeps app/script concept. Similar to Processing sketch with display.
  * Events - Mouse and keyboard events

  Protocols:

  * [[ImageProto]] - basic Image operations (Image, Canvas, Window and Pixels (see [[clojure2d.pixels]]) implement this protocol.
  * Various events protocols. Events and Window implement these:
    * [[MouseXYProto]] - mouse position related to Window.
    * [[MouseButtonProto]] - status of mouse buttons.
    * [[KeyEventProto]] - keyboard status
    * [[ModifiersProto]] - status of special keys (Ctrl, Meta, Alt, etc.)
  * Additionally Window implements [[PressedProto]] in case you want to check in draw loop if your mouse or key is pressed.

  To draw on Canvas outside Window you have to create graphical context. Wrap your code into one of two functions:

  * [[with-canvas]] - binding macro `(with-canvas [local-canvas canvas-object] ...)`
  * [[with-canvas->]] - threading macro `(with-canvas-> canvas ...)`. Each function in this macro has to accept Canvas as first parameter and return Canvas.

  Canvas bound to Window accessed via callback drawing function (a'ka Processing `draw()`) has graphical context created automatically.

  ### Image

  Image is `BufferedImage` java object. Image can be read from file using [[load-image]] function or saved to file with [[save]]. ImageProto provides [[get-image]] function to access to Image object directly (if you need)
  There is no function which creates Image directly (use Canvas instead).

  ### Canvas

  ### Events

  ### Window

  ### States
  
  ### Utilities
  "
  {:metadoc/categories {:image "Image functions"
                        :canvas "Canvas functions"
                        :draw "Drawing functions"
                        :display "Screen"
                        :window "Window"
                        :events "Events"
                        :dt "Date / time"
                        :session "Session"
                        :transform "Transform canvas"}}
  (:require [clojure.java.io :refer :all]
            [clojure2d.color :as c]
            [fastmath.vector :as v]
            [fastmath.core :as m]
            [clojure.reflect :as ref]
            [fastmath.random :as r]
            [metadoc.examples :as ex]
            [clojure.string :as s])
  (:import [fastmath.vector Vec2]
           [java.awt BasicStroke Color Component Dimension Graphics2D GraphicsEnvironment Image RenderingHints Shape Toolkit Transparency]
           [java.awt.event InputEvent ComponentEvent KeyAdapter KeyEvent MouseAdapter MouseEvent MouseMotionAdapter WindowAdapter WindowEvent]
           [java.awt.geom Ellipse2D Ellipse2D$Double Line2D Line2D$Double Path2D Path2D$Double Rectangle2D Rectangle2D$Double Point2D Point2D$Double]
           [java.awt.image BufferedImage BufferStrategy Kernel ConvolveOp]
           [java.util Iterator Calendar]
           [javax.imageio IIOImage ImageIO ImageWriteParam ImageWriter]
           [javax.swing ImageIcon JFrame SwingUtilities]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)
(m/use-primitive-operators)

;; how many tasks we can run (one less than available cores)?
(def ^:const ^long
  ^{:doc "How much processor cores are in system. Constant is machine dependant."}
  available-cores (.availableProcessors (Runtime/getRuntime)))
(def ^:const ^long
  ^{:doc "How much intensive tasks can we run. Which is 150% of available cores. Constant is machine dependant."}
  available-tasks (m/round (* 1.5 available-cores)))

;; ## Image

(def ^{:doc "Default quality of saved jpg (values: 0.0 (lowest) - 1.0 (highest)"
       :metadoc/categories #{:image}
       :metadoc/examples [(ex/example "Value" *jpeg-image-quality*)]} ^:dynamic *jpeg-image-quality* 0.97)

;; ### Load image

;; To load image from the file, just call `(load-image "filename.jpg")`. Idea is taken from Processing code. Loading is done via `ImageIcon` class and later converted to BufferedImage in ARGB mode.

(defn load-image 
  "Load Image from file.

  * Input: Image filename with absolute or relative path (relative to your project folder)
  * Returns BufferedImage object or nil when Image can't be loaded.

  For supported formats check [[img-reader-formats]]."
  {:metadoc/categories #{:image}}
  [^String filename]
  (try
    (let [^Image img (.getImage (ImageIcon. filename))
          ;; ^BufferedImage img (ImageIO/read (file filename)) ;; SVG - need to set parameters...
          ]
      (let [^BufferedImage bimg (BufferedImage. (.getWidth img nil) (.getHeight img nil) BufferedImage/TYPE_INT_ARGB)
            ^Graphics2D gr (.createGraphics bimg)]
        (.drawImage gr img 0 0 nil)
        (.dispose gr)
        (.flush img)
        bimg))
    (catch Exception e (println "Can't load image: " filename " " (.getMessage e)))))

;; ### Save image

;; Saving image is more tricky. Again, most concepts are taken from Processing.
;; 
;; For provided image object and filename process goes as follows:
;;
;; * create all necessary folders
;; * extract file extension and create image writer object
;; * call multimethod to prepare data and save in chosen format
;;
;; We have two types here. First is whether file format accepts alpha channel (jpgs, bmps don't) and second is quality settings (for jpg only). In the first case we have to properly flatten the image with `flatten-image` function. In the second case we set quality attributes.

(defn file-extension
  "Extract extension from filename.

  * Input: image filename
  * Returns extension (without dot)"
  {:metadoc/categories #{:image}
   :metadoc/examples [(ex/example-session "Usage"
                        (file-extension "image.png")
                        (file-extension "no_extension")
                        (file-extension "with.path/file.doc"))]}
  [filename]
  (second (re-find #"\.(\w+)$" filename)))

(defn- ^ImageWriter get-image-writer
  "Returns image writer of image type based on extension."
  [filename]
  (let [ext (file-extension filename)
        ^Iterator iter (ImageIO/getImageWritersByFormatName ext)]
    (when (.hasNext iter)
      (.next iter))))

(defn- ^BufferedImage flatten-image
  "Flatten image, properly drop alpha channel.

  * Input: ARGB BufferedImage object
  * Returns RGB BufferedImage object"
  [^BufferedImage img]
  (let [w (.getWidth img)
        h (.getHeight img)
        arr (.getRGB img 0 0 w h nil 0 w)
        ^BufferedImage nimg (BufferedImage. w h BufferedImage/TYPE_INT_RGB)]
    (.setRGB nimg 0 0 w h arr 0 w)
    nimg))

(defn- do-save
  "Save image to the file via writer with parameters. Returns image."
  ([filename img ^ImageWriter writer]
   (do-save filename img writer (.getDefaultWriteParam writer)))
  ([filename ^BufferedImage img ^ImageWriter writer param]
   (with-open [os (output-stream filename)]
     (doto writer
       (.setOutput (ImageIO/createImageOutputStream os))
       (.write nil (IIOImage. img nil nil) param)
       (.dispose)))
   img))

(defn- formats->names
  "Convert possible formats to names' set."
  [formats]
  (->> (seq formats)
       (map s/lower-case)
       (set)))

(def
  ^{:doc "Supported writable image formats. Machine/configuration dependant."
    :metadoc/categories #{:image}
    :metadoc/examples [(ex/example "Set of writable image formats" img-writer-formats)]}
  img-writer-formats (formats->names (ImageIO/getWriterFormatNames)))

(def
  ^{:doc "Supported readable image formats. Machine/configuration dependant."
    :metadoc/categories #{:image}
    :metadoc/examples [(ex/example "Set of readable image formats" img-reader-formats)]}
  img-reader-formats (formats->names (ImageIO/getReaderFormatNames)))

;; Now we define multimethod which saves image. Multimethod is used here because some image types requires additional actions.
(defmulti save-file-type
  "Save Image to the file.

  Preprocess if necessary (depends on file type). For supported formats check [[img-writer-formats]].

  Dispatch is based on extension as keyword, ie. \".jpg\" -> `:jpg`."
  {:metadoc/categories #{:image}}
  (fn [filename _ _] (keyword (file-extension filename))))

;; JPG requires flatten image and we must set the quality defined in `*jpeg-image-quality*` variable.
(defmethod save-file-type :jpg
  [filename img ^ImageWriter writer]
  (let [nimg (flatten-image img)
        ^ImageWriteParam param (.getDefaultWriteParam writer)]
    (doto param
      (.setCompressionMode ImageWriteParam/MODE_EXPLICIT)
      (.setCompressionQuality *jpeg-image-quality*))
    (do-save filename nimg writer param)))

;; BMP also requires image flattening
(defmethod save-file-type :bmp
  [filename img writer]
  (do-save filename (flatten-image img) writer))

;; The rest file types are saved with alpha without special treatment.
(defmethod save-file-type :default
  [filename img writer]
  (do-save filename img writer))

(defn save-image
  "Save image to the file.

  * Input: image (`BufferedImage` object) and filename
  * Side effect: saved image

  Image is saved using [[save-file-type]] multimethod."
  {:metadoc/categories #{:image}}
  [b filename]
  (println (str "saving: " filename "..."))
  (make-parents filename)
  (let [iwriter (get-image-writer filename)]
    (if-not (nil? iwriter)
      (do
        (save-file-type filename b iwriter)
        (println "...done!"))
      (println (str "can't save an image: " filename)))))

;; ### Additional functions
;;
;; Just an image resizer with bicubic interpolation. Native `Graphics2D` method is called.

(defprotocol ImageProto
  "Image Protocol"
  (^{:metadoc/categories #{:image :canvas :window}} get-image [i] "Return BufferedImage")
  (^{:metadoc/categories #{:image :canvas :window}} width [i] "Width of the image.")
  (^{:metadoc/categories #{:image :canvas :window}} height [i] "Height of the image.")
  (^{:metadoc/categories #{:image :canvas :window}} save [i n] "Save image `i` to a file `n`.")
  (^{:metadoc/categories #{:image :canvas :window}} convolve [i t] "Convolve with Java ConvolveOp. See [[convolution-matrices]] for kernel names.")
  (^{:metadoc/categories #{:image :canvas :window}} subimage [i x y w h] "Return part of the image.")
  (^{:metadoc/categories #{:image :canvas}} resize [i w h] "Resize image.")
  (^{:metadoc/categories #{:image :canvas :window}} get-pixel [i x y] "Retrun color from given position."))

(declare set-rendering-hints-by-key)

(ex/defsnippet process-image-snippet
  "Process image with function `f` and save."
  (let [img (load-image "docs/cockatoo.jpg")
        unique-name (str "images/core/" (first opts) ".jpg")]
    (binding [*jpeg-image-quality* 0.7]
      (save (f img) (str "docs/" unique-name)))
    (str "../" unique-name)))

(defn- resize-image
  "Resize Image.

  * Input: image and target width and height
  * Returns newly created resized image"
  {:metadoc/categories #{:image}
   :metadoc/examples []}
  [img width height]
  (let [^BufferedImage target (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        ^Graphics2D g (.createGraphics target)]
    (set-rendering-hints-by-key g :high)
    (doto g
      (.drawImage img 0 0 width height nil)
      (.dispose))
    target))

(defn- get-subimage
  "Get subimage of give image"
  [^BufferedImage source x y w h]
  (.getSubimage source x y w h))

(ex/add-examples resize
  (ex/example-snippet "Resize image to 300x40" process-image-snippet :image (fn [img] (resize img 300 40))))

(ex/add-examples subimage
  (ex/example-snippet "Get subimage and resize." process-image-snippet :image (fn [img] (-> img
                                                                                            (subimage 100 100 12 12)
                                                                                            (resize 150 150)))))

(ex/add-examples get-pixel
  (ex/example "Get pixel from an image." (let [img (load-image "docs/cockatoo.jpg")]
                                           (get-pixel img 100 100))))

(ex/add-examples width
  (ex/example "Width of the image" (width (load-image "docs/cockatoo.jpg"))))

(ex/add-examples height
  (ex/example "Height of the image" (height (load-image "docs/cockatoo.jpg"))))

;; ## Screen info

(defn- ^Dimension screen-size
  "Screen size from java.awt.Toolkit."
  [] (.getScreenSize (Toolkit/getDefaultToolkit)))

(defn screen-width
  "Returns width of the screen."
  {:metadoc/categories #{:display}
   :metadoc/examples [(ex/example "Example value" (screen-width))]}
  [] (.getWidth (screen-size)))

(defn screen-height 
  "Returns height of the screen." 
  {:metadoc/categories #{:display}
   :metadoc/examples [(ex/example "Example value" (screen-height))]}
  [] (.getHeight (screen-size)))

;;

(def ^{:doc "Java ConvolveOp kernels. See [[convolve]]."
       :metadoc/categories #{:image}
       :metadoc/examples [(ex/example "List of kernels" (keys convolution-matrices))]}
  convolution-matrices {:shadow          (Kernel. 3 3 (float-array [0 1 2 -1 0 1 -2 -1 0]))
                        :emboss          (Kernel. 3 3 (float-array [0 2 4 -2 1 2 -4 -2 0]))
                        :edges-1         (Kernel. 3 3 (float-array [1 2 1 2 -12 2 1 2 1]))
                        :edges-2         (Kernel. 3 3 (float-array [1 0 -1 0 0 0 -1 0 1]))
                        :edges-3         (Kernel. 3 3 (float-array [0 1 0 1 -4 1 0 1 0]))
                        :edges-4         (Kernel. 3 3 (float-array [-1 -1 -1 -1 8 -1 -1 -1 -1]))
                        :sharpen         (Kernel. 3 3 (float-array [0 -1 0 -1 5 -1 0 -1 0]))
                        :sobel-x         (Kernel. 3 3 (float-array [1 0 -1 2 0 -2 1 0 -1]))
                        :sobel-y         (Kernel. 3 3 (float-array [1 2 1 0 0 0 -1 -2 -1]))
                        :gradient-x      (Kernel. 3 3 (float-array [-1 0 1 -1 0 1 -1 0 1]))
                        :gradient-y      (Kernel. 3 3 (float-array [-1 -1 -1 0 0 0 1 1 1]))
                        :box-blur        (Kernel. 3 3 (float-array (map #(/ (int %) 9.0) [1 1 1 1 1 1 1 1 1])))
                        :gaussian-blur-3 (Kernel. 3 3 (float-array (map #(/ (int %) 16.0) [1 2 1 2 4 2 1 2 1])))
                        :gaussian-blur-5 (Kernel. 5 5 (float-array (map #(/ (int %) 256.0) [1 4 6 4 1 4 16 24 16 4 6 24 36 24 6 4 16 24 16 4 1 4 6 4 1])))
                        :unsharp         (Kernel. 5 5 (float-array (map #(/ (int %) -256.0) [1 4 6 4 1 4 16 24 16 4 6 24 -476 24 6 4 16 24 16 4 1 4 6 4 1])))})

(defmacro ^:private make-conv-examples
  []
  `(ex/add-examples convolve
     ~@(for [k (keys convolution-matrices)
             :let [n (str "Convolve image with " (name k) " kernel.")]]
         (list 'ex/example-snippet n 'process-image-snippet :image (list 'fn '[img] (list 'convolve 'img k))))))

(make-conv-examples)

;; Add ImageProto functions to BufferedImae
(extend BufferedImage
  ImageProto
  {:get-image identity
   :width (fn [^BufferedImage i] (.getWidth i))
   :height (fn [^BufferedImage i] (.getHeight i))
   :save #(do
            (save-image %1 %2)
            %1)
   :convolve (fn [^BufferedImage i t]
               (let [kernel (if (keyword? t)
                              (t convolution-matrices)
                              (let [s (int (m/sqrt (count t)))]
                                (Kernel. s s (float-array t))))]
                 (.filter ^ConvolveOp (ConvolveOp. kernel) i nil)))
   :subimage get-subimage
   :resize resize-image
   :get-pixel (fn [^BufferedImage i ^long x ^long y]
                (if (bool-or (< x 0)
                             (< y 0)
                             (>= x (.getWidth i))
                             (>= y (.getHeight i)))
                  (c/color 0 0 0)
                  (let [b (int-array 1)
                        ^java.awt.image.Raster raster (.getRaster i)]
                    (.getDataElements raster x y b)
                    (let [v (aget b 0)
                          b (bit-and v 0xff)
                          g (bit-and (>> v 8) 0xff)
                          r (bit-and (>> v 16) 0xff)
                          a (bit-and (>> v 24) 0xff)]
                      (if (== (.getNumBands raster) 3)
                        (c/color r g b)
                        (c/color r g b a))))))})

;;

(declare resize-canvas)

;; Canvas type. Use `get-image` to extract image (`BufferedImage`).
(defrecord ^{:doc "Test"}
    Canvas [^Graphics2D graphics
            ^BufferedImage buffer
            ^Line2D line-obj
            ^Rectangle2D rect-obj
            ^Ellipse2D ellipse-obj
            hints
            ^long w
            ^long h
            transform-stack
            font]
  ImageProto
  (get-image [_] buffer)
  (width [_] w)
  (height [_] h)
  (save [c n] (save-image buffer n) c)
  (convolve [_ t]
    (convolve buffer t))
  (resize [c w h] (resize-canvas c w h))
  (subimage [_ x y w h] (get-subimage buffer x y w h))
  (get-pixel [_ x y] (get-pixel buffer x y)))

(def ^{:doc "Rendering hints define quality of drawing or window rendering.

The differences are in interpolation, antialiasing, speed vs quality rendering etc. See the source code for the list of each value.

The `:highest` is `:high` with `VALUE_STROKE_PURE` added. Be aware that this option can give very soft lines.

Default hint for Canvas is `:high`. You can set also hint for Window which means that when display is refreshed this hint is applied (java defaults are used otherwise)."
       :metadoc/categories #{:canvas :window}
       :metadoc/examples [(ex/example "List of possible hints." (keys rendering-hints))]}
  rendering-hints {:low [[RenderingHints/KEY_ANTIALIASING        RenderingHints/VALUE_ANTIALIAS_OFF]
                         [RenderingHints/KEY_INTERPOLATION       RenderingHints/VALUE_INTERPOLATION_NEAREST_NEIGHBOR]
                         [RenderingHints/KEY_ALPHA_INTERPOLATION RenderingHints/VALUE_ALPHA_INTERPOLATION_SPEED]
                         [RenderingHints/KEY_COLOR_RENDERING     RenderingHints/VALUE_COLOR_RENDER_SPEED]
                         [RenderingHints/KEY_RENDERING           RenderingHints/VALUE_RENDER_SPEED]
                         [RenderingHints/KEY_FRACTIONALMETRICS   RenderingHints/VALUE_FRACTIONALMETRICS_OFF]
                         [RenderingHints/KEY_TEXT_ANTIALIASING   RenderingHints/VALUE_TEXT_ANTIALIAS_OFF]]
                   :mid [[RenderingHints/KEY_ANTIALIASING        RenderingHints/VALUE_ANTIALIAS_ON]
                         [RenderingHints/KEY_INTERPOLATION       RenderingHints/VALUE_INTERPOLATION_BILINEAR]
                         [RenderingHints/KEY_ALPHA_INTERPOLATION RenderingHints/VALUE_ALPHA_INTERPOLATION_SPEED]
                         [RenderingHints/KEY_COLOR_RENDERING     RenderingHints/VALUE_COLOR_RENDER_SPEED]
                         [RenderingHints/KEY_RENDERING           RenderingHints/VALUE_RENDER_SPEED]
                         [RenderingHints/KEY_FRACTIONALMETRICS   RenderingHints/VALUE_FRACTIONALMETRICS_OFF]
                         [RenderingHints/KEY_TEXT_ANTIALIASING   RenderingHints/VALUE_TEXT_ANTIALIAS_ON]]
                   :high [[RenderingHints/KEY_ANTIALIASING        RenderingHints/VALUE_ANTIALIAS_ON]
                          [RenderingHints/KEY_INTERPOLATION       RenderingHints/VALUE_INTERPOLATION_BICUBIC]
                          [RenderingHints/KEY_ALPHA_INTERPOLATION RenderingHints/VALUE_ALPHA_INTERPOLATION_QUALITY]
                          [RenderingHints/KEY_COLOR_RENDERING     RenderingHints/VALUE_COLOR_RENDER_QUALITY]
                          [RenderingHints/KEY_RENDERING           RenderingHints/VALUE_RENDER_QUALITY]
                          [RenderingHints/KEY_FRACTIONALMETRICS   RenderingHints/VALUE_FRACTIONALMETRICS_ON]
                          [RenderingHints/KEY_TEXT_ANTIALIASING   RenderingHints/VALUE_TEXT_ANTIALIAS_ON]]
                   :highest [[RenderingHints/KEY_ANTIALIASING        RenderingHints/VALUE_ANTIALIAS_ON]
                             [RenderingHints/KEY_INTERPOLATION       RenderingHints/VALUE_INTERPOLATION_BICUBIC]
                             [RenderingHints/KEY_ALPHA_INTERPOLATION RenderingHints/VALUE_ALPHA_INTERPOLATION_QUALITY]
                             [RenderingHints/KEY_COLOR_RENDERING     RenderingHints/VALUE_COLOR_RENDER_QUALITY]
                             [RenderingHints/KEY_RENDERING           RenderingHints/VALUE_RENDER_QUALITY]
                             [RenderingHints/KEY_FRACTIONALMETRICS   RenderingHints/VALUE_FRACTIONALMETRICS_ON]
                             [RenderingHints/KEY_TEXT_ANTIALIASING   RenderingHints/VALUE_TEXT_ANTIALIAS_ON]
                             [RenderingHints/KEY_STROKE_CONTROL      RenderingHints/VALUE_STROKE_PURE]]})

(defn- get-rendering-hints
  "Return rendering hints for a key or return default (or :high).
  This function is made to protect against user errors."
  ([hint default]
   (rendering-hints (or (some #{hint} (keys rendering-hints)) default)))
  ([hint]
   (get-rendering-hints hint :high)))

(defn- set-rendering-hints
  "Sets rendering hints for graphics context."
  [^Graphics2D g hints]
  (doseq [[key v] hints]
    (.setRenderingHint g key v))
  true)

(defn- set-rendering-hints-by-key
  "Sets rendering hints for graphics context."
  [g hints]
  (if (contains? rendering-hints hints)
    (set-rendering-hints g (hints rendering-hints))
    false))

;; 

(defn flush-graphics
  "Dispose current `Graphics2D`

  Do not use directly. Call [[with-canvas->]] or [[with-canvas]] macros."
  [^Canvas canvas]
  (.dispose ^Graphics2D (.graphics canvas)))

(defn make-graphics
  "Create new `Graphics2D` object and set rendering hints.

  Do not use directly. Call [[with-canvas->]] or [[with-canvas]] macros."
  [^Canvas canvas]
  (let [^Graphics2D ng (.createGraphics ^BufferedImage (.buffer canvas))]
    (set-rendering-hints ng (or (.hints canvas) (rendering-hints :high)))
    (when-let [f (.font canvas)] (.setFont ng f))
    (Canvas. ng
             (.buffer canvas)
             (.line-obj canvas)
             (.rect-obj canvas)
             (.ellipse-obj canvas)
             (.hints canvas)
             (.w canvas)
             (.h canvas)
             (atom [])
             (.font canvas))))

(defmacro with-canvas->
  "Threading macro which takes care to create and destroy `Graphics2D` object for drawings on canvas. Macro returns result of last call.

  Each function have to accept canvas as second parameter and have to return canvas.

  See also [[with-canvas]]."
  {:metadoc/categories #{:canvas}}
  [canvas & body]  
  `(let [newcanvas# (make-graphics ~canvas)
         result# (-> newcanvas#
                     ~@body)]
     (do
       (flush-graphics newcanvas#)
       result#)))

(defmacro with-canvas
  "Macro which takes care to create and destroy `Graphics2D` object for drawings on canvas. Macro returns result of last call.

  See also [[with-canvas->]]." 
  {:metadoc/categories #{:canvas}}
  [[c canvas] & body]
  `(let [~c (make-graphics ~canvas)
         result# (do ~@body)]
     (do
       (flush-graphics ~c)
       result#)))

;; Next functions are canvas management functions: create, save, resize and set quality.

(declare set-background)
(declare set-stroke)
(declare set-color)
(declare image)

(defn canvas
  "Create and return Canvas with `width`, `height` and optionally quality hint name (keyword) and font name.

  Default hint is `:high`. Default font is system one.

  Canvas is an object which keeps everything needed to draw Java2d primitives on it. As a drawing buffer `BufferedImage` is used. To draw on Canvas directly wrap your functions with [[with-canvas]] or [[with-canvas->]] macros to create graphical context.

  Be aware that drawing on Canvas is single threaded.

  Font you set while creating canvas will be default font. You can set another font in the code with [[set-font]] and [[set-font-attributes]] functions. However these set font temporary."
  {:metadoc/categories #{:canvas}
   :metadoc/examples [(ex/example "Canvas is the record." (canvas 20 30 :low))
                      (ex/example-session "Check ImageProto on canvas."
                        (width (canvas 10 20))
                        (height (canvas 10 20))
                        (get-image (canvas 5 6))
                        (width (resize (canvas 1 2) 15 15))
                        (height (subimage (canvas 10 10) 5 5 2 2)))]}
  ([^long width ^long height hint ^String font]
   (let [^BufferedImage buffer (.. GraphicsEnvironment 
                                   (getLocalGraphicsEnvironment)
                                   (getDefaultScreenDevice)
                                   (getDefaultConfiguration)
                                   (createCompatibleImage width height Transparency/TRANSLUCENT))        
         result (Canvas. nil
                         buffer
                         (Line2D$Double.)
                         (Rectangle2D$Double.)
                         (Ellipse2D$Double.)
                         (get-rendering-hints hint)
                         width height
                         nil
                         (when font (java.awt.Font/decode font)))]
     (with-canvas-> result
       (set-background Color/black))
     result))
  ([width height]
   (canvas width height :high nil))
  ([width height hint]
   (canvas width height hint nil)))

(defn- resize-canvas
  "Resize canvas to new dimensions. Creates and returns new canvas."
  [^Canvas c width height]
  (let [ncanvas (canvas width height (.hints c))]
    (with-canvas-> ncanvas
      (image (get-image c)))))

;;

(ex/add-examples with-canvas->
  (ex/example-snippet "Draw on canvas" process-image-snippet :image (fn [img]
                                                                      (with-canvas-> (canvas 100 100)
                                                                        (image img 50 50 50 50)))))

(ex/add-examples with-canvas
  (ex/example-snippet "Draw on canvas" process-image-snippet :image (fn [img]
                                                                      (with-canvas [c (canvas 200 200)]
                                                                        (dotimes [i 50]
                                                                          (let [x (r/irand -50 100)
                                                                                y (r/irand -50 100)
                                                                                w (r/irand (- 200 x))
                                                                                h (r/irand (- 200 y))]
                                                                            (image c img x y w h)))
                                                                        c))))

;; ### Transformations
;;
;; You can transform your working area with couple of functions on canvas. They act exactly the same as in Processing. Transformation context is bound to canvas wrapped to `with-canvas->` macro. Each `with-canvas->` cleans all transformations.
;; Transformations are concatenated.

(defn scale
  "Scale canvas"
  {:metadoc/categories #{:transform :canvas}
   :metadoc/examples [(ex/example-snippet "Scale canvas" process-image-snippet :image (fn [img]
                                                                                        (with-canvas-> (canvas 150 150)
                                                                                          (scale 0.5)
                                                                                          (image img 0 0))))]} 
  ([^Canvas canvas ^double scalex ^double scaley]
   (.scale ^Graphics2D (.graphics canvas) scalex scaley)
   canvas)
  ([canvas s] (scale canvas s s)))

(defn flip-x
  "Flip canvas over x axis"
  {:metadoc/categories #{:transform :canvas}
   :metadoc/examples [(ex/example-snippet "Scale canvas" process-image-snippet :image (fn [img]
                                                                                        (with-canvas-> (canvas 150 150)
                                                                                          (flip-x)
                                                                                          (image img -150 0))))]}
  [canvas]
  (scale canvas -1.0 1.0))

(defn flip-y
  "Flip canvas over y axis"
  {:metadoc/categories #{:transform :canvas}
   :metadoc/examples [(ex/example-snippet "Scale canvas" process-image-snippet :image (fn [img]
                                                                                        (with-canvas-> (canvas 150 150)
                                                                                          (flip-y)
                                                                                          (image img 0 -150))))]}
  [canvas]
  (scale canvas 1.0 -1.0))

(defn translate
  "Translate origin"
  {:metadoc/categories #{:transform :canvas}
   :metadoc/examples [(ex/example-snippet "Scale canvas" process-image-snippet :image (fn [img]
                                                                                        (with-canvas-> (canvas 150 150)
                                                                                          (translate 20 20)
                                                                                          (image img 0 0))))]}
  ([^Canvas canvas ^double tx ^double ty]
   (.translate ^Graphics2D (.graphics canvas) tx ty)
   canvas)
  ([canvas ^Vec2 v]
   (translate canvas (.x v) (.y v))))

(defn rotate
  "Rotate canvas"
  {:metadoc/categories #{:transform :canvas}
   :metadoc/examples [(ex/example-snippet "Scale canvas" process-image-snippet :image (fn [img]
                                                                                        (with-canvas-> (canvas 150 150)
                                                                                          (translate 75 75)
                                                                                          (rotate m/QUARTER_PI)
                                                                                          (image img -75 -75))))]}
  [^Canvas canvas ^double angle]
  (.rotate ^Graphics2D (.graphics canvas) angle)
  canvas)

(defn shear
  "Shear canvas"
  {:metadoc/categories #{:transform :canvas}
   :metadoc/examples [(ex/example-snippet "Scale canvas" process-image-snippet :image (fn [img]
                                                                                        (with-canvas-> (canvas 150 150)
                                                                                          (shear 0.2 0.4)
                                                                                          (image img 0 0))))]}
  ([^Canvas canvas ^double sx ^double sy]
   (.shear ^Graphics2D (.graphics canvas) sx sy)
   canvas)
  ([canvas s] (shear canvas s s)))

(defn push-matrix
  "Remember current transformation state.

  See also [[pop-matrix]], [[reset-matrix]]."
  {:metadoc/categories #{:transform :canvas}}
  [^Canvas canvas]
  (swap! (.transform-stack canvas) conj (.getTransform ^Graphics2D (.graphics canvas)))
  canvas)

(defn pop-matrix
  "Restore saved transformation state.

  See also [[push-matrix]], [[reset-matrix]]."
  {:metadoc/categories #{:transform :canvas}}
  [^Canvas canvas]
  (when (seq @(.transform-stack canvas))
    (let [v (peek @(.transform-stack canvas))]
      (swap! (.transform-stack canvas) pop)
      (.setTransform ^Graphics2D (.graphics canvas) v)))
  canvas)

(def ^:private push-pop-matrix-example
  (ex/example-snippet "Scale canvas" process-image-snippet :image
    (fn [img]
      (with-canvas [c (canvas 250 250)]
        (translate c 125 125)
        (doseq [a (range 0 m/TWO_PI 0.3)]
          (let [x (* 80.0 (m/cos a))
                y (* 80.0 (m/sin a))]
            (-> c
                (push-matrix)
                (translate x y)
                (rotate a)
                (image img 0 0 20 20)
                (pop-matrix))))
        c))))

(ex/add-examples push-matrix push-pop-matrix-example)
(ex/add-examples pop-matrix push-pop-matrix-example)

(defn transform
  "Transform given point or coordinates with current transformation. See [[inv-transform]]."
  {:metadoc/categories #{:transform :canvas}
   :metadoc/examples [(ex/example "Transform point."
                        (with-canvas [c (canvas 100 100)]
                          (translate c 50 50)
                          (rotate c m/HALF_PI)
                          (transform c 10 10)))]}
  ([^Canvas canvas x y]
   (let [^Point2D p (.transform ^java.awt.geom.AffineTransform (.getTransform ^Graphics2D (.graphics canvas)) (Point2D$Double. x y) nil)]
     (Vec2. (.getX p) (.getY p))))
  ([canvas ^Vec2 v]
   (transform canvas (.x v) (.y v))))

(defn inv-transform
  "Inverse transform of given point or coordinates with current transformation. See [[transform]]."
  {:metadoc/categories #{:transform :canvas}
   :metadoc/examples [(ex/example "Invesre transform of point."
                        (with-canvas [c (canvas 100 100)]
                          (translate c 50 50)
                          (rotate c m/HALF_PI)
                          (inv-transform c 40 60)))]}
  ([^Canvas canvas x y]
   (let [^Point2D p (.inverseTransform ^java.awt.geom.AffineTransform (.getTransform ^Graphics2D (.graphics canvas)) (Point2D$Double. x y) nil)]
     (Vec2. (.getX p) (.getY p))))
  ([canvas ^Vec2 v]
   (inv-transform canvas (.x v) (.y v))))

(defn reset-matrix
  "Reset transformations."
  {:metadoc/categories #{:transform :canvas}}
  [^Canvas canvas]
  (.setTransform ^Graphics2D (.graphics canvas) (java.awt.geom.AffineTransform.))
  canvas)

;; ### Drawing functions
;;
;; Here we have basic drawing functions. What you need to remember:
;;
;; * Color is set globally for all figures (exception: `set-background`)
;; * Filled or stroke figures are determined by last parameter `stroke?`. When set to `true` draws figure outline, filled otherwise (default). Default is `false` (filled).
;; * Always use with `with-canvas->` macro.
;; 
;; All functions return canvas object

(ex/defsnippet drawing-snippet
  "Draw something on canvas and save."
  (let [canvas (canvas 200 200) 
        unique-name (str "images/core/" (first opts) ".jpg")]
    (with-canvas-> canvas
      (set-background 0x30426a)
      (set-color :white)
      (f))
    (binding [*jpeg-image-quality* 0.85]
      (save canvas (str "docs/" unique-name)))
    (str "../" unique-name)))

(defn line
  "Draw line from point `(x1,y1)` to `(x2,y2)`"
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Draw some lines" drawing-snippet :image
                        (fn [canvas] 
                          (doseq [^long x (range 10 190 10)]
                            (line canvas x 10 (- 190 x) 190))))]}
  ([^Canvas canvas x1 y1 x2 y2]
   (let [^Line2D l (.line-obj canvas)]
     (.setLine l x1 y1 x2 y2)
     (.draw ^Graphics2D (.graphics canvas) l))
   canvas)
  ([canvas ^Vec2 v1 ^Vec2 v2]
   (line canvas (.x v1) (.y v1) (.x v2) (.y v2))))

(def ^{:doc "Stroke join types"
       :metadoc/categories #{:draw}
       :metadoc/examples [(ex/example "List of stroke join types" (keys stroke-joins))]}
  stroke-joins {:bevel BasicStroke/JOIN_BEVEL
                :mitter BasicStroke/JOIN_MITER
                :round BasicStroke/JOIN_ROUND})

(def ^{:doc "Stroke cap types"
       :metadoc/categories #{:draw}
       :metadoc/examples [(ex/example "List of stroke cap types" (keys stroke-caps))]}
  stroke-caps {:round BasicStroke/CAP_ROUND
               :butt BasicStroke/CAP_BUTT
               :square BasicStroke/CAP_SQUARE})

(declare rect)

(defn set-stroke
  "Set stroke (line) attributes like `cap`, `join` and size.

  Default `:round` and `:bevel` are used. Default size is `1.0`.

  See [[stroke-joins]] and [[stroke-caps]] for names."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Various stroke settings." drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (set-stroke 10 :round)
                              (line 25 20 25 180)
                              (set-stroke 10 :butt)
                              (line 55 20 55 180)
                              (set-stroke 10 :square)
                              (line 85 20 85 180)
                              (set-stroke 10 :round :bevel)
                              (rect 120 20 60 40 true)
                              (set-stroke 10 :round :mitter)
                              (rect 120 80 60 40 true)
                              (set-stroke 10 :round :round)
                              (rect 120 140 60 40 true))))]}
  ([^Canvas canvas size cap join]
   (.setStroke ^Graphics2D (.graphics canvas) (BasicStroke. size
                                                            (or (stroke-caps cap) BasicStroke/CAP_ROUND)
                                                            (or (stroke-joins join) BasicStroke/JOIN_BEVEL)))
   canvas)
  ([canvas size cap]
   (set-stroke canvas size cap :bevel))
  ([canvas size]
   (set-stroke canvas size :round :bevel))
  ([canvas]
   (set-stroke canvas 1.0)))

(defn point
  "Draw point at `x`,`y` or `^Vec2` position.

  It's implemented as a very short line. Consider using `(rect x y 1 1)` for speed when `x` and `y` are integers."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Sequence of points." drawing-snippet :image
                        (fn [canvas]
                          (doseq [^long x (range 10 190 10)]
                            (set-stroke canvas (/ x 20))
                            (point canvas x x))))
                      (ex/example-snippet "Magnified point can look differently when different stroke settings are used."
                        drawing-snippet :image (fn [canvas]
                                                 (-> canvas
                                                     (scale 80.0)
                                                     (set-stroke 0.5)
                                                     (point 0.5 0.5)
                                                     (set-stroke 0.5 :square)
                                                     (point 1.5 1.5))))]}  
  ([canvas ^double x ^double y]
   (line canvas x y (+ x 10.0e-6) (+ y 10.0e-6))
   canvas)
  ([canvas ^Vec2 vec]
   (point canvas (.x vec) (.y vec))))

(defn- draw-fill-or-stroke
  "Draw filled or outlined shape."
  [^Graphics2D g ^Shape obj stroke?]
  (if stroke?
    (.draw g obj)
    (.fill g obj)))

(defn rect
  "Draw rectangle with top-left corner at `(x,y)` position with width `w` and height `h`. Optionally you can set `stroke?` (default: `false`) to `true` if you don't want to fill rectangle and draw outline only.

  See also: [[crect]]."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Two squares, one filled and second as outline." drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (rect 30 30 50 50) 
                              (rect 80 80 90 90 true))))]}
  ([^Canvas canvas x y w h stroke?]
   (let [^Rectangle2D r (.rect-obj canvas)] 
     (.setFrame r x y w h)
     (draw-fill-or-stroke (.graphics canvas) r stroke?))
   canvas)
  ([canvas x y w h]
   (rect canvas x y w h false)))

(defn crect
  "Centered version of [[rect]]."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Two squares, regular and centered." drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (set-color :white 160)
                              (rect 50 50 100 100) 
                              (crect 50 50 60 60))))]}
  ([canvas x y w h stroke?]
   (let [w2 (* 0.5 ^double w)
         h2 (* 0.5 ^double h)]
     (rect canvas (- ^double x w2) (- ^double y h2) w h stroke?))
   canvas)
  ([canvas x y w h]
   (crect canvas x y w h false)))

(defn ellipse
  "Draw ellipse with middle at `(x,y)` position with width `w` and height `h`."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "A couple of ellises." drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (set-color :white 200)
                              (ellipse 100 100 50 150)
                              (ellipse 100 100 150 50 true)
                              (ellipse 100 100 20 20)
                              (set-color :black 200)
                              (ellipse 100 100 20 20 true))))]}
  ([^Canvas canvas x1 y1 w h stroke?]
   (let [^Ellipse2D e (.ellipse_obj canvas)]
     (.setFrame e (- ^double x1 (* ^double w 0.5)) (- ^double y1 (* ^double h 0.5)) w h)
     (draw-fill-or-stroke (.graphics canvas) e stroke?))
   canvas)
  ([canvas x1 y1 w h]
   (ellipse canvas x1 y1 w h false)))

(defn triangle
  "Draw triangle with corners at 3 positions."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Two triangles" drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (triangle 30 30 170 100 30 170)
                              (set-color :black)
                              (triangle 170 30 170 170 30 100 true))))]}
  ([^Canvas canvas x1 y1 x2 y2 x3 y3 stroke?]
   (let [^Path2D p (Path2D$Double.)]
     (doto p
       (.moveTo x1 y1)
       (.lineTo x2 y2)
       (.lineTo x3 y3)
       (.closePath))
     (draw-fill-or-stroke (.graphics canvas) p stroke?))
   canvas)
  ([canvas x1 y1 x2 y2 x3 y3]
   (triangle canvas x1 y1 x2 y2 x3 y3 false)))

(defn triangle-strip
  "Draw triangle strip. Implementation of `Processing` `STRIP` shape.

  Input: list of vertices as Vec2 vectors.

  See also: [[triangle-fan]]."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Triangle strip" drawing-snippet :image
                        (fn [canvas]
                          (set-color canvas :white 190)
                          (translate canvas 100 100)
                          (let [s (for [a (range 0 65 3.0)]
                                    (v/vec2 (* 90.0 (m/cos a))
                                            (* 90.0 (m/sin a))))]
                            (triangle-strip canvas s true))))]}
  ([canvas vs stroke?]
   (when (> (count vs) 2)
     (loop [^Vec2 v1 (first vs)
            ^Vec2 v2 (second vs)
            vss (nnext vs)]
       (when vss
         (let [^Vec2 v3 (first vss)]
           (triangle canvas (.x v2) (.y v2) (.x v3) (.y v3) (.x v1) (.y v1) stroke?)
           (recur v2 v3 (next vss))))))
   canvas)
  ([canvas vs]
   (triangle-strip canvas vs false)))

(defn triangle-fan
  "Draw triangle fan. Implementation of `Processing` `FAN` shape.

  First point is common vertex of all triangles.
  
  Input: list of vertices as Vec2 vectors.

  See also: [[triangle-strip]]."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Triangle fan" drawing-snippet :image
                        (fn [canvas]
                          (set-color canvas :white 190)
                          (translate canvas 100 100)
                          (let [s (for [a (range 0 65 3.0)]
                                    (v/vec2 (* 90.0 (m/cos a))
                                            (* 90.0 (m/sin a))))]
                            (triangle-fan canvas s true))))]}
  ([canvas vs stroke?]
   (when (> (count vs) 2)
     (let [^Vec2 v1 (first vs)]
       (loop [^Vec2 v2 (second vs)
              vss (nnext vs)]
         (when vss
           (let [^Vec2 v3 (first vss)]
             (triangle canvas (.x v1) (.y v1) (.x v2) (.y v2) (.x v3) (.y v3) stroke?)
             (recur v3 (next vss)))))))
   canvas)
  ([canvas vs]
   (triangle-strip canvas vs false)))

(defn path
  "Draw path from lines.

  Input: list of Vec2 points, close? - close path or not (default: false), stroke? - draw lines or filled shape (default true - lines).

  See also [[path-bezier]]."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Path" drawing-snippet :image
                        (fn [canvas]
                          (set-color canvas :white 190)
                          (translate canvas 100 100)
                          (let [s (for [^double a (range 0 65 1.3)]
                                    (v/vec2 (* (+ a 25) (m/cos a))
                                            (* (+ a 25) (m/sin a))))]
                            (path canvas s))) )]}
  ([^Canvas canvas vs close? stroke?]
   (when (seq vs)
     (let [^Path2D p (Path2D$Double.)
           ^Vec2 m (first vs)]
       (.moveTo p (.x m) (.y m))
       (doseq [^Vec2 v (next vs)]
         (.lineTo p (.x v) (.y v)))
       (when (or (not stroke?) close?) (.closePath p))
       (draw-fill-or-stroke (.graphics canvas) p stroke?)))
   canvas)
  ([canvas vs close?] (path canvas vs close? true))
  ([canvas vs] (path canvas vs false true)))

(defn- calculate-bezier-control-points
  "Calculate bezier spline control points. http://www.antigrain.com/research/bezier_interpolation/index.html"
  [v0 v1 v2 v3]
  (let [c1 (v/mult (v/add v0 v1) 0.5)
        c2 (v/mult (v/add v1 v2) 0.5)
        c3 (v/mult (v/add v2 v3) 0.5)
        ^double len1 (v/mag c1)
        ^double len2 (v/mag c2)
        ^double len3 (v/mag c3)
        k1 (/ len1 (+ len1 len2))
        k2 (/ len2 (+ len2 len3))
        m1 (v/add c1 (v/mult (v/sub c2 c1) k1))
        m2 (v/add c2 (v/mult (v/sub c3 c2) k2))
        cp1 (-> c2
                (v/sub m1)
                (v/add m1)
                (v/add v1)
                (v/sub m1))
        cp2 (-> c2
                (v/sub m2)
                (v/add m2)
                (v/add v2)
                (v/sub m2))]
    [cp1 cp2]))

(defn path-bezier
  "Draw path from quad curves.

  Input: list of Vec2 points, close? - close path or not (default: false), stroke? - draw lines or filled shape (default true - lines).

  See also [[path]]."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Bezier path" drawing-snippet :image
                        (fn [canvas]
                          (set-color canvas :white 190)
                          (translate canvas 100 100)
                          (let [s (for [^double a (range 0 65 1.3)]
                                    (v/vec2 (* (+ a 25) (m/cos a))
                                            (* (+ a 25) (m/sin a))))]
                            (path-bezier canvas s))))]}  
  ([^Canvas canvas vs close? stroke?]
   (when (> (count vs) 3)
     (let [cl? (or (not stroke?) close?)
           ^Path2D p (Path2D$Double.)
           ^Vec2 m0 (first vs)
           ^Vec2 m1 (second vs)
           m2 (nth vs 2) 
           f0 (if cl? m0 m0)
           ^Vec2 f1 (if cl? m1 m0)
           f2 (if cl? m2 m1)
           f3 (if cl? (nth vs 3) m2)
           vs (if cl? (next vs) vs)]
       (.moveTo p (.x f1) (.y f1))
       (loop [v0 f0
              v1 f1
              ^Vec2 v2 f2
              ^Vec2 v3 f3
              nvs (drop 3 vs)]
         (let [[^Vec2 cp1 ^Vec2 cp2] (calculate-bezier-control-points v0 v1 v2 v3)]
           (.curveTo p (.x cp1) (.y cp1) (.x cp2) (.y cp2) (.x v2) (.y v2))
           (if-not (empty? nvs)
             (recur v1 v2 v3 (first nvs) (next nvs))
             (if cl?
               (let [[^Vec2 cp1 ^Vec2 cp2] (calculate-bezier-control-points v1 v2 v3 m0)
                     [^Vec2 cp3 ^Vec2 cp4] (calculate-bezier-control-points v2 v3 m0 m1)
                     [^Vec2 cp5 ^Vec2 cp6] (calculate-bezier-control-points v3 m0 m1 m2)]
                 (.curveTo p (.x cp1) (.y cp1) (.x cp2) (.y cp2) (.x v3) (.y v3))
                 (.curveTo p (.x cp3) (.y cp3) (.x cp4) (.y cp4) (.x m0) (.y m0))
                 (.curveTo p (.x cp5) (.y cp5) (.x cp6) (.y cp6) (.x m1) (.y m1)))
               (let [[^Vec2 cp1 ^Vec2 cp2] (calculate-bezier-control-points v1 v2 v3 v3)]
                 (.curveTo p (.x cp1) (.y cp1) (.x cp2) (.y cp2) (.x v3) (.y v3)))))))
       (draw-fill-or-stroke (.graphics canvas) p stroke?)))
   canvas)
  ([canvas vs close?] (path-bezier canvas vs close? true))
  ([canvas vs] (path-bezier canvas vs false true)))

(defn bezier
  "Draw bezier curve with 4 sets of coordinates."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Bezier curve" drawing-snippet :image
                        (fn [canvas]
                          (bezier canvas 20 20 180 20 180 180 20 180 false)
                          (set-color canvas :black)
                          (bezier canvas 20 180 20 20 180 20 180 180)))]}  
  ([^Canvas canvas x1 y1 x2 y2 x3 y3 x4 y4 stroke?]
   (let [^Path2D p (Path2D$Double.)]
     (doto p
       (.moveTo x1 y1)
       (.curveTo x2 y2 x3 y3 x4 y4))
     (draw-fill-or-stroke (.graphics canvas) p stroke?)))
  ([canvas x1 y1 x2 y2 x3 y3 x4 y4]
   (bezier canvas x1 y1 x2 y2 x3 y3 x4 y4 true)))

(defn curve
  "Draw quadratic curve with 3 sets of coordinates."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Quadratic curve" drawing-snippet :image
                        (fn [canvas]
                          (curve canvas 20 20 180 20 180 180 false)
                          (set-color canvas :black)
                          (curve canvas 20 180 20 20 180 20)))]}  
  ([^Canvas canvas x1 y1 x2 y2 x3 y3 stroke?]
   (let [^Path2D p (Path2D$Double.)]
     (doto p
       (.moveTo x1 y1)
       (.quadTo x2 y2 x3 y3))
     (draw-fill-or-stroke (.graphics canvas) p stroke?)))
  ([canvas x1 y1 x2 y2 x3 y3]
   (curve canvas x1 y1 x2 y2 x3 y3 true)))

(defn quad
  "Draw quad with corners at 4 positions."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Quad" drawing-snippet :image
                        (fn [canvas]
                          (quad canvas 20 20 180 50 50 180 70 70)))]}
  ([^Canvas canvas x1 y1 x2 y2 x3 y3 x4 y4 stroke?]
   (let [^Path2D p (Path2D$Double.)]
     (doto p
       (.moveTo x1 y1)
       (.lineTo x2 y2)
       (.lineTo x3 y3)
       (.lineTo x4 y4)
       (.closePath))
     (draw-fill-or-stroke (.graphics canvas) p stroke?))
   canvas)
  ([canvas x1 y1 x2 y2 x3 y3 x4 y4]
   (quad canvas x1 y1 x2 y2 x3 y3 x4 y4 false)))

(declare text)

(defn set-font
  "Set font by name."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Various fonts" drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (set-font "Courier New")
                              (text "Trying to set Courier New" 100 50 :center)
                              (set-font "Arial")
                              (text "Trying to set Arial" 100 100 :center)
                              (set-font "Verdana")
                              (text "Trying to set Helvetica" 100 150 :center))))]}
  [^Canvas canvas ^String fontname]
  (let [f (java.awt.Font/decode fontname)]
    (.setFont ^Graphics2D (.graphics canvas) f)
    canvas))

(defn set-font-attributes
  "Set current font size and attributes.

  Attributes are: `:bold`, `:italic`, `:bold-italic`."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Font attributes" drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (set-font-attributes 30)
                              (text "Size 30" 100 50 :center)
                              (set-font-attributes 15 :italic)
                              (text "Size 15, italic" 100 100 :center)
                              (set-font-attributes 20 :bold-italic)
                              (text "Size 20, bold, italic" 100 150 :center))))]}
  ([^Canvas canvas ^double size style]
   (let [s (or (style {:bold 1 :italic 2 :bold-italic 3}) 0)
         f (.deriveFont ^java.awt.Font (.getFont ^Graphics2D (.graphics canvas)) (int s) (float size))]
     (.setFont ^Graphics2D (.graphics canvas) f)
     canvas))
  ([^Canvas canvas ^double size]
   (let [f (.deriveFont ^java.awt.Font (.getFont ^Graphics2D (.graphics canvas)) (float size))]
     (.setFont ^Graphics2D (.graphics canvas) f)
     canvas)))

(defn char-width
  "Returns font width from metrics. Should be called within context."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example "Width of some chars."
                        (with-canvas [c (canvas 10 10)] [(char-width c \W)
                                                         (char-width c \a)]))]}
  ^long [^Canvas canvas chr]
  (.charWidth (.getFontMetrics ^Graphics2D (.graphics canvas)) ^char chr))

(defn font-height
  "Returns font width from metrics. Should be called within context."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example"Height of current font."
                        (with-canvas-> (canvas 10 10)
                          (font-height)))]}
  ^long [^Canvas canvas]
  (.getHeight (.getFontMetrics ^Graphics2D (.graphics canvas))))

(defn text-width
  "Returns width of the provided string. Should be called within context."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example"Size of some string."
                        (with-canvas-> (canvas 10 10)
                          (text-width "Size of some string.")))]}
  ^long [^Canvas canvas ^String txt]
  (.stringWidth (.getFontMetrics ^Graphics2D (.graphics canvas)) txt))

(defn text
  "Draw text for given position and alignment.

  Possible alignments are: `:right`, `:center`, `:left`."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Font attributes" drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (text "Align left" 100 50 :left)
                              (text "Align center" 100 100 :center)
                              (text "Align right" 100 150 :right))))]}
  ([^Canvas canvas s x y align]
   (let [x (long x)
         y (long y)]
     (case align
       :right (let [w (.stringWidth (.getFontMetrics ^Graphics2D (.graphics canvas)) s)]
                (.drawString ^Graphics2D (.graphics canvas) ^String s (- x w) y))
       :center (let [w (/ (.stringWidth (.getFontMetrics ^Graphics2D (.graphics canvas)) s) 2.0)]
                 (.drawString ^Graphics2D (.graphics canvas) ^String s (m/round (- x w)) y))
       :left (.drawString ^Graphics2D (.graphics canvas) ^String s x y)
       (.drawString ^Graphics2D (.graphics canvas) ^String s x y))) 
   canvas)
  ([canvas s x y]
   (text canvas s x y :left)))

;; ### Color

(defn- set-color-with-fn
  "Set color for primitive or background via passed function. You can use:

  * java.awt.Color object
  * clojure2d.math.vector.Vec4 or Vec3 object
  * individual r, g, b (and optional alpha) as integers from 0-255. They are converted to integer and clamped if necessary."
  ([f canvas c]
   (f canvas (c/awt-color c)))
  ([f canvas c a]
   (f canvas (c/awt-color c a)))
  ([f canvas r g b a]
   (f canvas (c/awt-color r g b a)))
  ([f canvas r g b]
   (f canvas (c/awt-color r g b))))

(defn set-awt-color
  "Set color with valid java `Color` object. Use it when you're sure you pass `java.awt.Color` object."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Set color with `java.awt.Color`." drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (set-awt-color java.awt.Color/RED)
                              (rect 50 50 100 100))))]}
  [^Canvas canvas ^java.awt.Color c]
  (.setColor ^Graphics2D (.graphics canvas) c)
  canvas)

(def ^:private alpha-composite-src ^java.awt.Composite (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC))

(defn set-awt-background
  "Set background color. Expects valid `java.awt.Color` object."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Set background with `java.awt.Color`." drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (set-awt-background java.awt.Color/BLUE))))]}
  [^Canvas canvas c]
  (let [^Graphics2D g (.graphics canvas)
        ^Color currc (.getColor g)
        curr-composite (.getComposite g)]
    (push-matrix canvas)
    (reset-matrix canvas)
    (.setComposite g (java.awt.AlphaComposite/getInstance java.awt.AlphaComposite/SRC))
    (set-color-with-fn set-awt-color canvas c)
    (doto g
      (.fillRect 0 0 (.w canvas) (.h canvas))
      (.setColor currc))
    (pop-matrix canvas)
    (.setComposite g curr-composite))
  canvas)

(defn awt-xor-mode
  "Set XOR graphics mode with `java.awt.Color`.

  To revert call [[paint-mode]]."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Set Xor Mode with `java.awt.Color`." drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (awt-xor-mode java.awt.Color/BLACK)
                              (rect 50 50 100 100)
                              (rect 70 70 60 60))))]}
  [^Canvas canvas c]
  (let [^Graphics2D g (.graphics canvas)]
    (.setXORMode g c))
  canvas)

(defn paint-mode
  "Set normal paint mode.

  This is default mode.
  
  See [[gradient-mode]] or [[xor-mode]] for other types."
  {:metadoc/categories #{:draw}}
  [^Canvas canvas]
  (.setPaintMode ^Graphics2D (.graphics canvas))
  canvas)

;; Set color for primitive
(def ^{:doc "Sets current color. Color can be:

* [[vec3]], [[vec4]] with rgb values.
* java.awt.Color object
* keyword with name from 140 HTML color names
* Integer
* r, g, b and optional alpha

See [[clojure2d.color]] namespace for more color functions."
       :metadoc/categories #{:draw}
       :metadoc/examples [(ex/example-snippet "Set color various ways." drawing-snippet :image
                            (fn [canvas]
                              (-> canvas
                                  (set-color 0xaabbcc)
                                  (rect 10 10 40 40)
                                  (set-color :maroon)
                                  (rect 60 60 40 40)
                                  (set-color java.awt.Color/GREEN)
                                  (rect 110 110 40 40)
                                  (set-color 0 111 200 100)
                                  (rect 20 20 160 160)
                                  (set-color (v/vec3 0 100 255))
                                  (rect 160 160 25 25))))]} 
  set-color (partial set-color-with-fn set-awt-color))

;; Set background color
(def ^{:doc "Sets background with given color.

Background can be set with alpha.

See [[set-color]]."
       :metadoc/categories #{:draw}
       :metadoc/examples [(ex/example-snippet "Set background with alpha set." drawing-snippet :image
                            (fn [canvas]
                              (-> canvas
                                  (set-background :maroon 200))))]}
  set-background (partial set-color-with-fn set-awt-background))

;; Set XOR mode
(def
  ^{:doc "Set XOR painting mode."
    :metadoc/categories #{:draw}
    :metadoc/examples [(ex/example-snippet "Set XOR Painting mode" drawing-snippet :image
                         (fn [canvas]
                           (-> canvas
                               (xor-mode :gray)
                               (rect 50 50 100 100)
                               (rect 70 70 60 60))))]}
  xor-mode (partial set-color-with-fn awt-xor-mode))

;;;

(def ^:private ^:const false-list '(false))
(def ^:private ^:const true-list '(true))

(defn filled-with-stroke
  "Draw primitive filled and with stroke.

  Provide two colors, one for fill (`color-filled`), second for stroke (`color-stroke`). `primitive-fn` is a primitive function and `attrs` are function parameters. Do not provide.

  One note: current color is replaced with `color-stroke`."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Draw two primitives" drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (filled-with-stroke :maroon :black crect 100 100 180 180)
                              (filled-with-stroke 0x3344ff :white ellipse 100 100 20 100))))]}
  [canvas color-filled color-stroke primitive-fn & attrs]
  (set-color canvas color-filled)
  (apply primitive-fn canvas (concat attrs false-list))
  (set-color canvas color-stroke)
  (apply primitive-fn canvas (concat attrs true-list)))

;; ### Gradient

(defn gradient-mode
  "Set paint mode to gradient.

  To revert call [[paint-mode]]"
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Set some gradient and fill" drawing-snippet :image
                        (fn [canvas]
                          (-> canvas
                              (gradient-mode 20 20 :maroon 180 180 :black)
                              (ellipse 100 100 190 190))))]}
  ([^Canvas canvas x1 y1 color1 x2 y2 color2]
   (let [gp (java.awt.GradientPaint. x1 y1 (c/awt-color color1) x2 y2 (c/awt-color color2))
         ^Graphics2D g (.graphics canvas)]
     (.setPaint g gp)
     canvas)))

;; ### Image

(defn image
  "Draw an image.

  You can specify position and size of the image. Default it's placed on whole canvas."
  {:metadoc/categories #{:draw}
   :metadoc/examples [(ex/example-snippet "Draw image at given position." drawing-snippet :image
                        (fn [canvas]
                          (let [img (load-image "docs/cockatoo.jpg")]
                            (doseq [^int x (range 0 80 10)]
                              (image canvas img x x (- 200 x x) (- 200 x x))))))]}
  ([^Canvas canvas img x y w h]
   (.drawImage ^Graphics2D (.graphics canvas) (get-image img) x y w h nil)
   canvas)
  ([^Canvas canvas img]
   (image canvas img 0 0 (.w canvas) (.h canvas)))
  ([^Canvas canvas img x y]
   (image canvas img x y (width img) (height img))))

;; ## Display window
;;
;; You can find here a couple of functions which help to display your canvas and build interaction with user.
;; Display window is just a Swing `JFrame` with `java.awt.Canvas` as panel.
;; What is important, window is not a canvas (like it is in Processing) so first you need to create canvas object and then create window displaying it.
;; You can create as many windows as you want. Just name them differently. You can also create window with different size than canvas. Canvas will be rescaled.
;; Windows is not resizable and can't be set to a fullscreen mode (yet)
;; 
;; To show window you call `show-window` function and provide following parameters:
;;
;; * canvas to display
;; * window name (used to identify events)
;; * width and height
;; * canvas refresh rate as frames per second (ex. 25)
;; * optionally callback function which is called just before repainting the canvas (like `draw` in Processing)
;;
;; `show-window` returns a `Window` object containing
;;
;; * `JFrame` object
;; * `active?` atom (see below)
;; *  buffer which is canvas packed into atom (to enable easy canvas replacement)
;; *  `:panel` `java.awt.Canvas` object placed on JFrame (awt toolkit canvas)
;; *  `:fps`
;; *  `:width`
;; *  `:height`
;; *  `:window-name` window name
;;
;; `active?` atom is unique for each window and has value `true` when window is shown and set to `false` when window is closed with default close button.
;; Use this information via `window-active?` function to control (and possibly stop) all activities which refers to related window. For example you may want to cancel all updating canvas processes when user closes window.
;;
;; ### Callback function (aka `draw`)
;;
;; You can define function with three parameters which is called just before repainting canvas. You can use it to simulate Processing `draw` behaviour. Function should accept following parameters:
;;
;; * canvas - canvas to draw on, canvas bound to window will be passed here.
;; * window - window assiociated with function
;; * frame count - current number of calls, starting 0
;; * state - any state you want to pass between calls, `nil` initially
;;
;; Function should return current state, which will be passed to function when called next time.
;;
;; Note: calls to `draw` are wrapped in `with-canvas->` already.
;;
;; ### Events
;;
;; To control user activities you can use two event processing multimethods.
;;
;; * `key-pressed`
;; * `key-released`
;; * `key-typed`
;; * `mouse-event`
;;
;; Each multimethod get awt Event object and global state. Each should return new state.
;;
;; #### Key event: `key-pressed` and other `key-` multimethods
;;
;; Your dispatch value is vector with window name and pressed key (as `char`) which you want to handle.
;; This means you write different method for different key.
;; As a function parameters you get `KeyEvent` object [java.awt.KeyEvent](https://docs.oracle.com/javase/7/docs/api/java/awt/event/KeyEvent.html) and global state attached to the Window.
;;
;; `KeyEvent` is enriched by `KeyEventProto` with functions:
;;
;; * `key-code` - key code mapped to keyword. Eg. `VK_UP` -> `:up` or `VK_CONTROL` -> `:control`
;; * `key-raw` - raw key code value (integer)
;; * `key-char` - char representation (for special keys char is equal `virtual-key` or `0xffff` value
;;
;; If you want to dispatch on special keys (like arrows), dispatch on `(char 0xffff)` or `virtual-key` and read `(key-code e)` to get key pressed.
;;
;; #### Mouse event
;;
;; As as dispatch you use a vector containing window name as a String and mouse event type as a keyword
;; As a parameter you get `MouseEvent` object equipped with `MouseXYProto` protocol [java.awt.MouseEvent](https://docs.oracle.com/javase/7/docs/api/java/awt/event/MouseEvent.html) and global state attached to the Window.
;;
;; Currently implemented types are:
;;
;; * `:mouse-clicked`
;; * `:mouse-dragged`
;; * `:mouse-pressed`
;; * `:mouse-released`
;;
;; To get mouse position call `(mouse-x e)` and `(mouse-y e)` where `e` is MouseEvent object.

;; Extract all keycodes from `KeyEvent` object and pack it to the map
(def ^:private keycodes-map (->> KeyEvent
                                 (ref/reflect)
                                 (:members)
                                 (filter #(instance? clojure.reflect.Field %))
                                 (map #(str (:name %)))
                                 (filter #(re-matches #"VK_.*" %))
                                 (reduce #(assoc %1
                                                 (clojure.lang.Reflector/getStaticField "java.awt.event.KeyEvent" ^String %2)
                                                 (-> %2
                                                     (subs 3)
                                                     (clojure.string/lower-case)
                                                     (keyword))) {})))

(defprotocol MouseXYProto
  "Mouse position."
  (^{:metadoc/categories #{:window :events}} mouse-x [m] "Mouse horizontal position within window. 0 - left side. -1 outside window.")
  (^{:metadoc/categories #{:window :events}} mouse-y [m] "Mouse vertical position. 0 - top, -1 outside window.")
  (^{:metadoc/categories #{:window :events}} mouse-pos [m] "Mouse position as [[Vec2]] type. [0,0] - top left, [-1,-1] outside window."))

(defprotocol MouseButtonProto
  "Get pressed mouse button status."
  (^{:metadoc/categories #{:window :events}} mouse-button [m] "Get mouse pressed button status: :left :right :center or :none"))

(defprotocol KeyEventProto
  "Access to key event data"
  (^{:metadoc/categories #{:window :events}} key-code [e] "Keycode mapped to keyword. See `java.awt.event.KeyEvent` documentation. Eg. `VK_LEFT` is mapped to `:left`.")
  (^{:metadoc/categories #{:window :events}} key-char [e] "Key as char.")
  (^{:metadoc/categories #{:window :events}} key-raw [e] "Raw value for pressed key (as integer)."))

(defprotocol ModifiersProto
  "Get state of keyboard modifiers."
  (^{:metadoc/categories #{:window :events}} control-down? [e] "CONTROL key state as boolean.")
  (^{:metadoc/categories #{:window :events}} alt-down? [e] "ALT key state as boolean.")
  (^{:metadoc/categories #{:window :events}} meta-down? [e] "META key state as boolean.")
  (^{:metadoc/categories #{:window :events}} shift-down? [e] "SHIFT key state as boolean.")
  (^{:metadoc/categories #{:window :events}} alt-gr-down? [e] "ALT-GR key state as boolean."))

(defprotocol PressedProto
  "Key or mouse pressed status."
  (^{:metadoc/categories #{:window :events}} key-pressed? [w] "Any key pressed? (boolean)")
  (^{:metadoc/categories #{:window :events}} mouse-pressed? [w] "Any mouse button pressed? (boolean)"))

;; `Window` type definition, equiped with `get-image` method returning bound canvas' image.
(defrecord Window [^JFrame frame
                   active?
                   buffer
                   ^java.awt.Canvas panel
                   ^double fps
                   ^long w
                   ^long h
                   window-name
                   events]
  ImageProto
  (get-image [_] (get-image @buffer))
  (width [_] w)
  (height [_] h)
  (save [w n] (save-image (get-image @buffer) n) w)
  (convolve [w n] (convolve @buffer n))
  (subimage [_ x y w h] (get-subimage @buffer x y w h))
  (get-pixel [_ x y] (get-pixel @buffer x y))
  PressedProto
  (key-pressed? [_] (:key-pressed? @events))
  (mouse-pressed? [_] (:mouse-pressed? @events))
  ModifiersProto
  (control-down? [_] (:control-down? @events))
  (alt-down? [_] (:alt-down? @events)) 
  (meta-down? [_] (:meta-down? @events))
  (shift-down? [_] (:shift-down? @events))
  (alt-gr-down? [_] (:alt-gr-down? @events))
  KeyEventProto
  (key-code [_] (:key-code @events))
  (key-char [_] (:key-char @events))
  (key-raw [_] (:key-raw @events))
  MouseButtonProto
  (mouse-button [_] (:mouse-button @events))
  MouseXYProto
  (mouse-pos [_]
    (let [^java.awt.Point p (.getMousePosition panel)]
      (if (nil? p)
        (Vec2. -1.0 -1.0)
        (Vec2. (.x p) (.y p)))))
  (mouse-x [_]
    (let [^java.awt.Point p (.getMousePosition panel)]
      (if (nil? p) -1 (.x p))))
  (mouse-y [_]
    (let [^java.awt.Point p (.getMousePosition panel)]
      (if (nil? p) -1 (.y p)))))

;; ### Events function
(extend MouseEvent
  MouseButtonProto
  {:mouse-button #(condp = (.getButton ^MouseEvent %)
                    MouseEvent/BUTTON1 :left
                    MouseEvent/BUTTON2 :center
                    MouseEvent/BUTTON3 :right
                    :none)}
  MouseXYProto
  {:mouse-x #(.getX ^MouseEvent %)
   :mouse-y #(.getY ^MouseEvent %)
   :mouse-pos #(Vec2. (mouse-x %) (mouse-y %))})

(extend KeyEvent
  KeyEventProto
  {:key-code #(keycodes-map (.getKeyCode ^KeyEvent %))
   :key-char #(.getKeyChar ^KeyEvent %)
   :key-raw #(.getKeyCode ^KeyEvent %)})

(extend InputEvent
  ModifiersProto
  {:control-down? #(.isControlDown ^InputEvent %)
   :alt-down? #(.isAltDown ^InputEvent %)
   :meta-down? #(.isMetaDown ^InputEvent %)
   :shift-down? #(.isShiftDown ^InputEvent %)
   :alt-gr-down? #(.isAltGraphDown ^InputEvent %)})

;; ### Window type helper functions

(declare show-window)
(declare close-window)

(defn window-active?
  "Helper function, check if window is active."
  {:metadoc/categories #{:window}
   :metadoc/examples [(ex/example "Check if window is visible."
                        (let [w (show-window)
                              before-closing (window-active? w)]
                          (close-window w)
                          {:before-closing before-closing
                           :after-closing (window-active? w)}))]}
  [^Window window]
  @(.active? window))

(defn mouse-in-window?
  "Check if mouse is inside window."
  {:metadoc/categories #{:window}}
  [window]
  (bool-and (>= ^int (mouse-x window) 0.0)
            (>= ^int (mouse-y window) 0.0)))

;; ### Global state management
;;
;; Global atom is needed to keep current window state. Events don't know what window sends it. The only option is to get component name.

(defonce ^:private global-state (atom {}))

(defn get-state
  "Get state from window"
  [^Window window]
  (@global-state (.window-name window)))

(defn- change-state! 
  "Change state for Window (by name)"
  [window-name state] 
  (swap! global-state assoc window-name state)
  (@global-state window-name))

(defn- clear-state!
  "Clear state for Window (by name)"
  [window-name]
  (swap! global-state dissoc window-name))

(defn set-state!
  "Changle global state for Window."
  [^Window w state]
  (change-state! (.window-name w) state))

;; Private method which extracts the name of your window (set when `show-window` is called).

(defn- event-window-name
  "Returns name of the component. Used to dispatch events."
  [^ComponentEvent e]
  (.getName ^Component (.getComponent e)))

(def ^:const virtual-key (char 0xffff))

;; Multimethod used to process pressed key
(defmulti key-pressed (fn [^KeyEvent e state] [(event-window-name e) (.getKeyChar e)]))
;; Do nothing on default
(defmethod key-pressed :default [_ s]  s)

;; Multimethod used to process released key
(defmulti key-released (fn [^KeyEvent e state] [(event-window-name e) (.getKeyChar e)]))
;; Do nothing on default
(defmethod key-released :default [_ s]  s)

;; Multimethod used to process typed key
(defmulti key-typed (fn [^KeyEvent e state] [(event-window-name e) (.getKeyChar e)]))
;; Do nothing on default
(defmethod key-typed :default [_ s]  s)

;; Multimethod use to processed key events (any key event)

(def key-event-map {KeyEvent/KEY_PRESSED  :key-pressed
                    KeyEvent/KEY_RELEASED :key-released
                    KeyEvent/KEY_TYPED    :key-typed})

(defmulti key-event (fn [^KeyEvent e state] [(event-window-name e) (key-event-map (.getID e))]))
;; Do nothing on default
(defmethod key-event :default [_ s] s)

;; Map Java mouse event names onto keywords
(def mouse-event-map {MouseEvent/MOUSE_CLICKED  :mouse-clicked
                      MouseEvent/MOUSE_DRAGGED  :mouse-dragged
                      MouseEvent/MOUSE_PRESSED  :mouse-pressed
                      MouseEvent/MOUSE_RELEASED :mouse-released
                      MouseEvent/MOUSE_MOVED    :mouse-moved})

;; Multimethod used to processed mouse events
(defmulti mouse-event (fn [^MouseEvent e state] [(event-window-name e) (mouse-event-map (.getID e))]))
;; Do nothing on default
(defmethod mouse-event :default [_ s] s)

;; Event adapter objects.

;; General function which manipulates state and calls proper event multimethod

(defn- process-state-and-event 
  "For given event call provided multimethod passing state. Save new state."
  [ef e]
  (let [window-name (event-window-name e)]
    (change-state! window-name (ef e (@global-state window-name)))))

;; Key
(def ^:private key-char-processor (proxy [KeyAdapter] []
                                    (keyPressed [e] (process-state-and-event key-pressed e))
                                    (keyReleased [e] (process-state-and-event key-released e))
                                    (keyTyped [e] (process-state-and-event key-typed e))))

(def ^:private key-event-processor (proxy [KeyAdapter] []
                                     (keyPressed [e] (process-state-and-event key-event e))
                                     (keyReleased [e] (process-state-and-event key-event e))
                                     (keyTyped [e] (process-state-and-event key-event e))))

;; Mouse
(def ^:private mouse-processor (proxy [MouseAdapter] []
                                 (mouseClicked [e] (process-state-and-event mouse-event e))
                                 (mousePressed [e] (process-state-and-event mouse-event e))
                                 (mouseReleased [e] (process-state-and-event mouse-event e))))

;; Mouse drag and move
(def ^:private mouse-motion-processor (proxy [MouseMotionAdapter] []
                                        (mouseDragged [e] (process-state-and-event mouse-event e))
                                        (mouseMoved [e] (process-state-and-event mouse-event e))))

;;

(defn- add-events-state-processors
  "Add listeners for mouse and keyboard to store state"
  [^Window w]
  (let [mouse-events (proxy [MouseAdapter] []
                       (mousePressed [e] (swap! (.events w) assoc
                                                :mouse-pressed? true
                                                :mouse-button (mouse-button e)
                                                :control-down? (control-down? e)
                                                :alt-down? (alt-down? e)
                                                :meta-down? (meta-down? e)
                                                :shift-down? (shift-down? e)
                                                :alt-gr-down? (alt-gr-down? e)))
                       (mouseReleased [_] (swap! (.events w) assoc :mouse-pressed? false)))
        key-events (proxy [KeyAdapter] []
                     (keyPressed [e] (swap! (.events w) assoc
                                            :key-pressed? true
                                            :key-code (key-code e)
                                            :key-char (key-char e)
                                            :key-raw (key-raw e)
                                            :control-down? (control-down? e)
                                            :alt-down? (alt-down? e)
                                            :meta-down? (meta-down? e)
                                            :shift-down? (shift-down? e)
                                            :alt-gr-down? (alt-gr-down? e)))
                     (keyReleased [_] (swap! (.events w) assoc :key-pressed? false)))]
    (doto ^java.awt.Canvas (.panel w)
      (.addMouseListener mouse-events)
      (.addKeyListener key-events))))

(defn close-window
  "Close window programatically"
  {:metadoc/categories #{:window}}
  [window]
  (.dispatchEvent ^JFrame (:frame window) (java.awt.event.WindowEvent. (:frame window) java.awt.event.WindowEvent/WINDOW_CLOSING)))

;; ### Frame machinery functions
;;
;; Window is JFrame with panel (as java.awt.Canvas object) which is used to draw clojure2d canvas on it.

(defn- create-panel
  "Create panel which displays canvas. Attach mouse events, give a name (same as window), set size etc."
  [buffer windowname width height]
  (let [panel (java.awt.Canvas.)
        d (Dimension. width height)]
    (doto panel
      (.setName windowname)
      (.addMouseListener mouse-processor)
      (.addKeyListener key-char-processor)
      (.addKeyListener key-event-processor)
      (.addMouseMotionListener mouse-motion-processor)
      (.setFocusTraversalKeysEnabled false)
      (.setIgnoreRepaint true)
      (.setPreferredSize d)
      (.setBackground Color/black))))

;; Function used to close and dispose window. As a side effect `active?` atom is set to false and global state for window is cleared.

(defn- close-window-fn
  "Close window frame"
  [^JFrame frame active? windowname]
  (reset! active? false)
  (clear-state! windowname)
  (.dispose frame))

;; Create lazy list of icons to be loaded by frame
(def ^:private window-icons (map #(.getImage (ImageIcon. (resource (str "icons/i" % ".png")))) [10 16 20 24 30 32 40 44 64 128]))

(defn- build-frame
  "Create JFrame object, create and attach panel and do what is needed to show window. Attach key events and closing event."
  [^JFrame frame ^java.awt.Canvas panel active? windowname width height]
  (let [closer (proxy [WindowAdapter] []
                 (windowClosing [^WindowEvent e] (close-window-fn frame active? windowname)))]
    (doto frame
      (.setLayout (java.awt.BorderLayout.))
      (.setIconImages window-icons)
      (.add panel)
      (.setSize (Dimension. width height))
      (.invalidate)
      (.setResizable false)
      (.pack)
      (.setDefaultCloseOperation JFrame/DO_NOTHING_ON_CLOSE)
      (.addWindowListener closer)
      (.setName windowname)
      (.setTitle windowname)
      (.setBackground Color/white)
      (.setLocationRelativeTo nil)
      (.setVisible true))
    (doto panel
      (.requestFocus)
      (.createBufferStrategy 2))))

;; Another internal function repaints panel with frames per seconds rate. If `draw` function is passed it is called before rapaint action. Function runs infinitely until window is closed. The cycle goes like this:
;;
;; * call `draw` function if available, pass canvas, current frame number and current state (`nil` at start)
;; * repaint
;; * wait
;; * check if window is still displayed and recur incrementing frame number and pass state for another run.

(defn- repaint
  "Draw buffer on panel using `BufferStrategy` object."
  [^java.awt.Canvas panel ^Canvas canvas hints]
  (let [^BufferStrategy strategy (.getBufferStrategy panel)
        ^BufferedImage b (.buffer canvas)]
    (when strategy
      (loop []
        (loop []
          (let [^Graphics2D graphics-context (.getDrawGraphics strategy)]
            (when hints (set-rendering-hints graphics-context hints))
            (.drawImage graphics-context b 0 0 (.getWidth panel) (.getHeight panel) nil) ;; sizes of panel???
            (.dispose graphics-context))
          (when (.contentsRestored strategy) (recur)))
        (.show strategy)
        (when (.contentsLost strategy) (recur))))
    (.sync (Toolkit/getDefaultToolkit))))

(deftype WithExceptionT [exception? value])

(defn- refresh-screen-task-safety
  "Repaint canvas on window with set FPS.

  * Input: frame, active? atom, function to run before repaint, canvas and sleep time."
  [^Window window draw-fun draw-state hints]
  (let [stime (/ 1000.0 ^double (.fps window))]
    (loop [cnt (long 0)
           result draw-state
           t (System/nanoTime)
           overt 0.0]
      (let [^WithExceptionT new-result (try
                                         (WithExceptionT. false (when (and draw-fun @(.active? window)) ; call draw only when window is active and draw-fun is defined
                                                                  (with-canvas-> @(.buffer window)
                                                                    (draw-fun window cnt result))))
                                         (catch Throwable e
                                           (.printStackTrace e)
                                           (WithExceptionT. true e)))] 
        (let [at (System/nanoTime)
              diff (/ (- at t) 1.0e6)
              delay (- stime diff overt)]
          (when (pos? delay)
            (Thread/sleep (long delay) (int (* 1000000.0 (m/frac delay)))))
          (repaint (.panel window) @(.buffer window) hints)
          (when (bool-and @(.active? window) (not (.exception? new-result)))
            (recur (inc cnt)
                   (.value new-result)
                   (System/nanoTime)
                   (if (pos? delay) (- (/ (- (System/nanoTime) at) 1.0e6) delay) 0.0))))))))


(defn- refresh-screen-task-speed
  "Repaint canvas on window with set FPS.

  * Input: frame, active? atom, function to run before repaint, canvas and sleep time."
  [^Window window draw-fun draw-state hints]
  (let [stime (/ 1000.0 ^double (.fps window))]
    (with-canvas [canvas @(.buffer window)]
      (loop [cnt (long 0)
             result draw-state
             t (System/nanoTime)
             overt 0.0]
        (let [^WithExceptionT new-result (try
                                           (WithExceptionT. false (when (and draw-fun @(.active? window)) ; call draw only when window is active and draw-fun is defined
                                                                    (draw-fun canvas window cnt result)))
                                           (catch Throwable e
                                             (when @(.active? window) (.printStackTrace e))
                                             (WithExceptionT. true e)))] 
          (let [at (System/nanoTime)
                diff (/ (- at t) 1.0e6)
                delay (- stime diff overt)]
            (when (pos? delay)
              (Thread/sleep (long delay) (int (* 1000000.0 (m/frac delay)))))
            (repaint (.panel window) @(.buffer window) hints)
            (when (bool-and @(.active? window) (not (.exception? new-result)))
              (recur (inc cnt)
                     (.value new-result)
                     (System/nanoTime)
                     (if (pos? delay) (- (/ (- (System/nanoTime) at) 1.0e6) delay) 0.0)))))))))

;; You may want to replace canvas to the other one. To make it pass result of `show-window` function and new canvas.
;; Internally it just resets buffer atom for another canvas.
;; See examples/ex01_events.clj to see how it works.

(defn replace-canvas
  "Replace canvas in window.

  * Input: window and new canvas
  * Returns canvas"
  [^Window window canvas]
  (reset! (.buffer window) canvas))

;; You may want to extract canvas bound to window

(defn get-canvas
  "Returns canvas bound to `window`."
  [^Window window]
  @(.buffer window))

;; Finally function which creates and displays window. Function creates window's visibility status (`active?` atom), buffer as atomized canvas, creates frame, creates refreshing task (repainter) and shows window.

(declare to-hex)

(defn show-window
  "Show window with width/height, name and required fps of refresh. Optionally pass callback function.

  * Input: canvas, window name, width (defalut: canvas width), height (default: canvas height), frames per seconds (default: 60), (optional) `draw` function.
  * Returns `Window` value

  As parameters you can provide a map with folowing keys:

  * :canvas
  * :window-name
  * :w
  * :h
  * :fps
  * :draw-fn
  * :state
  * :draw-state
  * :setup
  * :hint - rendering hint for display
  * :refresher - safe (default) or fast"
  {:metadoc/categories #{:window}}
  ([canvas wname width height fps draw-fun state draw-state setup hint refresher]
   (let [active? (atom true)
         buffer (atom canvas)
         frame (JFrame.)
         panel (create-panel buffer wname width height)
         window (->Window frame
                          active?
                          buffer
                          panel
                          fps
                          width
                          height
                          wname
                          (atom {}))
         setup-state (when setup (with-canvas-> canvas
                                   (setup window))) 
         refresh-screen-task (if (= refresher :fast)
                               refresh-screen-task-speed
                               refresh-screen-task-safety)]
     (SwingUtilities/invokeAndWait #(build-frame frame panel active? wname width height))
     (add-events-state-processors window)
     (change-state! wname state)
     (future (refresh-screen-task window draw-fun (or setup-state draw-state) (when hint (get-rendering-hints hint :mid))))
     window))
  ([canvas wname]
   (show-window canvas wname nil))
  ([canvas wname draw-fn]
   (show-window canvas wname 60 draw-fn))
  ([canvas wname fps draw-fn]
   (show-window canvas wname (width canvas) (height canvas) fps draw-fn nil nil nil nil nil))
  ([canvas wname w h fps]
   (show-window canvas wname w h fps nil nil nil nil nil nil))
  ([canvas wname w h fps draw-fun]
   (show-window canvas wname w h fps draw-fun nil nil nil nil nil))
  ([{:keys [canvas window-name w h fps draw-fn state draw-state setup hint refresher]
     :or {canvas (canvas 200 200)
          window-name (str "Clojure2D - " (to-hex (rand-int (Integer/MAX_VALUE)) 8))
          fps 60
          draw-fn nil
          state nil
          draw-state nil
          setup nil
          hint nil
          refresher nil}}]
   (show-window canvas window-name (or w (width canvas)) (or h (height canvas)) fps draw-fn state draw-state setup hint refresher))
  ([] (show-window {})))

;; ## Utility functions
;;
;; Now we have a part with some utilities (I had no idea where to put them).

(defn to-hex
  "Return hex value of given number, padded with leading zeroes if given length"
  ([n]
   (format "%X" n))
  ([n pad]
   (format (str "%0" pad "X") n)))

;; ## Date/Time functions

(defn ^{:metadoc/categories #{:dt}} year ^long [] (.get ^Calendar (Calendar/getInstance) Calendar/YEAR))
(defn ^{:metadoc/categories #{:dt}} month ^long [] (inc ^int (.get ^Calendar (Calendar/getInstance) Calendar/MONTH)))
(defn ^{:metadoc/categories #{:dt}} day ^long [] (.get ^Calendar (Calendar/getInstance) Calendar/DAY_OF_MONTH))
(defn ^{:metadoc/categories #{:dt}} hour ^long [] (.get ^Calendar (Calendar/getInstance) Calendar/HOUR_OF_DAY))
(defn ^{:metadoc/categories #{:dt}} minute ^long [] (.get ^Calendar (Calendar/getInstance) Calendar/MINUTE))
(defn ^{:metadoc/categories #{:dt}} sec ^long [] (.get ^Calendar (Calendar/getInstance) Calendar/SECOND))
(defn ^{:metadoc/categories #{:dt}} millis ^long [] (System/currentTimeMillis))
(defn ^{:metadoc/categories #{:dt}} nanos ^long [] (System/nanoTime))
(defn ^{:metadoc/categories #{:dt}} datetime
  "Date time values in the array. Optional parameter :vector or :hashmap (default) to indicate what to return."
  ([type-of-array]
   (let [y (year) m (month) d (day)
         h (hour) mi (minute) s (sec) mil (millis)
         n (nanos)]
     (if (= type-of-array :vector)
       [y m d h mi s mil n]
       {:year y :month m :day d :hour h :minute mi :second s :millis mil :nanos n :sec s})))
  ([] (datetime :hashmap)))

;; ## Load bytes

(defn load-bytes
  "Load file and return byte array."
  [file]
  (with-open [in (clojure.java.io/input-stream file)
              out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy in out)
    (.toByteArray out)))

;; ## Session management
;;
;; Couple session management functions. Generally used to generate unique identifier, log or generate filename.
;; Use cases are:
;;
;; * Log your actions to the file. Simply writes text messages.
;; * Save your images under unique and sequenced filenames

(defn make-counter
  "Create counter function, each call returns next number."
  ([^long v]
   (let [tick (atom (dec v))]
     (fn [] (swap! tick #(inc ^long %)))))
  ([]
   (make-counter 0)))

;; Store date format in variable
(def ^:private ^java.text.SimpleDateFormat simple-date-format (java.text.SimpleDateFormat. "yyyyMMddHHmmss"))
(def ^:private ^java.text.SimpleDateFormat simple-date-format-full (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss"))

;; Following block defines session related functions.
;; Session is a type of:
;;
;; * logger - file writer
;; * name - session identifiers as vector with formatted current date and hash of this date
;; * counter - used to create sequence filenames for session
;;
;; Session is encapsulated in agent and is global for library
;;
;; Example:
;;
;; * `(get-session-name) => nil`
;; * `(make-session) => ["20170123235332" "CD88D0C5"]`
;; * `(get-session-name) => ["20170123235332" "CD88D0C5"]`
;; * `(next-filename "folder/" ".txt") => "folder/CD88D0C5_000000.txt"`
;; * `(next-filename "folder/" ".txt") => "folder/CD88D0C5_000001.txt"`
;; * `(close-session) => nil`
;; * `(get-session-name) => nil`
;; * `(make-session) => ["20170123235625" "CD8B7204"]`

;; Session values are packed into the type
(defrecord SessionType [logger
                        name
                        counter])

;; Session is stored in agent
(defonce ^:private session-agent (agent (map->SessionType {})))

;; Logging to file is turned off by default.
(def ^:dynamic *log-to-file* false)

(defn- close-session-fn
  "Close current session"
  [s]
  (let [^java.io.Writer o (:logger s)]
    (when-not (nil? o)
      (.flush o)
      (.close o)))
  (map->SessionType {}))

(defn- make-logger-fn
  "Create writer for logger"
  [session-name]
  (let [fname (str "log/" (first session-name) ".log")]
    (make-parents fname) 
    (let [^java.io.Writer no (writer fname :append true)]
      (.write no (str "Session id: " (second session-name) (System/lineSeparator) (System/lineSeparator)))
      no)))

(defn- make-session-name
  "Create unique session name based on current time. Result is a vector with date and hash represented as hexadecimary number."
  []
  (let [date (java.util.Date.)]
    [(.format simple-date-format date) (to-hex (hash date) 8)]))

(defn- make-session-fn 
  "Create session"
  [^SessionType s]
  (close-session-fn s)
  (let [nname (make-session-name)
        writer (when *log-to-file* (make-logger-fn nname))]
    (->SessionType writer nname (make-counter 0))))

(defn make-session
  "Create session via agent"
  {:metadoc/categories #{:session}}
  []
  (send session-agent make-session-fn)
  (await-for 1000 session-agent)
  (:name @session-agent))

(defn close-session
  "Close session via agent"
  {:metadoc/categories #{:session}}
  []
  (send session-agent close-session-fn)
  (await-for 1000 session-agent))

(defn ensure-session
  "Ensure that session is active (create one if not"
  []
  {:metadoc/categories #{:session}}
  (when (nil? (:name @session-agent))
    (make-session)))

(defn session-name
  "Get session name"
  {:metadoc/categories #{:session}}
  []
  (ensure-session)
  (:name @session-agent))

(defn next-filename
  "Create next unique filename based on session"
  {:metadoc/categories #{:session}}
  ([prefix]
   (ensure-session)
   (let [s @session-agent]
     (str prefix (second (:name s)) "_" (format "%06d" ((:counter s))))))
  ([prefix suffix]
   (str (next-filename prefix) suffix)))

(defn log
  "Log message to file or console"
  {:metadoc/categories #{:session}}
  [message]
  (let [to-log (str (.format simple-date-format-full (java.util.Date.)) ": " message (System/lineSeparator))]
    (ensure-session)
    (if *log-to-file*
      (send session-agent (fn [s]
                            (let [^java.io.Writer o (or (:logger s) (make-logger-fn (:name s)))]
                              (.write o to-log)
                              (.flush o)
                              (->SessionType o (:name s) (:counter s)))))
      (println to-log))))

;;
;; Mutable array2d creator helper
;;

(defn int-array-2d
  "Create 2d int array getter and setter methods. Array is mutable!"
  [^long sizex ^long sizey]
  (let [buff (int-array (* sizex sizey))]
    [#(aget ^ints buff (+ ^long %1 (* sizex ^long %2)))
     #(aset ^ints buff (+ ^long %1 (* sizex ^long %2)) ^int %3)]))

(defn long-array-2d
  "Create 2d int array getter and setter methods. Array is mutable!"
  [^long sizex ^long sizey]
  (let [buff (long-array (* sizex sizey))]
    [#(aget ^longs buff (+ ^long %1 (* sizex ^long %2)))
     #(aset ^longs buff (+ ^long %1 (* sizex ^long %2)) ^long %3)]))

(defn double-array-2d
  "Create 2d int array getter and setter methods. Array is mutable!"
  [^long sizex ^long sizey]
  (let [buff (double-array (* sizex sizey))]
    [#(aget ^doubles buff (+ ^long %1 (* sizex ^long %2)))
     #(aset ^doubles buff (+ ^long %1 (* sizex ^long %2)) ^double %3)]))

;;

