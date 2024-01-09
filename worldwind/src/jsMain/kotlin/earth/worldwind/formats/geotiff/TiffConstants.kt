package earth.worldwind.formats.geotiff

class TiffConstants {
    object IFDTag {
        const val NEW_SUBFILE_TYPE = 254
        const val SUBFILE_TYPE = 255
        const val IMAGE_WIDTH = 256
        const val IMAGE_LENGTH = 257
        const val BITS_PER_SAMPLE = 258
        const val COMPRESSION = 259
        const val PHOTOMETRIC_INTERPRETATION = 262
        const val THRESHHOLDING = 263
        const val CELL_WIDTH = 264
        const val CELL_LENGTH = 265
        const val FILL_ORDER = 266
        const val DOCUMENT_NAME = 269
        const val IMAGE_DESCRIPTION = 270
        const val MAKE = 271
        const val MODEL = 272
        const val STRIP_OFFSETS = 273
        const val ORIENTATION = 274
        const val SAMPLES_PER_PIXEL = 277
        const val ROWS_PER_STRIP = 278
        const val STRIP_BYTE_COUNTS = 279
        const val MIN_SAMPLE_VALUE = 280
        const val MAX_SAMPLE_VALUE = 281
        const val X_RESOLUTION = 282
        const val Y_RESOLUTION = 283
        const val PLANAR_CONFIGURATION = 284
        const val PAGE_NAME = 285
        const val X_POSITION = 286
        const val Y_POSITION = 287
        const val FREE_OFFSETS = 288
        const val FREE_BYTE_COUNTS = 289
        const val GRAY_RESPONSE_UNIT = 290
        const val GRAY_RESPONSE_CURVE = 291
        const val T4_OPTIONS = 292
        const val T6_OPTIONS = 293
        const val RESOLUTION_UNIT = 296
        const val PAGE_NUMBER = 297
        const val TRANSFER_FUNCTION = 301
        const val SOFTWARE = 305
        const val DATE_TIME = 306
        const val ARTIST = 315
        const val HOST_COMPUTER = 316
        const val PREDICTOR = 317
        const val WHITE_POINT = 318
        const val PRIMARY_CHROMATICI_TIES = 319
        const val COLOR_MAP = 320
        const val HALFTONE_HINTS = 321
        const val TILE_WIDTH = 322
        const val TILE_LENGTH = 323
        const val TILE_OFFSETS = 324
        const val TILE_BYTE_COUNTS = 325
        const val INK_SET = 332
        const val INK_NAMES = 333
        const val NUMBER_OF_INKS = 334
        const val DOT_RANGE = 336
        const val TARGET_PRINTER = 337
        const val EXTRA_SAMPLES = 338
        const val SAMPLE_FORMAT = 339
        const val S_MIN_SAMPLE_VALUE = 340
        const val S_MAX_SAMPLE_VALUE = 341
        const val TRANSFER_RANGE = 342
        const val JPEG_PROC = 512
        const val JPEG_INTERCHANGE_FORMAT = 513
        const val JPEG_INTERCHANGE_FORMAT_LENGTH = 514
        const val JPEG_RESTART_INTERVAL = 515
        const val JPEG_LOSSLESS_PREDICTORS = 517
        const val JPEG_POINT_TRANSFORMS = 518
        const val JPEG_Q_TABLES = 519
        const val JPEG_DC_TABLES = 520
        const val JPEG_AC_TABLES = 521
        const val Y_CB_CR_COEFFICIENTS = 529
        const val Y_CB_CR_SUB_SAMPLING = 530
        const val Y_CB_CR_POSITIONING = 531
        const val REFERENCE_BLACK_WHITE = 532
        const val COPYRIGHT = 33432
    }

    object Compression {
        const val UNCOMPRESSED = 1
        const val CCITT_1D = 2
        const val GROUP_3_FAX = 3
        const val GROUP_4_FAX = 4
        const val LZW = 5
        const val JPEG = 6
        const val PACK_BITS = 32773
    }

    object Orientation {
        const val ROW0_IS_TOP_COL0_IS_LHS = 1
        const val ROW0_IS_TOP_COL0_IS_RHS = 2
        const val ROW0_IS_BOTTOM_COL0_IS_RHS = 3
        const val ROW0_IS_BOTTOM_COL0_IS_LHS = 4
        const val ROW0_IS_LHS_COL0_IS_TOP = 5
        const val ROW0_IS_RHS_COL0_IS_TOP = 6
        const val ROW0_IS_RHS_COL0_IS_BOTTOM = 7
        const val ROW0_IS_LHS_COL0_IS_BOTTOM = 8
    }

    object PhotometricInterpretation {
        const val WHITE_IS_ZERO = 0
        const val BLACK_IS_ZERO = 1
        const val RGB = 2
        const val RGB_PALETTE = 3
        const val TRANSPARENCY_MASK = 4
        const val CMYK = 5
        const val Y_CB_CR = 6
        const val CIE_LAB = 7
    }

    object PlanarConfiguration {
        const val CHUNKY = 1
        const val PLANAR = 2
    }

    object ResolutionUnit {
        const val NONE = 1
        const val INCH = 2
        const val CENTIMETER = 3
    }

    object SampleFormat {
        const val UNSIGNED = 1
        const val SIGNED = 2
        const val IEEE_FLOAT = 3
        const val UNDEFINED = 4
    }

    object Type {
        const val BYTE = 1
        const val ASCII = 2
        const val SHORT = 3
        const val LONG = 4
        const val RATIONAL = 5
        const val SBYTE = 6
        const val UNDEFINED = 7
        const val SSHORT = 8
        const val SLONG = 9
        const val SRATIONAL = 10
        const val FLOAT = 11
        const val DOUBLE = 12
    }
}