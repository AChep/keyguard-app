package app.keemobile.kotpass.cryptography.engines

internal fun chachaCore(rounds: Int, input: IntArray, x: IntArray) {
    require(input.size == 16)
    require(x.size == 16)
    require(rounds % 2 == 0) { "Number of rounds must be even" }

    var i = rounds
    var x00 = input[0]
    var x01 = input[1]
    var x02 = input[2]
    var x03 = input[3]
    var x04 = input[4]
    var x05 = input[5]
    var x06 = input[6]
    var x07 = input[7]
    var x08 = input[8]
    var x09 = input[9]
    var x10 = input[10]
    var x11 = input[11]
    var x12 = input[12]
    var x13 = input[13]
    var x14 = input[14]
    var x15 = input[15]

    while (i > 0) {
        x00 += x04
        x12 = ((x12 xor x00).rotateLeft(16))
        x08 += x12
        x04 = ((x04 xor x08).rotateLeft(12))
        x00 += x04
        x12 = ((x12 xor x00).rotateLeft(8))
        x08 += x12
        x04 = ((x04 xor x08).rotateLeft(7))
        x01 += x05
        x13 = ((x13 xor x01).rotateLeft(16))
        x09 += x13
        x05 = ((x05 xor x09).rotateLeft(12))
        x01 += x05
        x13 = ((x13 xor x01).rotateLeft(8))
        x09 += x13
        x05 = ((x05 xor x09).rotateLeft(7))
        x02 += x06
        x14 = ((x14 xor x02).rotateLeft(16))
        x10 += x14
        x06 = ((x06 xor x10).rotateLeft(12))
        x02 += x06
        x14 = ((x14 xor x02).rotateLeft(8))
        x10 += x14
        x06 = ((x06 xor x10).rotateLeft(7))
        x03 += x07
        x15 = ((x15 xor x03).rotateLeft(16))
        x11 += x15
        x07 = ((x07 xor x11).rotateLeft(12))
        x03 += x07
        x15 = ((x15 xor x03).rotateLeft(8))
        x11 += x15
        x07 = ((x07 xor x11).rotateLeft(7))
        x00 += x05
        x15 = ((x15 xor x00).rotateLeft(16))
        x10 += x15
        x05 = ((x05 xor x10).rotateLeft(12))
        x00 += x05
        x15 = ((x15 xor x00).rotateLeft(8))
        x10 += x15
        x05 = ((x05 xor x10).rotateLeft(7))
        x01 += x06
        x12 = ((x12 xor x01).rotateLeft(16))
        x11 += x12
        x06 = ((x06 xor x11).rotateLeft(12))
        x01 += x06
        x12 = ((x12 xor x01).rotateLeft(8))
        x11 += x12
        x06 = ((x06 xor x11).rotateLeft(7))
        x02 += x07
        x13 = ((x13 xor x02).rotateLeft(16))
        x08 += x13
        x07 = ((x07 xor x08).rotateLeft(12))
        x02 += x07
        x13 = ((x13 xor x02).rotateLeft(8))
        x08 += x13
        x07 = ((x07 xor x08).rotateLeft(7))
        x03 += x04
        x14 = ((x14 xor x03).rotateLeft(16))
        x09 += x14
        x04 = ((x04 xor x09).rotateLeft(12))
        x03 += x04
        x14 = ((x14 xor x03).rotateLeft(8))
        x09 += x14
        x04 = ((x04 xor x09).rotateLeft(7))
        i -= 2
    }
    x[0] = x00 + input[0]
    x[1] = x01 + input[1]
    x[2] = x02 + input[2]
    x[3] = x03 + input[3]
    x[4] = x04 + input[4]
    x[5] = x05 + input[5]
    x[6] = x06 + input[6]
    x[7] = x07 + input[7]
    x[8] = x08 + input[8]
    x[9] = x09 + input[9]
    x[10] = x10 + input[10]
    x[11] = x11 + input[11]
    x[12] = x12 + input[12]
    x[13] = x13 + input[13]
    x[14] = x14 + input[14]
    x[15] = x15 + input[15]
}
