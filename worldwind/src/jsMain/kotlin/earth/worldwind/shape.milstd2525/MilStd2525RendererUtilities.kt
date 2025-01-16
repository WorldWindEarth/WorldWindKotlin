@file:JsQualifier("armyc2.c2sd.renderer.utilities")
@file:Suppress("unused", "FunctionName")

package earth.worldwind.shape.milstd2525

import org.w3c.dom.Image
import kotlin.js.Date

external interface IPointConversion {
    fun PixelsToGeo(pixel: armyc2.c2sd.graphics2d.Point2D): armyc2.c2sd.graphics2d.Point2D
    fun GeoToPixels(coord: armyc2.c2sd.graphics2d.Point2D): armyc2.c2sd.graphics2d.Point2D
}

open external class PointConverter3D(controlLong: Number, controlLat: Number, scale: Number) : IPointConversion {
    override fun PixelsToGeo(pixel: armyc2.c2sd.graphics2d.Point2D): armyc2.c2sd.graphics2d.Point2D
    override fun GeoToPixels(coord: armyc2.c2sd.graphics2d.Point2D): armyc2.c2sd.graphics2d.Point2D
}

/**
 * @param symbolID - 15 character mil-std code
 * @param uniqueID
 * @param coordinates - array of Point2D
 * @param modifiers
 */
open external class MilStdSymbol(
    symbolID: String, uniqueID: String?,
    coordinates: java.util.ArrayList<armyc2.c2sd.graphics2d.Point2D>,
    modifiers: Map<String, String>?
) {
    /**
     * @param modifier like ModifiersTG.T_UniqueDesignation1
     * @param index only applies to X, AM & AN
     */
    fun setModifier(modifier: String, value: String, index: Number? = definedExternally)
    fun setAltitudeMode(value: String?)
    fun getSymbolShapes(): java.util.ArrayList<ShapeInfo>
    fun getModifierShapes(): java.util.ArrayList<ShapeInfo>
    fun getLineColor(): Color
}

open external class ShapeInfo {
    fun getShapeType(): Number
    fun getLineColor(): Color?
    fun getFillColor(): Color?
    fun getPolylines(): java.util.ArrayList<java.util.ArrayList<armyc2.c2sd.graphics2d.Point2D>>
    fun getModifierStringPosition(): armyc2.c2sd.graphics2d.Point2D?
    fun getGlyphPosition(): armyc2.c2sd.graphics2d.Point2D?
    fun getModifierString(): String
    fun getModifierStringAngle(): Number
    fun getStroke(): armyc2.c2sd.graphics2d.Stroke?

    companion object {
        val SHAPE_TYPE_POLYLINE: Number
        val SHAPE_TYPE_FILL: Number
        val SHAPE_TYPE_MODIFIER: Number
        val SHAPE_TYPE_MODIFIER_FILL: Number
        val SHAPE_TYPE_UNIT_FRAME: Number
        val SHAPE_TYPE_UNIT_FILL: Number
        val SHAPE_TYPE_UNIT_SYMBOL1: Number
        val SHAPE_TYPE_UNIT_SYMBOL2: Number
        val SHAPE_TYPE_UNIT_DISPLAY_MODIFIER: Number
        val SHAPE_TYPE_UNIT_ECHELON: Number
        val SHAPE_TYPE_UNIT_AFFILIATION_MODIFIER: Number
        val SHAPE_TYPE_UNIT_HQ_STAFF: Number
        val SHAPE_TYPE_TG_SP_FILL: Number
        val SHAPE_TYPE_TG_SP_FRAME: Number
        val SHAPE_TYPE_TG_Q_MODIFIER: Number
        val SHAPE_TYPE_TG_SP_OUTLINE: Number
        val SHAPE_TYPE_SINGLE_POINT_OUTLINE: Number
        val SHAPE_TYPE_UNIT_OUTLINE: Number
    }
}

open external class ImageInfo {
    fun getCenterPoint(): armyc2.c2sd.renderer.so.Point
    fun getSymbolBounds(): armyc2.c2sd.renderer.so.Rectangle

    /**
     * @returns {HTML5 canvas} HTML5 canvas
     */
    fun getImage(): Image
}

open external class Color(R: Number, G: Number, B: Number, A: Number = definedExternally) {
    fun convert(integer: Int): String
    fun getAlpha(): Number
    fun getRed(): Number
    fun getGreen(): Number
    fun getBlue(): Number
    fun toRGB(): Number
    fun toARGB(): Number

    /**
     * A hex string in the format of AARRGGBB
     * @param withAlpha Optional, default is true. If set to false,
     * will return a hex string without alpha values.
     */
    fun toHexString(withAlpha : Boolean): String

    /**
     * A KML Formatted hex string is in the format of AABBGGRR
     */
    fun toKMLHexString(): String

    companion object {
        val white: Color
        val WHITE: Color
        val lightGray: Color
        val LIGHT_GRAY: Color
        val gray: Color
        val GRAY: Color
        val darkGray: Color
        val DARK_GRAY: Color
        val black: Color
        val BLACK: Color
        val red: Color
        val RED: Color
        val pink: Color
        val PINK: Color
        val orange: Color
        val ORANGE: Color
        val yellow: Color
        val YELLOW: Color
        val green: Color
        val GREEN: Color
        val magenta: Color
        val MAGENTA: Color
        val cyan: Color
        val CYAN: Color
        val blue: Color
        val BLUE: Color

        /**
         * @param color int value of a color
         * @return 0 - 255, alpha value
         */
        fun getAlphaFromColor(color: Number): Number

        /**
         * @param color int value of a color
         * @return 0 - 255, Red value
         */
        fun getRedFromColor(color: Number): Number

        /**
         * @param color int value of a color
         * @return 0 - 255, Green value
         */
        fun getGreenFromColor(color: Number): Number

        /**
         * @param color int value of a color
         * @return 0 - 255, Blue value
         */
        fun getBlueFromColor(color: Number): Number

        /**
         * @param hexValue - String representing hex value
         * (formatted "0xRRGGBB" i.e. "0xFFFFFF")
         * OR
         * formatted "0xAARRGGBB" i.e. "0x00FFFFFF" for a color with an alpha value
         * I will also put up with "RRGGBB" and "AARRGGBB" without the starting "0x"
         */
        fun getColorFromHexString(hexValue: String): Color

        fun rgbToHexString(r: Number, g: Number, b: Number, a: Number): String
    }
}

