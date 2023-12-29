package com.artemchep.keyguard.common.model

class BiometricAuthException(
    val code: Int,
    message: String,
) : RuntimeException(message) {
    companion object {
        /**
         * There is no error, and the user can successfully authenticate.
         */
        const val BIOMETRIC_SUCCESS = 0

        const val ERROR_UNKNOWN = 999_999

        /**
         * The hardware is unavailable. Try again later.
         */
        const val ERROR_HW_UNAVAILABLE = 1

        /**
         * The sensor was unable to process the current image.
         */
        const val ERROR_UNABLE_TO_PROCESS = 2

        /**
         * The current operation has been running too long and has timed out.
         *
         *
         * This is intended to prevent programs from waiting for the biometric sensor indefinitely.
         * The timeout is platform and sensor-specific, but is generally on the order of ~30 seconds.
         */
        const val ERROR_TIMEOUT = 3

        /**
         * The operation can't be completed because there is not enough device storage remaining.
         */
        const val ERROR_NO_SPACE = 4

        /**
         * The operation was canceled because the biometric sensor is unavailable. This may happen when
         * the user is switched, the device is locked, or another pending operation prevents it.
         */
        const val ERROR_CANCELED = 5

        /**
         * The operation was canceled because the API is locked out due to too many attempts. This
         * occurs after 5 failed attempts, and lasts for 30 seconds.
         */
        const val ERROR_LOCKOUT = 7

        /**
         * The operation failed due to a vendor-specific error.
         *
         *
         * This error code may be used by hardware vendors to extend this list to cover errors that
         * don't fall under one of the other predefined categories. Vendors are responsible for
         * providing the strings for these errors.
         *
         *
         * These messages are typically reserved for internal operations such as enrollment but may
         * be used to express any error that is not otherwise covered. In this case, applications are
         * expected to show the error message, but they are advised not to rely on the message ID, since
         * this may vary by vendor and device.
         */
        const val ERROR_VENDOR = 8

        /**
         * The operation was canceled because [.ERROR_LOCKOUT] occurred too many times. Biometric
         * authentication is disabled until the user unlocks with their device credential (i.e. PIN,
         * pattern, or password).
         */
        const val ERROR_LOCKOUT_PERMANENT = 9

        /**
         * The user canceled the operation.
         *
         *
         * Upon receiving this, applications should use alternate authentication, such as a password.
         * The application should also provide the user a way of returning to biometric authentication,
         * such as a button.
         */
        const val ERROR_USER_CANCELED = 10

        /**
         * The user does not have any biometrics enrolled.
         */
        const val ERROR_NO_BIOMETRICS = 11

        /**
         * The device does not have the required authentication hardware.
         */
        const val ERROR_HW_NOT_PRESENT = 12

        /**
         * The user pressed the negative button.
         */
        const val ERROR_NEGATIVE_BUTTON = 13

        /**
         * The device does not have pin, pattern, or password set up.
         */
        const val ERROR_NO_DEVICE_CREDENTIAL = 14

        /**
         * A security vulnerability has been discovered with one or more hardware sensors. The
         * affected sensor(s) are unavailable until a security update has addressed the issue.
         */
        const val ERROR_SECURITY_UPDATE_REQUIRED = 15
    }
}
