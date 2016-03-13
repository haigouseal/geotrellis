package geotrellis.spark.tiling

import geotrellis.proj4._
import geotrellis.proj4.util.UTM
import geotrellis.raster._
import geotrellis.spark._
import geotrellis.vector._

object ZoomedLayoutScheme {
  val EARTH_RADIUS = 6378137 // Use what gdal2tiles uses.
  val EARTH_CIRCUMFERENCE = 2 * math.Pi * EARTH_RADIUS

  val DEFAULT_TILE_SIZE = 256
  val DEFAULT_RESOLUTION_THRESHOLD = 0.1

  def apply(crs: CRS, tileSize: Int = DEFAULT_TILE_SIZE, resolutionThreshold: Double = DEFAULT_RESOLUTION_THRESHOLD) =
    new ZoomedLayoutScheme(crs, tileSize, resolutionThreshold)
}

/** Layout for zoom levels based off of a power-of-2 scheme,
  * used in Leaflet et al.
  *
  * @param  crs                      The CRS this zoomed layout scheme will be using
  * @param  tileSize                 The size of each tile in this layout scheme
  * @param  resolutionThreshold      The percentage difference between a cell size and a zoom level
  *                                  and the resolution difference between that zoom level and the next
  *                                  that is tolerated to snap to the lower-resolution zoom level.
  *                                  For example, if this paramter is 0.1, that means we're willing to downsample
  *                                  rasters with a higher resolution in order to fit them to some zoom level Z,
  *                                  if the difference is resolution is less than or equal to 10% the difference
  *                                  between the resolutions of zoom level Z and zoom level Z+1.
  * */
class ZoomedLayoutScheme(val crs: CRS, val tileSize: Int, resolutionThreshold: Double) extends LayoutScheme {
  import ZoomedLayoutScheme.EARTH_CIRCUMFERENCE

  /** This will calcluate the closest zoom level based on the resolution in a UTM zone containing the point.
    * The calculated zoom level is up to some percentage (determined by the resolutionThreshold) less resolute then the cellSize.
    * If the cellSize is more resolute than that threshold's allowance, this will return the next zoom level up.
    */
  def zoom(x: Double, y: Double, cellSize: CellSize): Int = {
    val ll1 = Point(x + cellSize.width, y + cellSize.height).reproject(crs, LatLng)
    val ll2 = Point(x, y).reproject(crs, LatLng)
    // Try UTM zone, if not, use web mercator.
    val dist: Double =
      if(UTM.inValidZone(ll1.y)) {
        val utmCrs = UTM.getZoneCrs(ll1.x, ll1.y)
        val (p1, p2) = (ll1.reproject(LatLng, utmCrs), ll2.reproject(LatLng, utmCrs))

        math.max(math.abs(p1.x - p2.x), math.abs(p1.y - p2.y))
      } else {
        // Use Haversine distance formula
        val p = math.Pi / 180
        val a = 0.5 - math.cos((ll2.y - ll1.y) * p) / 2 + math.cos(ll1.y * p) * math.cos(ll2.y * p) * (1 - math.cos((ll2.x - ll1.x) * p)) / 2

        2 * EARTH_CIRCUMFERENCE * math.asin(math.sqrt(a))
      }
    val z = (math.log(EARTH_CIRCUMFERENCE / (dist * tileSize)) / math.log(2)).toInt
    val zRes = EARTH_CIRCUMFERENCE / (math.pow(2, z) * tileSize)
    val nextZRes = EARTH_CIRCUMFERENCE / (math.pow(2, z + 1) * tileSize)
    val delta = zRes - nextZRes
    val diff = zRes - dist

    val zoom =
      if(diff / delta > resolutionThreshold) {
        z.toInt + 1
      } else {
        z.toInt
      }

    zoom
  }

  private def tileCols(level: Int): Int = math.pow(2, level).toInt
  private def tileRows(level: Int): Int = math.pow(2, level).toInt

  def levelFor(extent: Extent, cellSize: CellSize): LayoutLevel = {
    val worldExtent = crs.worldExtent
    val l =
      zoom(extent.xmin, extent.ymin, cellSize)

    levelForZoom(worldExtent, l)
  }

  def levelForZoom(id: Int): LayoutLevel =
    levelForZoom(crs.worldExtent, id)

  def levelForZoom(worldExtent: Extent, id: Int): LayoutLevel = {
    if(id < 1)
      sys.error("TMS Tiling scheme does not have levels below 1")
    LayoutLevel(id, LayoutDefinition(worldExtent, TileLayout(tileCols(id), tileRows(id), tileSize, tileSize)))
  }

  def zoomOut(level: LayoutLevel) = {
    val layout = level.layout
    new LayoutLevel(
      zoom = level.zoom - 1,
      layout = LayoutDefinition(
        extent = layout.extent,
        tileLayout = TileLayout(
          layout.tileLayout.layoutCols / 2,
          layout.tileLayout.layoutRows / 2,
          layout.tileLayout.tileCols,
          layout.tileLayout.tileRows
        )
      )
    )
  }

  def zoomIn(level: LayoutLevel) = {
    val layout = level.layout
    new LayoutLevel(
      zoom = level.zoom + 1,
      layout = LayoutDefinition(
        extent = layout.extent,
        tileLayout = TileLayout(
          layout.tileLayout.layoutCols * 2,
          layout.tileLayout.layoutRows * 2,
          layout.tileLayout.tileCols,
          layout.tileLayout.tileRows
        )
      )
    )
  }
}