external object RendererUtilities {
    fun fontsLoaded(): Boolean
    /**
     * @param color like "#FFFFFF"
     * @param forceRGB, return value drops any alpha value
     * and is formatted like "#RRGGBB"
     */
    fun getIdealOutlineColor(color: String, forceRGB: Boolean): String
}

external object RendererSettings {
    /**
     * There will be no background for text
     * NOTE: not supported
     */
    val TextBackgroundMethod_NONE: Number

    /**
     * There will be a colored box behind the text
     * NOTE: not implemented
     */
    val TextBackgroundMethod_COLORFILL: Number

    /**
     * There will be an adjustable outline around the text (expensive)
     * Outline width of 4 is recommended.
     */
    val TextBackgroundMethod_OUTLINE: Number

    /**
     * Was quick in Java.  Some gains in IE 10+ if outline width is set to 1.
     * NOTE: only implemented for Units
     */
    val TextBackgroundMethod_OUTLINE_QUICK: Number

    /**
     * 2525Bch2 and USAS 11-12 symbology
     */
    val Symbology_2525B: Number

    /**
     * 2525Bch2 and USAS 13-14 symbology
     * @deprecated use Symbology_2525B
     */
    val Symbology_2525Bch2_USAS_13_14: Number

    /**
     * 2525C, which includes 2525Bch2 & USAS 13/14
     */
    val Symbology_2525C: Number

    val OperationalConditionModifierType_SLASH: Number
    val OperationalConditionModifierType_BAR: Number

    /**
     * Library version
     */
    fun getVersion(): String

    /**
     * Set operational condition modifiers to be rendered as bars(1) or slashes(0)
     * @param operationalConditionModifierType like [OperationalConditionModifierType_BAR]
     */
    fun setOperationalConditionModifierType(operationalConditionModifierType: Number)

    /**
     * Get operational condition modifiers to be rendered as bars(1) or slashes(0)
     * @return Operational Condition Modifier Type like [OperationalConditionModifierType_BAR]
     */
    fun getOperationalConditionModifierType(): Number

    /**
     * Controls what symbols are supported.
     * Set this before loading the renderer.
     * @param standard like RendererSettings.Symbology_2525B
     */
    fun setSymbologyStandard(standard: Number)

    /**
     * Current symbology standard
     */
    fun getSymbologyStandard(): Number

    /**
     * set device DPI (default 90)
     */
    fun setDeviceDPI(value: Number)

    /**
     * returns user defined device DPI (default 90)
     */
    fun getDeviceDPI(): Number

    /**
     * For lines symbols with "decorations" like FLOT or LOC, when points are
     * too close together, we will start dropping points until we get enough
     * space between 2 points to draw the decoration.  Without this, when points
     * are too close together, you run the chance that the decorated line will
     * look like a plain line because there was no room between points to
     * draw the decoration.
     */
    fun setUseLineInterpolation(value: Boolean)

    /**
     * Returns the current setting for Line Interpolation.
     */
    fun getUseLineInterpolation(): Boolean

    /**
     *
     * @param name like "Arial" or "Arial, sans-serif" so a backup is
     * available in case 'Arial' is not present.
     * @param size like 12
     * @param style like "bold"
     * @param kmlLabelScale Only set if you want to scale the KML label font. (default 1.0)
     */
    fun setMPModifierFont(
        name: String, size: Number, style: String,
        kmlLabelScale: Number = definedExternally, fontInfo: dynamic? = definedExternally
    )

    /**
     * @returns String like "bold 12pt Arial"
     */
    fun getMPModifierFont(): String

    fun getMPModifierFontName(): String

    fun getMPModifierFontSize(): Number

    fun getMPModifierFontStyle(): String

    /**
     * @param name like "Arial" or "Arial, sans-serif" so a backup is
     * available in case 'Arial' is not present.
     * @param size like 12
     * @param style like "bold"
     */
    fun setModifierFont(name: String, size: Number, style: String, fontInfo: dynamic? = definedExternally)

    /**
     * @returns String like "bold 12pt Arial"
     */
    fun getModifierFont(): String

    fun getModifierFontName(): String

    fun getModifierFontSize(): Number

    fun getModifierFontStyle(): String

    fun getKMLLabelScale(): Number

    fun getFontInfo(): dynamic

    fun getMPFontInfo(): dynamic

    /**
     * None, outline (default), or filled background.
     * If set to OUTLINE, TextOutlineWidth changed to default of 4.
     * If set to OUTLINE_QUICK, TextOutlineWidth changed to default of 2.
     * Use setTextOutlineWidth if you'd like a different value.
     * @param textBackgroundMethod like RenderSettings.TextBackgroundMethod_NONE
     */
    fun setTextBackgroundMethod(textBackgroundMethod: Number)

    /**
     * None, outline (default), or filled background.
     * @return method like RenderSettings.TextBackgroundMethod_NONE
     */
    fun getTextBackgroundMethod(): Number

    /**
     * if RenderSettings.TextBackgroundMethod_OUTLINE is used,
     * the outline will be this many pixels wide.
     */
    fun setTextOutlineWidth(width: Number)

    /**
     * if RenderSettings.TextBackgroundMethod_OUTLINE is used,
     * the outline will be this many pixels wide.
     */
    fun getTextOutlineWidth(): Number

    /**
     * This applies to Single Point Tactical Graphics.
     * Setting this will determine the default value for milStdSymbols when created.
     * 0 for no outline,
     * 1 for outline thickness of 1 pixel,
     * 2 for outline thickness of 2 pixels,
     * greater than 2 is not currently recommended.
     */
    fun setSinglePointSymbolOutlineWidth(width: Number)

    /**
     * This only applies to single point tactical graphics.
     */
    fun getSinglePointSymbolOutlineWidth(): Number

    /**
     * Refers to text color of modifier labels
     * Default Color is Black. If NULL, uses line color of symbol
     */
    fun setLabelForegroundColor(value: Color?)

    /**
     * Refers to text color of modifier labels
     */
    fun getLabelForegroundColor(): Color?

    /**
     * Refers to text color of modifier labels
     * Default Color is White.
     * Null value means the optimal background color (black or white)
     * will be chose based on the color of the text.
     */
    fun setLabelBackgroundColor(value: Color?)

    /**
     * Refers to background color of modifier labels
     */
    fun getLabelBackgroundColor(): Color?

    /**
     * Collapse Modifiers for fire support areas when the symbol isn't large enough to show all
     * the labels.  Identifying label will always be visible.  Zooming in, to make the symbol larger,
     * will make more modifiers visible.  Resizing the symbol can also make more modifiers visible.
     */
    fun setAutoCollapseModifiers(value: Boolean)

    /**
     * Returns the current setting for Line Interpolation.
     */
    fun getAutoCollapseModifiers(): Boolean

    /**
     * Cesium users calling RenderSymbol2D should set this to true
     */
    fun setUseCesium2DScaleModifiers(value: Boolean)

    fun getUseCesium2DScaleModifiers(): Boolean

    /**
     * for SVG and Canvas output, if your images look stretched or scaled down,
     * try altering there values.  Smaller values will result in a bigger image.
     * Larger values will result in a smaller image.  For example, if you're
     * getting images half the size of the space that they take on the map and are
     * getting stretched to fill it, try 0.5 as a starting point.
     * @param value (default 1.0)
     */
    fun set3DMinScaleMultiplier(value: Number)

    fun get3DMinScaleMultiplier(): Number
    
    /**
     * for SVG and Canvas output, if your images look stretched or scaled down,
     * try altering there values.  Smaller values will result in a bigger image.
     * Larger values will result in a smaller image.  For example, if you're
     * getting images half the size of the space that they take on the map and are
     * getting stretched to fill it, try 0.5 as a starting point.
     * @param value (default 1.0)
     */
    fun set3DMaxScaleMultiplier(value: Number)

    fun get3DMaxScaleMultiplier(): Number

    /**
     * if true (default), when HQ Staff is present, location will be indicated by the free
     * end of the staff.
     */
    fun setCenterOnHQStaff(value: Boolean)

    fun getCenterOnHQStaff(): Boolean

    /**
     * Sets the default pixel size for symbology.
     * Default value is 35.
     */
    fun setDefaultPixelSize(size: Number)

    /**
     * Gets the default pixel size for symbology.
     * Default value is 35.
     */
    fun getDefaultPixelSize(): Number

    /**
     * Value from 0 to 255. The closer to 0 the lighter the text color has to be
     * to have the outline be black. Default value is 160.
     */
    fun setTextBackgroundAutoColorThreshold(value: Number)

    /**
     * Value from 0 to 255. The closer to 0 the lighter the text color has to be
     * to have the outline be black. Default value is 160.
     */
    fun getTextBackgroundAutoColorThreshold(): Number

    /**
     * false to use label font size
     * true to scale it using symbolPixelBounds / 3.5
     */
    fun setScaleEchelon(value: Boolean)

    /**
     * Returns the value determining if we scale the echelon font size or
     * just match the font size specified by the label font.
     */
    fun getScaleEchelon(): Boolean

    /**
     * Determines how to draw the Affiliation modifier.
     * True to draw as modifier label in the "E/F" location.
     * False to draw at the top right corner of the symbol
     */
    fun setDrawAffiliationModifierAsLabel(value: Boolean)

    /**
     * True to draw as modifier label in the "E/F" location.
     * False to draw at the top right corner of the symbol
     */
    fun getDrawAffiliationModifierAsLabel(): Boolean

    /**
     * If present, append the Country Code to the 'M' Label
     */
    fun setDrawCountryCode(value: Boolean)

    /**
     * If present, append the Country Code to the 'M' Label
     */
    fun getDrawCountryCode(): Boolean

    /**
     * Get a boolean indicating between the use of ENY labels in all segments (false) or
     * to only set 2 labels one at the north and the other one at the south of the graphic (true).
     */
    fun getTwoLabelOnly(): Boolean

    /**
     * Set a boolean indicating between the use of ENY labels in all segments (false) or
     * to only set 2 labels one at the north and the other one at the south of the graphic (true).
     */
    fun setTwoLabelOnly(twoLabelOnly: Boolean)

    /**
     * get the preferred fill affiliation color for units.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getFriendlyUnitFillColor(): Color

    /**
     * Set the preferred fill affiliation color for units
     *
     * @param friendlyUnitFillColor Color like  Color(255, 255, 255)
     */
    fun setFriendlyUnitFillColor(friendlyUnitFillColor: Color)

    /**
     * get the preferred fill affiliation color for units.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getHostileUnitFillColor(): Color

    /**
     * Set the preferred fill affiliation color for units
     *
     * @param hostileUnitFillColor Color like  Color(255, 255, 255)
     */
    fun setHostileUnitFillColor(hostileUnitFillColor: Color)

    /**
     * get the preferred fill affiliation color for units.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getNeutralUnitFillColor(): Color

    /**
     * Set the preferred line affiliation color for units
     *
     * @param neutralUnitFillColor Color like  Color(255, 255, 255)
     */
    fun setNeutralUnitFillColor(neutralUnitFillColor: Color)

    /**
     * get the preferred fill affiliation color for units.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getUnknownUnitFillColor(): Color

    /**
     * Set the preferred fill affiliation color for units
     *
     * @param unknownUnitFillColor Color like  Color(255, 255, 255)
     */
    fun setUnknownUnitFillColor(unknownUnitFillColor: Color)

    /**
     * get the preferred fill affiliation color for graphics.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getHostileGraphicFillColor(): Color

    /**
     * Set the preferred fill affiliation color for graphics
     *
     * @param hostileGraphicFillColor Color like  Color(255, 255, 255)
     */
    fun setHostileGraphicFillColor(hostileGraphicFillColor: Color)

    /**
     * get the preferred fill affiliation color for graphics.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getFriendlyGraphicFillColor(): Color

    /**
     * Set the preferred fill affiliation color for graphics
     *
     * @param friendlyGraphicFillColor Color like  Color(255, 255, 255)
     */
    fun setFriendlyGraphicFillColor(friendlyGraphicFillColor: Color)

    /**
     * get the preferred fill affiliation color for graphics.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getNeutralGraphicFillColor(): Color

    /**
     * Set the preferred fill affiliation color for graphics
     *
     * @param neutralGraphicFillColor Color like  Color(255, 255, 255)
     */
    fun setNeutralGraphicFillColor(neutralGraphicFillColor: Color)

    /**
     * get the preferred fill affiliation color for graphics.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getUnknownGraphicFillColor(): Color

    /**
     * Set the preferred fill affiliation color for graphics
     *
     * @param unknownGraphicFillColor Color like  Color(255, 255, 255)
     */
    fun setUnknownGraphicFillColor(unknownGraphicFillColor: Color)

    /**
     * get the preferred line affiliation color for units.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getFriendlyUnitLineColor(): Color

    /**
     * Set the preferred line affiliation color for units
     *
     * @param friendlyUnitLineColor Color like  Color(255, 255, 255)
     */
    fun setFriendlyUnitLineColor(friendlyUnitLineColor: Color)

    /**
     * get the preferred line   affiliation color for units.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getHostileUnitLineColor(): Color

    /**
     * Set the preferred line affiliation color for units
     *
     * @param hostileUnitLineColor Color like  Color(255, 255, 255)
     *
     * */
    fun setHostileUnitLineColor(hostileUnitLineColor: Color)

    /**
     * get the preferred line affiliation color for units.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getNeutralUnitLineColor(): Color

    /**
     * Set the preferred line affiliation color for units
     *
     * @param neutralUnitLineColor Color like  Color(255, 255, 255)
     */
    fun setNeutralUnitLineColor(neutralUnitLineColor: Color)

    /**
     * get the preferred line affiliation color for units.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getUnknownUnitLineColor(): Color

    /**
     * Set the preferred line affiliation color for units
     *
     * @param unknownUnitLineColor Color like  Color(255, 255, 255)
     */
    fun setUnknownUnitLineColor(unknownUnitLineColor: Color)

    /**
     * get the preferred line affiliation color for graphics.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getFriendlyGraphicLineColor(): Color

    /**
     * Set the preferred line affiliation color for graphics
     *
     * @param friendlyGraphicLineColor Color like  Color(255, 255, 255)
     */
    fun setFriendlyGraphicLineColor(friendlyGraphicLineColor: Color)

    /**
     * get the preferred line affiliation color for graphics.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getHostileGraphicLineColor(): Color

    /**
     * Set the preferred line affiliation color for graphics
     *
     * @param hostileGraphicLineColor Color like  Color(255, 255, 255)
     */
    fun setHostileGraphicLineColor(hostileGraphicLineColor: Color)

    /**
     * get the preferred line affiliation color for graphics.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getNeutralGraphicLineColor(): Color

    /**
     * Set the preferred line affiliation color for graphics
     *
     * @param neutralGraphicLineColor Color like  Color(255, 255, 255)
     */
    fun setNeutralGraphicLineColor(neutralGraphicLineColor: Color)

    /**
     * get the preferred line affiliation color for graphics.
     *
     * @return Color like  Color(255, 255, 255)
     */
    fun getUnknownGraphicLineColor(): Color

    /**
     * Set the preferred line affiliation color for graphics
     *
     * @param unknownGraphicLineColor Color like  Color(255, 255, 255)
     */
    fun setUnknownGraphicLineColor(unknownGraphicLineColor: Color)

    /**
     * Set the preferred line and fill affiliation color for tactical graphics.
     *
     * @param friendlyGraphicLineColor Color
     * @param hostileGraphicLineColor Color
     * @param neutralGraphicLineColor Color
     * @param unknownGraphicLineColor Color
     * @param friendlyGraphicFillColor Color
     * @param hostileGraphicFillColor Color
     * @param neutralGraphicFillColor Color
     * @param unknownGraphicFillColor Color
     */
    fun setGraphicPreferredAffiliationColors(
        friendlyGraphicLineColor: Color,
        hostileGraphicLineColor: Color,
        neutralGraphicLineColor: Color,
        unknownGraphicLineColor: Color,
        friendlyGraphicFillColor: Color,
        hostileGraphicFillColor: Color,
        neutralGraphicFillColor: Color,
        unknownGraphicFillColor: Color)

    /**
     * Set the preferred line and fill affiliation color for units and tactical graphics.
     *
     * @param friendlyUnitLineColor Color like  Color(255, 255, 255). Set to null to ignore setting
     * @param hostileUnitLineColor Color
     * @param neutralUnitLineColor Color
     * @param unknownUnitLineColor Color
     * @param friendlyUnitFillColor Color
     * @param hostileUnitFillColor Color
     * @param neutralUnitFillColor Color
     * @param unknownUnitFillColor Color
     */
    fun setUnitPreferredAffiliationColors(
        friendlyUnitLineColor: Color,
        hostileUnitLineColor: Color,
        neutralUnitLineColor: Color,
        unknownUnitLineColor: Color,
        friendlyUnitFillColor: Color,
        hostileUnitFillColor: Color,
        neutralUnitFillColor: Color,
        unknownUnitFillColor: Color
    )
}

