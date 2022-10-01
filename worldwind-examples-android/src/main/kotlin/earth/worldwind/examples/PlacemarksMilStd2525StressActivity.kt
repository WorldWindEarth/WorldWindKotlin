package earth.worldwind.examples

import android.graphics.Color
import android.os.Bundle
import android.util.SparseArray
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import android.widget.FrameLayout
import android.widget.TextView
import armyc2.c2sd.renderer.utilities.MilStdAttributes
import armyc2.c2sd.renderer.utilities.ModifiersUnits
import earth.worldwind.examples.milstd2525.MilStd2525
import earth.worldwind.examples.milstd2525.MilStd2525LevelOfDetailSelector
import earth.worldwind.examples.milstd2525.MilStd2525Placemark
import earth.worldwind.geom.Angle.Companion.toDegrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.ShowTessellationLayer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.asin
import kotlin.random.Random

open class PlacemarksMilStd2525StressActivity: GeneralGlobeActivity(), FrameCallback {
    protected var activityPaused = false
    protected var cameraDegreesPerSecond = 2.0
    protected var lastFrameTimeNanos = 0L

    // A component for displaying the status of this activity
    protected lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_placemarks_milstd2525_stress_test)
        aboutBoxText = "Demonstrates a LOT of different MIL-STD-2525 symbols."

        // Add a TextView on top of the globe to convey the status of this activity
        statusText = TextView(this)
        statusText.setTextColor(Color.YELLOW)
        val globeLayout = findViewById<FrameLayout>(R.id.globe)
        globeLayout.addView(statusText)
        wwd.engine.layers.apply {
            clearLayers()
            addLayer(ShowTessellationLayer())
        }

        // The MIL-STD-2525 rendering library takes time initialize, we'll perform this task via the
        // async task's background thread and then load the symbols in its post execute handler.
        InitializeSymbolsTask().execute()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos != 0L) {
            // Compute the frame duration in seconds.
            val frameDurationSeconds = (frameTimeNanos - lastFrameTimeNanos) * 1.0e-9
            val cameraDegrees = frameDurationSeconds * cameraDegreesPerSecond

            // Move the camera to simulate the Earth's rotation about its axis.
            val camera = wwd.engine.camera
            camera.position.longitude = camera.position.longitude.minusDegrees(cameraDegrees)

            // Redraw the WorldWindow to display the above changes.
            wwd.requestRedraw()
        }
        // stop animating when this Activity is paused
        if (!activityPaused) Choreographer.getInstance().postFrameCallback(this)
        lastFrameTimeNanos = frameTimeNanos
    }

    override fun onPause() {
        super.onPause()
        // Stop running the animation when this activity is paused.
        activityPaused = true
        lastFrameTimeNanos = 0
    }

    override fun onResume() {
        super.onResume()
        // Resume the earth rotation animation
        activityPaused = false
        lastFrameTimeNanos = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    /**
     * InitializeSymbolsTask is an async task that initializes the MIL-STD-2525 symbol renderer on a background thread
     * and then loads the symbols after the initialization is complete. This task must be instantiated and executed on
     * the UI Thread.
     */
    protected open inner class InitializeSymbolsTask: CoroutinesAsyncTask<Void, Void, Void>() {
        // Formatter for a date-time group (DTG) string
        private val dateTimeGroup = SimpleDateFormat("ddHHmmss'Z'MMMyyyy", Locale.US)

        // Create a random number generator with an arbitrary seed
        // that will generate the same numbers between runs.
        protected val random = Random(123)

        override fun onPreExecute() {
            super.onPreExecute()
            statusText.text = "Initializing the MIL-STD-2525 Library and symbols..."
        }

        /**
         * Initialize the MIL-STD-2525 Rendering Library on a background thread.
         */
        override fun doInBackground(vararg params: Void): Void? {
            // Time consuming operation . . .
            MilStd2525.initializeRenderer(applicationContext)
            return null
        }

        /**
         * Update the symbol layer on the UI Thread.
         */
        override fun onPostExecute(result: Void?) {
            super.onPostExecute(result)

            // Create a Renderable layer for the placemarks and add it to the WorldWindow
            val symbolLayer = RenderableLayer("MIL-STD-2525 Symbols")
            wwd.engine.layers.addLayer(symbolLayer)
            MilStd2525LevelOfDetailSelector.setFarThreshold(1500000.0)
            MilStd2525LevelOfDetailSelector.setNearThreshold(750000.0)
            val unitModifiers = SparseArray<String>()
            val renderAttributes = SparseArray<String>()
            renderAttributes.put(MilStdAttributes.KeepUnitRatio, "false")
            val codeScheme = "S" // Warfighting
            val sizeMobility = "*"
            val countryCode = "**"
            val orderOfBattle = "**"
            var numSymbolsCreated = 0
            for (standardId in standardIdentities) {
                for (battleDimension in battleDimensions) {
                    for (status in statusCodes) {
                        when (battleDimension) {
                            "Z" -> for (functionId in warfightingUnknownFunctionIDs) {
                                val sidc = codeScheme + standardId + battleDimension + status + functionId + sizeMobility + countryCode + orderOfBattle
                                val position = randomPosition
                                unitModifiers.put(ModifiersUnits.W_DTG_1, getDateTimeGroup(Date()))
                                unitModifiers.put(ModifiersUnits.Y_LOCATION, getLocation(position))
                                symbolLayer.addRenderable(
                                    MilStd2525Placemark(position, sidc, unitModifiers, renderAttributes)
                                )
                                numSymbolsCreated++
                            }
                            "P" ->                                 //unitModifiers.clear();
                                for (functionId in warfightingSpaceFunctionIDs) {
                                    val sidc = codeScheme + standardId + battleDimension + status + functionId + sizeMobility + countryCode + orderOfBattle
                                    val position = randomPosition
                                    unitModifiers.put(ModifiersUnits.W_DTG_1, getDateTimeGroup(Date()))
                                    unitModifiers.put(ModifiersUnits.Y_LOCATION, getLocation(position))
                                    symbolLayer.addRenderable(
                                        MilStd2525Placemark(position, sidc, unitModifiers, renderAttributes)
                                    )
                                    numSymbolsCreated++
                                }
                            "A" -> for (functionId in warfightingAirFunctionIDs) {
                                val sidc = codeScheme + standardId + battleDimension + status + functionId + sizeMobility + countryCode + orderOfBattle
                                val position = randomPosition
                                unitModifiers.put(ModifiersUnits.W_DTG_1, getDateTimeGroup(Date()))
                                unitModifiers.put(ModifiersUnits.Y_LOCATION, getLocation(position))
                                symbolLayer.addRenderable(
                                    MilStd2525Placemark(position, sidc, unitModifiers, renderAttributes)
                                )
                                numSymbolsCreated++
                            }
                            "G" -> for (functionId in warfightingGroundFunctionIDs) {
                                val sidc = codeScheme + standardId + battleDimension + status + functionId + sizeMobility + countryCode + orderOfBattle
                                symbolLayer.addRenderable(
                                    MilStd2525Placemark(randomPosition, sidc, unitModifiers, renderAttributes)
                                )
                                numSymbolsCreated++
                            }
                            "S" -> for (functionId in warfightingSeaSurfaceFunctionIDs) {
                                val sidc = codeScheme + standardId + battleDimension + status + functionId + sizeMobility + countryCode + orderOfBattle
                                val position = randomPosition
                                unitModifiers.put(ModifiersUnits.W_DTG_1, getDateTimeGroup(Date()))
                                unitModifiers.put(ModifiersUnits.Y_LOCATION, getLocation(position))
                                symbolLayer.addRenderable(
                                    MilStd2525Placemark(position, sidc, unitModifiers, renderAttributes)
                                )
                                numSymbolsCreated++
                            }
                            "U" -> for (functionId in warfightingSubsurfaceFunctionIDs) {
                                val sidc = codeScheme + standardId + battleDimension + status + functionId + sizeMobility + countryCode + orderOfBattle
                                val position = randomPosition
                                unitModifiers.put(ModifiersUnits.W_DTG_1, getDateTimeGroup(Date()))
                                unitModifiers.put(ModifiersUnits.Y_LOCATION, getLocation(position))
                                symbolLayer.addRenderable(
                                    MilStd2525Placemark(position, sidc, unitModifiers, renderAttributes)
                                )
                                numSymbolsCreated++
                            }
                            "F" -> for (functionId in warfightingSOFFunctionIDs) {
                                val sidc = codeScheme + standardId + battleDimension + standardId + functionId + sizeMobility + countryCode + orderOfBattle
                                val position = randomPosition
                                unitModifiers.put(ModifiersUnits.W_DTG_1, getDateTimeGroup(Date()))
                                unitModifiers.put(ModifiersUnits.Y_LOCATION, getLocation(position))
                                symbolLayer.addRenderable(
                                    MilStd2525Placemark(position, sidc, unitModifiers, renderAttributes)
                                )
                                numSymbolsCreated++
                            }
                        }
                    }
                }
            }
            // Signal a change in the WorldWind scene
            // requestRedraw() is callable from any thread.
            wwd.requestRedraw()

            // Clear the status message set in onPreExecute
            statusText.text = "%,d Symbols Created".format(numSymbolsCreated)
        }// Use a random sin value to generate latitudes without clustering at the poles.

        /**
         * Returns a  an even distribution of latitude and longitudes across the globe.
         *
         * @return A random latitude/longitude with a zero altitude
         */
        protected val randomPosition: Position get() {
            // Use a random sin value to generate latitudes without clustering at the poles.
            val lat = toDegrees(asin(random.nextDouble())) * if (random.nextBoolean()) 1 else -1
                val lon = 180.0 - random.nextDouble() * 360
                return fromDegrees(lat, lon, 0.0)
            }

        /**
         * Returns a date-time group (DTG) string for the given date.
         *
         * @param date The date/time to be formatted as a date-time group
         *
         * @return DDHHMMSSZMONYYYY
         */
        protected open fun getDateTimeGroup(date: Date) = dateTimeGroup.format(date).uppercase()

        /**
         * Returns a location string for the given position.
         *
         * @param position The position to be formated as location string
         *
         * @return xx.dddddhyyy.dddddh where xx = degrees latitude, yyy = degrees longitude, .ddddd = decimal degrees,
         * and h = direction (N, E, S, W)
         */
        protected open fun getLocation(position: Position): String {
            return "%02.5f%s%03.5f%s".format(
                abs(position.latitude.degrees),
                if (position.latitude.degrees > 0) "N" else "S",
                abs(position.longitude.degrees),
                if (position.longitude.degrees > 0) "E" else "W"
            )
        }
    }

    companion object {
        private val standardIdentities = arrayOf(
            "P",  // Pending
            "U",  // Unknown
            "F",  // Friend
            "N",  // Neutral
            "H",  // Hostile
            "A",  // Assumed Friend
            "S"   // Suspect
        )
        private val battleDimensions = arrayOf(
            "Z",  // Unknown
            "P",  // Space
            "A",  // Air
            "G",  // Ground
            "S",  // Sea surface
            "U",  // Subsurface
            "F"   // SOF
        )
        private val statusCodes = arrayOf(
            "A",  // Anticipated
            "P",  // Present
            "C",  // Present/Fully Capable
            "D",  // Present/Damaged
            "X",  // Present/Destroyed
            "F"   // Present/Full to Capacity
        )
        private val warfightingUnknownFunctionIDs = arrayOf("------")
        private val warfightingSpaceFunctionIDs = arrayOf(
            "------", "S-----", "V-----", "T-----", "L-----"
        )
        private val warfightingAirFunctionIDs = arrayOf(
            "------", "C-----", "M-----", "MF----", "MFB---", "MFF---", "MFFI--", "MFT---", "MFA---",
            "MFL---", "MFK---", "MFKB--", "MFKD--", "MFC---", "MFCL--", "MFCM--", "MFCH--", "MFJ---",
            "MFO---", "MFR---", "MFRW--", "MFRZ--", "MFRX--", "MFP---", "MFPN--", "MFPM--", "MFU---",
            "MFUL--", "MFUM--", "MFUH--", "MFY---", "MFH---", "MFD---", "MFQ---", "MFQA--", "MFQB--",
            "MFQC--", "MFQD--", "MFQF--", "MFQH--", "MFQJ--", "MFQK--", "MFQL--", "MFQM--", "MFQI--",
            "MFQN--", "MFQP--", "MFQR--", "MFQRW-", "MFQRZ-", "MFQRX-", "MFQS--", "MFQT--", "MFQU--",
            "MFQY--", "MFQO--", "MFS---", "MFM---", "MH----", "MHA---", "MHS---", "MHU---", "MHUL--",
            "MHUM--", "MHUH--", "MHI---", "MHH---", "MHR---", "MHQ---", "MHC---", "MHCL--", "MHCM--",
            "MHCH--", "MHT---", "MHO---", "MHM---", "MHD---", "MHK---", "MHJ---", "ML----", "MV----",
            "ME----", "W-----", "WM----", "WMS---", "WMSS--", "WMSA--", "WMSU--", "WMSB--", "WMA---",
            "WMAS--", "WMAA--", "WMAP--", "WMU---", "WMCM--", "WMB---", "WB----", "WD----", "C-----",
            "CF----", "CH----", "CL----"
        )
        private val warfightingGroundFunctionIDs = arrayOf(
            "------", "U-----", "UC----", "UCD---", "UCDS--", "UCDSC-", "UCDSS-", "UCDSV-", "UCDM--",
            "UCDML-", "UCDMLA", "UCDMM-", "UCDMH-", "UCDH--", "UCDHH-", "UCDHP-", "UCDG--", "UCDC--",
            "UCDT--", "UCDO--", "UCA---", "UCAT--", "UCATA-", "UCATW-", "UCATWR", "UCATL-", "UCATM-",
            "UCATH-", "UCATR-", "UCAW--", "UCAWS-", "UCAWA-", "UCAWW-", "UCAWWR", "UCAWL-", "UCAWM-",
            "UCAWH-", "UCAWR-", "UCAA--", "UCAAD-", "UCAAL-", "UCAAM-", "UCAAS-", "UCAAU-", "UCAAC-",
            "UCAAA-", "UCAAAT", "UCAAAW", "UCAAAS", "UCAAO-", "UCAAOS", "UCV---", "UCVF--", "UCVFU-",
            "UCVFA-", "UCVFR-", "UCVR--", "UCVRA-", "UCVRS-", "UCVRW-", "UCVRU-", "UCVRUL", "UCVRUM",
            "UCVRUH", "UCVRUC", "UCVRUE", "UCVRM-", "UCVS--", "UCVC--", "UCVV--", "UCVU--", "UCVUF-",
            "UCVUR-", "UCI---", "UCIL--", "UCIM--", "UCIO--", "UCIA--", "UCIS--", "UCIZ--", "UCIN--",
            "UCII--", "UCIC--", "UCE---", "UCEC--", "UCECS-", "UCECA-", "UCECC-", "UCECL-", "UCECM-",
            "UCECH-", "UCECT-", "UCECW-", "UCECO-", "UCECR-", "UCEN--", "UCENN-", "UCF---", "UCFH--",
            "UCFHE-", "UCFHS-", "UCFHA-", "UCFHC-", "UCFHO-", "UCFHL-", "UCFHM-", "UCFHH-", "UCFHX-",
            "UCFR--", "UCFRS-", "UCFRSS", "UCFRSR", "UCFRST", "UCFRM-", "UCFRMS", "UCFRMR", "UCFRMT",
            "UCFT--", "UCFTR-", "UCFTS-", "UCFTF-", "UCFTC-", "UCFTCD", "UCFTCM", "UCFTA-", "UCFM--",
            "UCFMS-", "UCFMW-", "UCFMT-", "UCFMTA", "UCFMTS", "UCFMTC", "UCFMTO", "UCFML-", "UCFS--",
            "UCFSS-", "UCFSA-", "UCFSL-", "UCFSO-", "UCFO--", "UCFOS-", "UCFOA-", "UCFOL-", "UCFOO-",
            "UCR---", "UCRH--", "UCRV--", "UCRVA-", "UCRVM-", "UCRVG-", "UCRVO-", "UCRC--", "UCRS--",
            "UCRA--", "UCRO--", "UCRL--", "UCRR--", "UCRRD-", "UCRRF-", "UCRRL-", "UCRX--", "UCM---",
            "UCMT--", "UCMS--", "UCS---", "UCSW--", "UCSG--", "UCSGD-", "UCSGM-", "UCSGA-", "UCSM--",
            "UCSR--", "UCSA--", "UU----", "UUA---", "UUAC--", "UUACC-", "UUACCK", "UUACCM", "UUACS-",
            "UUACSM", "UUACSA", "UUACR-", "UUACRW", "UUACRS", "UUAN--", "UUAB--", "UUABR-", "UUAD--",
            "UUM---", "UUMA--", "UUMS--", "UUMSE-", "UUMSEA", "UUMSED", "UUMSEI", "UUMSEJ", "UUMSET",
            "UUMSEC", "UUMC--", "UUMR--", "UUMRG-", "UUMRS-", "UUMRSS", "UUMRX-", "UUMMO-", "UUMO--",
            "UUMT--", "UUMQ--", "UUMJ--", "UUL---", "UULS--", "UULM--", "UULC--", "UULF--", "UULD--",
            "UUS---", "UUSA--", "UUSC--", "UUSCL-", "UUSO--", "UUSF--", "UUSM--", "UUSMS-", "UUSML-",
            "UUSMN-", "UUSR--", "UUSRS-", "UUSRT-", "UUSRW-", "UUSS--", "UUSW--", "UUSX--", "UUI---",
            "UUP---", "UUE---", "US----", "USA---", "USAT--", "USAC--", "USAJ--", "USAJT-", "USAJC-",
            "USAO--", "USAOT-", "USAOC-", "USAF--", "USAFT-", "USAFC-", "USAS--", "USAST-", "USASC-",
            "USAM--", "USAMT-", "USAMC-", "USAR--", "USART-", "USARC-", "USAP--", "USAPT-", "USAPC-",
            "USAPB-", "USAPBT", "USAPBC", "USAPM-", "USAPMT", "USAPMC", "USAX--", "USAXT-", "USAXC-",
            "USAL--", "USALT-", "USALC-", "USAW--", "USAWT-", "USAWC-", "USAQ--", "USAQT-", "USAQC-",
            "USM---", "USMT--", "USMC--", "USMM--", "USMMT-", "USMMC-", "USMV--", "USMVT-", "USMVC-",
            "USMD--", "USMDT-", "USMDC-", "USMP--", "USMPT-", "USMPC-", "USS---", "USST--", "USSC--",
            "USS1--", "USS1T-", "USS1C-", "USS2--", "USS2T-", "USS2C-", "USS3--", "USS3T-", "USS3C-",
            "USS3A-", "USS3AT", "USS3AC", "USS4--", "USS4T-", "USS4C-", "USS5--", "USS5T-", "USS5C-",
            "USS6--", "USS6T-", "USS6C-", "USS7--", "USS7T-", "USS7C-", "USS8--", "USS8T-", "USS8C-",
            "USS9--", "USS9T-", "USS9C-", "USSX--", "USSXT-", "USSXC-", "USSL--", "USSLT-", "USSLC-",
            "USSW--", "USSWT-", "USSWC-", "USSWP-", "USSWPT", "USSWPC", "UST---", "USTT--", "USTC--",
            "USTM--", "USTMT-", "USTMC-", "USTR--", "USTRT-", "USTRC-", "USTS--", "USTST-", "USTSC-",
            "USTA--", "USTAT-", "USTAC-", "USTI--", "USTIT-", "USTIC-", "USX---", "USXT--", "USXC--",
            "USXH--", "USXHT-", "USXHC-", "USXR--", "USXRT-", "USXRC-", "USXO--", "USXOT-", "USXOC-",
            "USXOM-", "USXOMT", "USXOMC", "USXE--", "USXET-", "USXEC-", "UH----", "E-----", "EWM---",
            "EWMA--", "EWMAS-", "EWMASR", "EWMAI-", "EWMAIR", "EWMAIE", "EWMAL-", "EWMALR", "EWMALE",
            "EWMAT-", "EWMATR", "EWMATE", "EWMS--", "EWMSS-", "EWMSI-", "EWMSL-", "EWMT--", "EWMTL-",
            "EWMTM-", "EWMTH-", "EWS---", "EWSL--", "EWSM--", "EWSH--", "EWX---", "EWXL--", "EWXM--",
            "EWXH--", "EWT---", "EWTL--", "EWTM--", "EWTH--", "EWR---", "EWRR--", "EWRL--", "EWRH--",
            "EWZ---", "EWZL--", "EWZM--", "EWZH--", "EWO---", "EWOL--", "EWOM--", "EWOH--", "EWH---",
            "EWHL--", "EWHLS-", "EWHM--", "EWHMS-", "EWHH--", "EWHHS-", "EWG---", "EWGL--", "EWGM--",
            "EWGH--", "EWGR--", "EWD---", "EWDL--", "EWDLS-", "EWDM--", "EWDMS-", "EWDH--", "EWDHS-",
            "EWA---", "EWAL--", "EWAM--", "EWAH--", "EV----", "EVA---", "EVAT--", "EVATL-", "EVATLR",
            "EVATM-", "EVATMR", "EVATH-", "EVATHR", "EVAA--", "EVAAR-", "EVAI--", "EVAC--", "EVAS--",
            "EVAL--", "EVU---", "EVUB--", "EVUS--", "EVUSL-", "EVUSM-", "EVUSH-", "EVUL--", "EVUX--",
            "EVUR--", "EVUT--", "EVUTL-", "EVUTH-", "EVUA--", "EVUAA-", "EVE---", "EVEB--", "EVEE--",
            "EVEC--", "EVEM--", "EVEMV-", "EVEML-", "EVEA--", "EVEAA-", "EVEAT-", "EVED--", "EVEDA-",
            "EVES--", "EVER--", "EVEH--", "EVEF--", "EVT---", "EVC---", "EVCA--", "EVCAL-", "EVCAM-",
            "EVCAH-", "EVCO--", "EVCOL-", "EVCOM-", "EVCOH-", "EVCM--", "EVCML-", "EVCMM-", "EVCMH-",
            "EVCU--", "EVCUL-", "EVCUM-", "EVCUH-", "EVCJ--", "EVCJL-", "EVCJM-", "EVCJH-", "EVCT--",
            "EVCTL-", "EVCTM-", "EVCTH-", "EVCF--", "EVCFL-", "EVCFM-", "EVCFH-", "EVM---", "EVS---",
            "EVST--", "EVSR--", "EVSC--", "EVSP--", "EVSW--", "ES----", "ESR---", "ESE---", "EXI---",
            "EXL---", "EXN---", "EXF---", "EXM---", "EXMC--", "EXML--", "I-----", "IR----", "IRM---",
            "IRP---", "IRN---", "IRNB--", "IRNC--", "IRNN--", "IP----", "IPD---", "IE----", "IU----",
            "IUR---", "IUT---", "IUE---", "IUEN--", "IUED--", "IUEF--", "IUP---", "IMF---", "IMFA--",
            "IMFP--", "IMFPW-", "IMFS--", "IMA---", "IME---", "IMG---", "IMV---", "IMN---", "IMNB--",
            "IMC---", "IMS---", "IMM---", "IG----", "IB----", "IBA---", "IBN---", "IT----", "IX----",
            "IXH---"
        )
        private val warfightingSeaSurfaceFunctionIDs = arrayOf(
            "------", "C-----", "CL----", "CLCV--", "CLBB--", "CLCC--", "CLDD--", "CLFF--", "CLLL--",
            "CLLLAS", "CLLLMI", "CLLLSU", "CA----", "CALA--", "CALS--", "CALSM-", "CALST-", "CALC--",
            "CM----", "CMML--", "CMMS--", "CMMH--", "CMMA--", "CP----", "CPSB--", "CPSU--", "CPSUM-",
            "CPSUT-", "CPSUG-", "CH----", "G-----", "GT----", "GG----", "GU----", "GC----", "CD----",
            "CU----", "CUM---", "CUS---", "CUN---", "CUR---", "N-----", "NR----", "NF----", "NI----",
            "NS----", "NM----", "NH----", "XM----", "XMC---", "XMR---", "XMO---", "XMTU--", "XMF---",
            "XMP---", "XMH---", "XMTO--", "XF----", "XFDF--", "XFDR--", "XFTR--", "XR----", "XL----",
            "XH----", "XA----", "XAR---", "XAS---", "XP----", "O-----"
        )
        private val warfightingSubsurfaceFunctionIDs = arrayOf(
            "------", "S-----", "SF----", "SB----", "SR----", "SX----", "SN----", "SNF---", "SNA---",
            "SNM---", "SNG---", "SNB---", "SC----", "SCF---", "SCA---", "SCM---", "SCG---", "SCB---",
            "SO----", "SOF---", "SU----", "SUM---", "SUS---", "SUN---", "S1----", "S2----", "S3----",
            "S4----", "SL----", "SK----", "W-----", "WT----", "WM----", "WMD---", "WMG---", "WMGD--",
            "WMGX--", "WMGE--", "WMGC--", "WMGR--", "WMGO--", "WMM---", "WMMD--", "WMMX--", "WMME--",
            "WMMC--", "WMMR--", "WMMO--", "WMF---", "WMFD--", "WMFX--", "WMFE--", "WMFC--", "WMFR--",
            "WMFO--", "WMO---", "WMOD--", "WMX---", "WME---", "WMA---", "WMC---", "WMR---", "WMB---",
            "WMBD--", "WMN---", "WMS---", "WMSX--", "WMSD--", "WD----", "WDM---", "WDMG--", "WDMM--",
            "ND----", "E-----", "V-----", "X-----"
        )
        private val warfightingSOFFunctionIDs = arrayOf(
            "------", "A-----", "AF----", "AFA---", "AFK---", "AFU---", "AFUL--", "AFUM--", "AFUH--",
            "AV----", "AH----", "AHH---", "AHA---", "AHU---", "AHUL--", "AHUM--", "AHUH--", "N-----",
            "NS----", "NU----", "NB----", "NN----", "G-----", "GS----", "GR----", "GP----","GPA---",
            "GC----", "B-----"
        )
        var signalsIntelligenceSpaceFunctionIDs = arrayOf(
            "SCD---", "SRD---", "SRE---", "SRI---", "SRM---", "SRT---", "SRS---", "SRU---"
        )
        var signalsIntelligenceAirFunctionIDs = arrayOf(
            "SCC---", "SCO---", "SCP---", "SCS---", "SRAI--", "SRAS--", "SRC---", "SRD---", "SRE---",
            "SRF---", "SRI---", "SRMA--", "SRMD--", "SRMG--", "SRMT--", "SRMF--", "SRTI--", "SRTA--",
            "SRTT--", "SRU---"
        )
        var signalsIntelligenceGroundFunctionIDs = arrayOf(
            "SCC---", "SCO---", "SCP---", "SCS---", "SCT---", "SRAT--", "SRAA--", "SRB---", "SRCS--",
            "SRCA--", "SRD---", "SRE---", "SRF---", "SRH---", "SRI---", "SRMM--", "SRMA--", "SRMG--",
            "SRMT--", "SRMF--", "SRS---", "SRTA--", "SRTI--", "SRTT--", "SRU---"
        )

        // Warfighting
        var signalsIntelligenceSeaSurfaceFunctionIDs = arrayOf(
            "SCC---", "SCO---", "SCP---", "SCS---", "SRAT--", "SRAA--", "SRCA--", "SRCI--", "SRD---",
            "SRE---", "SRF---", "SRH---", "SRI---", "SRMM--", "SRMA--", "SRMG--", "SRMT--", "SRMF--",
            "SRS---", "SRTA--", "SRTI--", "SRTT--", "SRU---"
        )
        var signalsIntelligenceSubsurfaceFunctionIDs = arrayOf(
            "SCO---", "SCP---", "SCS---", "SRD---", "SRE---", "SRM---", "SRS---", "SRT---", "SRU---"
        )
        var stabilityOperationsViolentActivitiesFunctionIDs = arrayOf(
            "A-----", "M-----", "MA----", "MB----", "MC----", "B-----", "Y-----", "D-----", "S-----",
            "P-----", "E-----", "EI----"
        )
        var stabilityOperationsLocationsFunctionIDs = arrayOf(
            "B-----", "G-----", "W-----", "M-----"
        )
        var stabilityOperationsOperationsFunctionIDs = arrayOf(
            "P-----", "RW----", "RC----", "D-----", "M-----", "Y-----", "YT----", "YW----", "YH----",
            "F-----", "S-----", "O-----", "E-----", "HT----", "HA----", "HV----", "K-----", "KA----",
            "A-----", "U-----", "C-----", "CA----", "CB----", "CC----"
        )
        var stabilityOperationsItemsFunctionIDs = arrayOf(
            "R-----", "S-----", "G-----", "V-----", "I-----", "D-----", "F-----"
        )
        var stabilityOperationsIndividualFunctionIDs = arrayOf(
            "------", "A-----", "B-----", "C-----"
        )

        //  Signals Intelligence
        var stabilityOperationsNonmilitaryFunctionIDs = arrayOf(
            "------", "A-----", "B-----", "C-----", "D-----", "E-----", "F-----"
        )
        var stabilityOperationsRapeFunctionIDs = arrayOf(
            "------",
            "A-----"
        )
        var emergencyManagementIncidentsFunctionIDs = arrayOf(
            "A-----", "AC----", "B-----", "BA----", "BC----", "BD----", "BF----", "C-----", "CA----",
            "CB----", "CC----", "CD----", "CE----", "CF----", "CG----", "CH----", "D-----", "DA----",
            "DB----", "DC----", "DE----", "DF----", "DG----", "DH----", "DI----", "DJ----", "DK----",
            "DL----", "DM----", "DN----", "DO----", "E-----", "EA----", "F-----", "FA----", "G-----",
            "GA----", "GB----", "H-----", "HA----"
        )
        var emergencyManagementNaturalEventsFunctionIDs = arrayOf(
            "AA----", "AB----", "AC----", "AD----", "AE----", "AG----", "BB----", "BC----", "BF----",
            "BM----", "CA----", "CB----", "CC----", "CD----", "CE----"
        )
        var emergencyManagementOperationsFunctionIDs = arrayOf(
            "A-----H----", "AA---------", "AB---------", "AC----H----", "AD----H----", "AE---------",
            "AF---------", "AG----H----", "AJ----H----", "AK----H----", "AL----H----", "AM----H----",
            "B----------", "BA---------", "BB---------", "BC----H----", "BD---------", "BE----H----",
            "BF----H----", "BG----H----", "BH----H----", "BI----H----", "BJ---------", "BK----H----",
            "BL----H----", "C----------", "CA---------", "CB---------", "CC---------", "CD----H----",
            "CE----H----", "D----------", "DA---------", "DB---------", "DC----H----", "DD---------",
            "DDA--------", "DDB--------", "DDC---H----", "DE---------", "DEA--------", "DEB--------",
            "DEC---H----", "DF---------", "DFA--------", "DFB--------", "DFC---H----", "DG---------",
            "DGA--------", "DGB--------", "DGC---H----", "DH---------", "DHA--------", "DHB--------",
            "DHC---H----", "DI---------", "DIA--------", "DIB--------", "DIC---H----", "DJ---------",
            "DJB--------", "DJC---H----", "DK---------", "DL---------", "DLA--------", "DLB--------",
            "DLC---H----", "DM---------", "DMA--------", "DMB--------", "DMC---H----", "DN---------",
            "DNA--------", "DNC---H----", "DO---------", "DOA--------", "DOB--------", "DOC---H----",
            "EA---------", "EB---------", "EC---------", "ED---------", "EE---------"
        )

        //  Stability Operations
        var emergencyManagementInfrastructureFunctionIDs = arrayOf(
            "A----------", "AA----H----", "AB----H----", "AC----H----", "AD----H----", "AE----H----",
            "AF----H----", "AG----H----", "B-----H----", "BA---------", "BB----H----", "BC----H----",
            "BD----H----", "BE----H----", "BF----H----", "C-----H----", "CA----H----", "CB----H----",
            "CC----H----", "CD----H----", "CE----H----", "CF----H----", "CG----H----", "CH----H----",
            "CI----H----", "CJ----H----", "D-----H----", "DA----H----", "DB----H----", "EA----H----",
            "EB----H----", "EE----H----", "F-----H----", "G-----H----", "GA----H----", "H-----H----",
            "HA----H----", "HB----H----", "I-----H----", "IA----H----", "IB----H----", "IC----H----",
            "ID----H----", "J-----H----", "JA----H----", "JB----H----", "JC----H----", "K-----H----",
            "KB----H----", "LA----H----", "LD----H----", "LE----H----", "LF----H----", "LH----H----",
            "LJ----H----", "LK----H----", "LM----H----", "LO----H----", "LP----H----", "MA---------",
            "MB----H----", "MC---------", "MD----H----", "ME----H----", "MF----H----", "MG----H----",
            "MH----H----", "MI----H----"
        )
    }
}