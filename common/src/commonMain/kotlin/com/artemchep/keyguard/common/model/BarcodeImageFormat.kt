package com.artemchep.keyguard.common.model

/**
 * Enumerates barcode formats known to this package.
 * Please keep alphabetized.
 */
enum class BarcodeImageFormat {
    /** Aztec 2D barcode format.  */
    AZTEC,

    /** CODABAR 1D format.  */
    CODABAR,

    /** Code 39 1D format.  */
    CODE_39,

    /** Code 93 1D format.  */
    CODE_93,

    /** Code 128 1D format.  */
    CODE_128,

    /** Data Matrix 2D barcode format.  */
    DATA_MATRIX,

    /** EAN-8 1D format.  */
    EAN_8,

    /** EAN-13 1D format.  */
    EAN_13,

    /** ITF (Interleaved Two of Five) 1D format.  */
    ITF,

    /** MaxiCode 2D barcode format.  */
    MAXICODE,

    /** PDF417 format.  */
    PDF_417,

    /** QR Code 2D barcode format.  */
    QR_CODE,

    /** RSS 14  */
    RSS_14,

    /** RSS EXPANDED  */
    RSS_EXPANDED,

    /** UPC-A 1D format.  */
    UPC_A,

    /** UPC-E 1D format.  */
    UPC_E,

    /** UPC/EAN extension format. Not a stand-alone format.  */
    UPC_EAN_EXTENSION,
}