external object SymbolUtilities {
    /**
     * @param symbolID 15 character code
     * @returns basic symbolID
     */
    fun getBasicSymbolID(symbolID: String, symStd: Number? = definedExternally): String

    /**
     * Reads the Symbol ID string and returns the text that represents the echelon
     * code.
     */
    fun getEchelonText(echelon: String): String

    /**
     * Gets line color used if no line color has been set. The color is specified based on the affiliation of
     * the symbol and whether it is a unit or not.
     * @returns [Color] hex color like #FFFFFF
     */
    fun getLineColorOfAffiliation(symbolID: String?): Color?

    /**
     * Is the fill color used if no fill color has been set. The color is specified based on the affiliation
     * of the symbol and whether it is a unit or not.
     * @returns [Color] hex color like #FFFFFF
     */
    fun getFillColorOfAffiliation(symbolID: String?): Color?

    /**
     * converts a Javascript Date object into a properly formated String for
     * W or W1
     */
    fun getDateLabel(date: Date): String

    /**
     * Determines if the symbol is a tactical graphic
     * @returns true if symbol starts with "G", or is a weather graphic, or an EMS natural event
     */
    fun isTacticalGraphic(strSymbolID: String?): Boolean

    fun isWeather(strSymbolID: String): Boolean

    fun isMobility(strSymbolID: String): Boolean

    fun hasInstallationModifier(strSymbolID: String): Boolean

    /**
     * Returns true if the SymbolID has a valid Affiliation (2nd character)
     */
    fun hasValidAffiliation(symbolID: String?): Boolean

    /**
     * Returns true if the SymbolID has a valid Status (4th character)
     */
    fun hasValidStatus(symbolID: String?): Boolean

    /**
     * Returns true if the characters in the country code positions of the
     * SymbolID are letters.
     */
    fun hasValidCountryCode(symbolID: String?): Boolean

    fun hasAMmodifierRadius(symbolID: String, symStd: Number? = definedExternally): Boolean

    fun hasAMmodifierWidth(symbolID: String, symStd: Number? = definedExternally): Boolean

    fun canSymbolHaveModifier(symbolID: String?, tgModifier: String, symStd: Number? = definedExternally): Boolean

    fun canUnitHaveModifier(symbolID: String?, unitModifier: String): Boolean
}

