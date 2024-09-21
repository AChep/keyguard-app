package com.artemchep.keyguard.common.service.googleauthenticator.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
Schema is available here
https://github.com/dim13/otpauth/blob/master/migration/migration.proto
under ISC License 2020 Dimitri Sokolyuk <demon@dim13.org>

message Payload {
  message OtpParameters {
    enum Algorithm {
      ALGORITHM_UNSPECIFIED = 0;
      ALGORITHM_SHA1 = 1;
      ALGORITHM_SHA256 = 2;
      ALGORITHM_SHA512 = 3;
      ALGORITHM_MD5 = 4;
    }
    enum DigitCount {
      DIGIT_COUNT_UNSPECIFIED = 0;
      DIGIT_COUNT_SIX = 1;
      DIGIT_COUNT_EIGHT = 2;
    }
    enum OtpType {
      OTP_TYPE_UNSPECIFIED = 0;
      OTP_TYPE_HOTP = 1;
      OTP_TYPE_TOTP = 2;
    }
    bytes secret = 1;
    string name = 2;
    string issuer = 3;
    Algorithm algorithm = 4;
    DigitCount digits = 5;
    OtpType type = 6;
    uint64 counter = 7;
  }
  repeated OtpParameters otp_parameters = 1;
  int32 version = 2;
  int32 batch_size = 3;
  int32 batch_index = 4;
  int32 batch_id = 5;
}
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OtpAuthMigrationData(
    @ProtoNumber(1)
    @SerialName("otp_parameters")
    val otpParameters: List<OtpParameters>,
    @ProtoNumber(2)
    val version: Int = 0,
    @ProtoNumber(3)
    @SerialName("batch_size")
    val batchSize: Int = 0,
    @ProtoNumber(4)
    @SerialName("batch_index")
    val batchIndex: Int = 0,
    @ProtoNumber(5)
    @SerialName("batch_id")
    val batchId: Int = 0,
) {
    @Serializable
    data class OtpParameters(
        @ProtoNumber(1)
        val secret: ByteArray,
        @ProtoNumber(2)
        val name: String? = null,
        @ProtoNumber(3)
        val issuer: String? = null,
        @ProtoNumber(4)
        val algorithm: Algorithm = Algorithm.ALGORITHM_UNSPECIFIED,
        @ProtoNumber(5)
        val digits: DigitCount = DigitCount.DIGIT_COUNT_UNSPECIFIED,
        @ProtoNumber(6)
        val type: Type = Type.OTP_TYPE_UNSPECIFIED,
        @ProtoNumber(7)
        val counter: Int? = null,
    ) {
        @Serializable
        enum class Algorithm {
            @ProtoNumber(0)
            ALGORITHM_UNSPECIFIED,
            @ProtoNumber(1)
            ALGORITHM_SHA1,
            @ProtoNumber(2)
            ALGORITHM_SHA256,
            @ProtoNumber(3)
            ALGORITHM_SHA512,
            @ProtoNumber(4)
            ALGORITHM_MD5,
        }

        @Serializable
        enum class DigitCount {
            @ProtoNumber(0)
            DIGIT_COUNT_UNSPECIFIED,
            @ProtoNumber(1)
            DIGIT_COUNT_SIX,
            @ProtoNumber(2)
            DIGIT_COUNT_EIGHT,
        }

        @Serializable
        enum class Type {
            @ProtoNumber(0)
            OTP_TYPE_UNSPECIFIED,
            @ProtoNumber(1)
            OTP_TYPE_HOTP,
            @ProtoNumber(2)
            OTP_TYPE_TOTP,
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as OtpParameters

            if (!secret.contentEquals(other.secret)) return false
            if (name != other.name) return false
            if (issuer != other.issuer) return false
            if (algorithm != other.algorithm) return false
            if (digits != other.digits) return false
            if (type != other.type) return false
            if (counter != other.counter) return false

            return true
        }

        override fun hashCode(): Int {
            var result = secret.contentHashCode()
            result = 31 * result + (name?.hashCode() ?: 0)
            result = 31 * result + (issuer?.hashCode() ?: 0)
            result = 31 * result + algorithm.hashCode()
            result = 31 * result + digits.hashCode()
            result = 31 * result + type.hashCode()
            result = 31 * result + (counter ?: 0)
            return result
        }
    }
}