external object SymbolDefTable {
    /**
     * Just a category in the milstd hierarchy.
     * Not something we draw.
     * WILL NOT RENDER
     */
    val DRAW_CATEGORY_DONOTDRAW: Int

    /**
     * A polyline, a line with n number of points.
     * 0 control points
     */
    val DRAW_CATEGORY_LINE: Int

    /**
     * An animated shape, uses the animate function to draw.
     * 0 control points (every point shapes symbol)
     */
    val DRAW_CATEGORY_AUTOSHAPE: Int

    /**
     * An enclosed polygon with n points
     * 0 control points
     */
    val DRAW_CATEGORY_POLYGON: Int

    /**
     * A polyline with n points (entered in reverse order)
     * 0 control points
     */
    val DRAW_CATEGORY_ARROW: Int

    /**
     * A graphic with n points whose last point defines the width of the graphic.
     * 1 control point
     */
    val DRAW_CATEGORY_ROUTE: Int

    /**
     * A line defined only by 2 points, and cannot have more.
     * 0 control points
     */
    val DRAW_CATEGORY_TWOPOINTLINE: Int

    /**
     * Shape is defined by a single point
     * 0 control points
     */
    val DRAW_CATEGORY_POINT: Int

    /**
     * A polyline with 2 points (entered in reverse order).
     * 0 control points
     */
    val DRAW_CATEGORY_TWOPOINTARROW: Int

    /**
     * An animated shape, uses the animate function to draw. Super Autoshape draw
     * in 2 phases, usually one to define length, and one to define width.
     * 0 control points (every point shapes symbol)
     *
     */
    val DRAW_CATEGORY_SUPERAUTOSHAPE: Int

    /**
     * Circle that requires 1 AM modifier value.
     * See ModifiersTG.js for modifier descriptions and constant key strings.
     */
    val DRAW_CATEGORY_CIRCULAR_PARAMETERED_AUTOSHAPE: Int

    /**
     * Rectangle that requires 2 AM modifier values and 1 AN value.
     * See ModifiersTG.js for modifier descriptions and constant key strings.
     */
    val DRAW_CATEGORY_RECTANGULAR_PARAMETERED_AUTOSHAPE: Int

    /**
     * Requires 2 AM values and 2 AN values per sector.
     * The first sector can have just one AM value, although it is recommended
     * to always use 2 values for each sector.  X values are not required
     * as our rendering is only 2D for the Sector Range Fan symbol.
     * See ModifiersTG.js for modifier descriptions and constant key strings.
     */
    val DRAW_CATEGORY_SECTOR_PARAMETERED_AUTOSHAPE: Int

    /**
     *  Requires at least 1 distance/AM value"
     *  See ModifiersTG.js for modifier descriptions and constant key strings.
     */
    val DRAW_CATEGORY_CIRCULAR_RANGEFAN_AUTOSHAPE: Int

    /**
     * Requires 1 AM value.
     * See ModifiersTG.js for modifier descriptions and constant key strings.
     */
    val DRAW_CATEGORY_TWO_POINT_RECT_PARAMETERED_AUTOSHAPE: Int

    /**
     * 3D airspace, not a milstd graphic.
     */
    val DRAW_CATEGORY_3D_AIRSPACE: Int

    /**
     * UNKNOWN.
     */
    val DRAW_CATEGORY_UNKNOWN: Int

    /**
     * @param symbolID
     * @param symStd 2525b=0,2525c=1
     * @returns SymbolDef has symbolID, minPoints, maxPoints,
     * drawCategory, hasWidth, modifiers.  drawCategory is a number, the
     * rest are strings
     */
    fun getSymbolDef(symbolID: String, symStd: Number): dynamic
}

external object UnitDefTable {
    /**
     * @param symbolID
     * @param symStd 2525b=0,2525c=1
     * @returns UnitDef has symbolID, description, drawCategory,
     * hierarchy, alphahierarchy, path.  drawCategory is a Number.
     */
    fun getUnitDef(symbolID: String, symStd: Number): dynamic
}

external object ModifiersTG {
    /**
     * The innermost part of a symbol that represents a warfighting object
     * Here for completeness, not actually used as this comes from the
     * symbol code.
     * SIDC positions 3, 5-104
     * TG: P,L,A,BL,N,B/C
     * Length: G
     */
    val A_SYMBOL_ICON: String

    /**
     * The basic graphic (see 5.5.1).
     * We feed off of the symbol code so this isn't used
     * SIDC positions 11 and 12
     * TG: L,A,BL
     * Length: G
     */
    val B_ECHELON: String

    /**
     * A graphic modifier in a boundary graphic that
     * identifies command level (see 5.5.2.2, table V, and
     * figures 10 and 12).
     * TG: N
     * Length: 6
     */
    val C_QUANTITY: String

    /**
     * A text modifier for tactical graphics, content is
     * implementation specific.
     * TG: P,L,A,N,B/C
     * Length: 20
     */
    val H_ADDITIONAL_INFO_1: String

    /**
     * A text modifier for tactical graphics, content is
     * implementation specific.
     * TG: P,L,A,N,B/C
     * Length: 20
     */
    val H1_ADDITIONAL_INFO_2: String

    /**
     * A text modifier for tactical graphics, content is
     * implementation specific.
     * TG: P,L,A,N,B/C
     * Length: 20
     */
    val H2_ADDITIONAL_INFO_3: String

    /**
     * A text modifier for tactical graphics, letters "ENY" denote hostile symbols.
     * TG: P,L,A,BL,N,B/C
     * Length: 3
     */
    val N_HOSTILE: String

    /**
     * A graphic modifier for CBRN events that
     * identifies the direction of movement (see 5.5.2.1
     * and figure 11).
     * TG: N,B/C
     * Length: G
     */
    val Q_DIRECTION_OF_MOVEMENT: String

    /**
     * A graphic modifier for points and CBRN events
     * used when placing an object away from its actual
     * location (see 5.5.2.3 and figures 10, 11, and 12).
     * TG: P,N,B/C
     * Length: G
     */
    val S_OFFSET_INDICATOR: String

    /**
     * A text modifier that uniquely identifies a particular
     * tactical graphic, track number.
     * Nuclear: delivery unit (missile, aircraft, satellite,
     * etc.)
     * TG:P,L,A,BL,N,B/C
     * Length: 15 (35 for BL)
     */
    val T_UNIQUE_DESIGNATION_1: String

    /**
     * A text modifier that uniquely identifies a particular
     * tactical graphic, track number.
     * Nuclear: delivery unit (missile, aircraft, satellite,
     * etc.)
     * TG:P,L,A,BL,N,B/C
     * Length: 15 (35 for BL)
     */
    val T1_UNIQUE_DESIGNATION_2: String

    /**
     * A text modifier that indicates nuclear weapon type.
     * TG: N
     * Length: 20
     */
    val V_EQUIP_TYPE: String

    /**
     * A text modifier for units, equipment, and installations that displays DTG format:
     * DDHHMMSSZMONYYYY for on order (see 5.5.2.6).
     * TG:P,L,A,N,B/C
     * Length: 16
     */
    val W_DTG_1: String

    /**
     * A text modifier for units, equipment, and installations that displays DTG format:
     * DDHHMMSSZMONYYYY for on order (see 5.5.2.6).
     * TG:P,L,A,N,B/C
     * Length: 16
     */
    val W1_DTG_2: String

    /**
     * A text modifier that displays the minimum,
     * maximum, and/or specific altitude (in feet or
     * meters in relation to a reference datum), flight
     * level, or depth (for submerged objects in feet
     * below sea level). See 5.5.2.5 for content.
     * TG:P,L,A,N,B/C
     * Length: 14
     */
    val X_ALTITUDE_DEPTH: String

    /**
     * A text modifier that displays a graphic’s location
     * in degrees, minutes, and seconds (or in UTM or
     * other applicable display format).
     *  Conforms to decimal
     *  degrees format:
     *  xx.dddddhyyy.dddddh
     *  where
     *  xx : degrees latitude
     *  yyy : degrees longitude
     *  .ddddd : decimal degrees
     *  h : direction (N, E, S, W)
     * TG:P,L,A,BL,N,B/C
     * Length: 19
     */
    val Y_LOCATION: String

    /**
     * For Tactical Graphics
     * A numeric modifier that displays a minimum,
     * maximum, or a specific distance (range, radius,
     * width, length, etc.), in meters.
     * 0 - 999,999 meters
     * TG: P.L.A
     * Length: 6
     */
    val AM_DISTANCE: String

    /**
     * For Tactical Graphics
     * A numeric modifier that displays an angle
     * measured from true north to any other line in
     * degrees.
     * 0 - 359 degrees
     * TG: P.L.A
     * Length: 3
     */
    val AN_AZIMUTH: String
}

external object ModifiersUnits {
    /**
     * The innermost part of a symbol that represents a warfighting object
     * Here for completeness, not actually used as this comes from the
     * symbol code.
     * Type: U,E,I,SI,SO,EU,EEI,EI
     * Length: G
     */
    val A_SYMBOL_ICON: String

    /**
     * A graphic modifier in a unit symbol that identifies command level
     * We feed off of the symbol code so this isn't used
     * Type: U,SO
     * Length: G
     */
    val B_ECHELON: String

    /**
     * A text modifier in an equipment symbol that identifies the number of items present.
     * Type: E,EEI
     * Length: 9
     */
    val C_QUANTITY: String

    /**
     * A graphic modifier that identifies a unit or SO symbol as a task force (see 5.3.4.6
     * and figures 2 and 3).
     * Type: U,SO
     * Length: G
     */
    val D_TASK_FORCE_INDICATOR: String

    /**
     * A graphic modifier that displays standard identity, battle dimension, or exercise
     * amplifying descriptors of an object (see 5.3.1 and table II).
     * Type: U,E,I,SO,EU,EEI,EI
     * Length: G
     */
    val E_FRAME_SHAPE_MODIFIER: String

    /**
     * A text modifier in a unit symbol that displays (+) for reinforced, (-) for reduced,(+) reinforced and reduced.
     * R : reinforced,D : reduced,RD : reinforced and reduced
     * Type: U,SO
     * Length: 23
     */
    val F_REINFORCED_REDUCED: String

    /**
     * A text modifier for units, equipment and installations, content is implementation specific.
     * Type: U,E,I,SI,SO
     * Length: 20
     */
    val G_STAFF_COMMENTS: String

    /**
     * Text modifier for amplifying free text
     * Type: U,E,I,SI,SO,EU,EEI,EI
     * Length: 20
     */
    val H_ADDITIONAL_INFO_1: String

    /**
     * Text modifier for amplifying free text
     * Type: U,E,I,SI,SO,EU,EEI,EI
     * Length: 20
     */
    val H1_ADDITIONAL_INFO_2: String

    /**
     * Text modifier for amplifying free text
     * Type: U,E,I,SI,SO,EU,EEI,EI
     * Length: 20
     */
    val H2_ADDITIONAL_INFO_3: String

    /**
     * A text modifier for units, equipment, and installations that consists of
     * a one letter reliability rating and a one-number credibility rating.
    Reliability Ratings: A-completely reliable, B-usually reliable,
    C-fairly reliable, D-not usually reliable, E-unreliable,
    F-reliability cannot be judged.
    Credibility Ratings: 1-confirmed by other sources,
    2-probably true, 3-possibly true, 4-doubtfully true,
    5-improbable, 6-truth cannot be judged.
    Type: U,E,I,SI,SO,EU,EEI,EI
    Length: 2
     */
    val J_EVALUATION_RATING: String

    /**
     * A text modifier for units and installations that indicates unit effectiveness or
     * installation capability.
     * Type: U,I,SO
     * Length: 5,5,3
     */
    val K_COMBAT_EFFECTIVENESS: String

    /**
     * A text modifier for hostile equipment, “!�? indicates detectable electronic
     * signatures.
     * Type: E,SI
     * Length: 1
     */
    val L_SIGNATURE_EQUIP: String

    /**
     * A text modifier for units that indicates number or title of higher echelon
     * command (corps are designated by Roman numerals).
     * Type: U,SI
     * Length: 21
     */
    val M_HIGHER_FORMATION: String

    /**
     * A text modifier for equipment, letters "ENY" denote hostile symbols.
     * Type: E
     * Length: 3
     */
    val N_HOSTILE: String

    /**
     * A text modifier displaying IFF/SIF Identification modes and codes.
     * Type: U,E,SO
     * Length: 5
     */
    val P_IFF_SIF: String

    /**
     * A graphic modifier for units and equipment that identifies the direction of
     * movement or intended movement of an object (see 5.3.4.1 and figures 2 and 3).
     * Type: U,E,SO,EU,EEI
     * Length: G
     */
    val Q_DIRECTION_OF_MOVEMENT: String

    /**
     * A graphic modifier for equipment that depicts the mobility of an object (see
     *   5.3.4.3, figures 2 and 3, and table VI).
     * We feed off of the symbol code for mobility so this isn't used
     * Type: E,EEI
     * Length: G
     */
    val R_MOBILITY_INDICATOR: String

    /**
     * M : Mobile, S : Static, or U : Uncertain.
     * Type: SI
     * Length: 1
     */
    val R2_SIGNIT_MOBILITY_INDICATOR: String

    /**
     * Headquarters staff indicator: A graphic modifier for units, equipment, and
     * installations that identifies a unit as a headquarters (see 5.3.4.8 and figures 2 and
     * 3).
     * Offset location indicator: A graphic modifier for units, equipment, and
     * installations used when placing an object away from its actual location (see
     * 5.3.4.9 and figures 2 and 3).
     * Type: U,E,I,SO,EU,EEI,EI
     * Length: G
     */
    val S_HQ_STAFF_OR_OFFSET_INDICATOR: String

    /**
     * A text modifier for units, equipment, and installations that uniquely identifies a
     * particular symbol or track number. Identifies acquisitions number when used
     * with SIGINT symbology.
     * Type: U,E,I,SI,SO,EU,EEI,EI
     * Length: 21
     */
    val T_UNIQUE_DESIGNATION_1: String

    /**
     * A text modifier for units, equipment, and installations that uniquely identifies a
     * particular symbol or track number. Identifies acquisitions number when used
     * with SIGINT symbology.
     * Type: U,E,I,SI,SO,EU,EEI,EI
     * Length: 21
     */
    val T1_UNIQUE_DESIGNATION_2: String

    /**
     * A text modifier for equipment that indicates types of equipment.
     * For Tactical Graphics:
     * A text modifier that indicates nuclear weapon type.
     * Type: E,SI,EEI
     * Length: 24
     */
    val V_EQUIP_TYPE: String

    /**
     * A text modifier for units, equipment, and installations that displays DTG format:
     * DDHHMMSSZMONYYYY for on order (see 5.5.2.6).
     * Type: U,E,I,SI,SO,EU,EEI,EI
     * Length: 16
     */
    val W_DTG_1: String

    /**
     * A text modifier for units, equipment, and installations that displays DTG format:
     * DDHHMMSSZMONYYYY for on order (see 5.5.2.6).
     * Type: U,E,I,SI,SO,EU,EEI,EI
     * Length: 16
     */
    val W1_DTG_2: String

    /**
     * A text modifier for units, equipment, and installations, that displays either
     * altitude flight level, depth for submerged objects, or height of equipment or
     * structures on the ground. See 5.5.2.5 for content.
     * Type: U,E,I,SO,EU,EEI,EI
     * Length: 14
     */
    val X_ALTITUDE_DEPTH: String

    /**
     * A text modifier for units, equipment, and installations that displays a symbol’s
     * location in degrees, minutes, and seconds (or in UTM or other applicable display
     * format).
     * Conforms to decimal
     *  degrees format:
     *  xx.dddddhyyy.dddddh
     *  where
     *  xx : degrees latitude
     *  yyy : degrees longitude
     *  .ddddd : decimal degrees
     *  h : direction (N, E, S, W)
     * Type: U,E,I,SI,SO,EU,EEI,EI
     * Length: 19
     */
    val Y_LOCATION: String

    /**
     * A text modifier for units and equipment that displays velocity as set forth in
     * MIL-STD-6040.
     * Type: U,E,SO,EU,EEI
     * Length: 8
     */
    val Z_SPEED: String

    /**
     * A text modifier for units, indicator is contained inside the frame (see figures 2
     * and 3), contains the name of the special C2 Headquarters.
     * Type: U,SO
     * Length: 9
     */
    val AA_SPECIAL_C2_HQ: String

    /**
     * Feint or dummy indicator: A graphic modifier for units, equipment, and
     * installations that identifies an offensive or defensive unit intended to draw the
     * enemy’s attention away from the area of the main attack (see 5.3.4.7 and figures
     * 2 and 3).
     * Type: U,E,I,SO
     * Length: G
     */
    val AB_FEINT_DUMMY_INDICATOR: String

    /**
     * Installation: A graphic modifier for units, equipment, and installations used to
     * show that a particular symbol denotes an installation (see 5.3.4.5 and figures 2
     * and 3).
     * Not used, we feed off of symbol code for this
     * Type: U,E,I,SO,EU,EEI,EI
     * Length: G
     */
    val AC_INSTALLATION: String

    /**
     * ELNOT or CENOT
     * Type: SI
     * Length: 6
     */
    val AD_PLATFORM_TYPE: String

    /**
     * Equipment teardown time in minutes.
     * Type: SI
     * Length: 3
     */
    val AE_EQUIPMENT_TEARDOWN_TIME: String

    /**
     * Example: "Hawk" for Hawk SAM system.
     * Type: SI
     * Length: 12
     */
    val AF_COMMON_IDENTIFIER: String

    /**
     * Towed sonar array indicator: A graphic modifier for equipment that indicates the
     * presence of a towed sonar array (see 5.3.4.4, figures 2 and 3, and table VII).
     * Type: E
     * Length: G
     */
    val AG_AUX_EQUIP_INDICATOR: String

    /**
     * A graphic modifier for units and equipment that indicates the area where an
     * object is most likely to be, based on the object’s last report and the reporting
     * accuracy of the sensor that detected the object (see 5.3.4.11.1 and figure 4).
     * Type: E,U,SO,EU,EEI
     * Length: G
     */
    val AH_AREA_OF_UNCERTAINTY: String

    /**
     * A graphic modifier for units and equipment that identifies where an object
     * should be located at present, given its last reported course and speed (see
     * 5.3.4.11.2 and figure 4).
     * Type: E,U,SO,EU,EEI
     * Length: G
     */
    val AI_DEAD_RECKONING_TRAILER: String

    /**
     * A graphic modifier for units and equipment that depicts the speed and direction
     * of movement of an object (see 5.3.4.11.3 and figure 4).
     * Type: E,U,SO,EU,EEI
     * Length: G
     */
    val AJ_SPEED_LEADER: String

    /**
     * A graphic modifier for units and equipment that connects two objects and is
     * updated dynamically as the positions of the objects change (see 5.3.4.11.4 and
     * figure 4).
     * Type: U,E,SO
     * Length: G
     */
    val AK_PAIRING_LINE: String

    /**
     * An optional graphic modifier for equipment or installations that indicates
     * operational condition or capacity.
     * Type: E,I,SI,SO,EU,EEI,EI
     * Length: G
     */
    val AL_OPERATIONAL_CONDITION: String

    /**
     * A graphic amplifier placed immediately atop the symbol. May denote, 1)
     * local/remote status, 2) engagement status, and 3) weapon type.
     *
     * Type: U,E,I
     * Length: G/8
     */
    val AO_ENGAGEMENT_BAR: String

    /**
     * Used internally by the renderer.  This value is set via the 13th & 14th
     * characters in the symbol id.  There is no formal definition of how
     * this should be indicated on the symbol in the MilStd or USAS.
     * The renderer will place it to the right of the 'H' label.
     */
    val CC_COUNTRY_CODE: String

    /**
     * A generic name label that goes to the right of the symbol and
     * any existing labels.  If there are no existing labels, it goes right
     * next to the right side of the symbol.  This is a CPOF label that applies
     * to all force elements.  This IS NOT a MilStd or USAS Label.
     */
    val CN_CPOF_NAME_LABEL: String

    /**
     * Sonar Classification Confidence level. valid values are 1-5.
     * Only applies to the 4 subsurface MILCO sea mines
     */
    val SCC_SONAR_CLASSIFICATION_CONFIDENCE: String
}

external object MilStdAttributes {
    val PixelSize: String
    val AltitudeMode: String
    val FillColor: String
    val LineColor: String
    val IconColor: String
}

